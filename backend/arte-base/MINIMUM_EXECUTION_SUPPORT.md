# 最小执行支撑

依据顶层设计 §1.3、§2.9 及异步、背压、审计和存储边界，补齐独立新执行链需要的公共机制。 base 生产代码仍仅依赖 JDK，ai-new 仍只依赖
base。数据库和文件适配位于 app 的新组合包，旧入口及引用不变。

## 已实现的范围

| 能力     | 公共类型                                                                     | 本批实现                                                                                                  |
|----------|------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| 准入     | AdmissionController、AdmissionRequest／Key／Limits、AdmissionAttempt／Permit | LocalAdmissionController：预登记有限分区、总并发及分区并发、固定窗口速率、有界优先队列和等待期限          |
| 异步工作 | TaskExecutor、ExecutionTask、ExecutionCheckpoint、TaskHandle                 | BoundedTaskExecutor：专用线程、有界队列、显式上下文、合作式取消及期限检查                                 |
| 事件输出 | ExecutionEvent、EventDelivery                                                | BoundedEventStream：Flow 背压、每订阅者有限缓冲、有限订阅人数、单调序号和慢订阅者断开                     |
| 审计     | AuditSink、AuditRecord／Outcome／Receipt                                     | app JdbcAuditSink：独立提交、事实摘要、相同事件幂等追加、冲突拒绝                                         |
| 文件产物 | ArtifactStore、ArtifactUpload／Content、Artifact／Ref／Status                | app JdbcFileArtifactStore：数据库元数据、私有字节、大小／SHA-256 校验、隔离生命周期、条件更新和作用域隔离 |
| 采样观测 | Telemetry、Span、有限标签键                                                  | 定义指标／追踪操作，提供显式 disabled 实现；本批未接入 Micrometer／OpenTelemetry                          |

本说明只描述公共支撑，不将本地工作／事件工具作为权威业务状态库。 后续 [最小模型调用](../arte-ai-new/MINIMUM_MODEL_CALL.md)
已组合可靠受理、单次尝试、原子预算及终态／结果／耐久事件。 多实例执行归属、可靠消息、Job 调度与流式恢复仍需后续实现。

## 准入与异步执行

AdmissionKey 将租户、工作类型、适用供应商和固定连接分区。请求租户必须匹配上下文，未知分区返回 POLICY_UNAVAILABLE。
策略在构造时登记有限集合，不按任意请求键无限创建状态。并发和速率独立：释放许可不会退还已消耗的速率配额。 队列满载／等待超时返回
BUSY，速率耗尽的立即请求返回 RATE_LIMITED，执行期限到达返回 DEADLINE_EXCEEDED。
交互请求优先于后台请求；相同优先级按进入顺序处理。饱和分区不会阻止其他有容量分区从队列取得许可；持续交互负载下未承诺后台公平性。
等待时间取 maxWait 与上下文 deadline 的较早值，时钟倒退时停止新的等待放行。

AdmissionAttempt 的 completion 是只读结果视图；cancelWaiting 只取消尚未授予的等待。 许可已授予后，必须等实际工作退出再
close；不能因 HTTP 断线、取消请求或等待 future 的副本被取消而提前释放。 许可重复 close 无副作用，没有会自动释放仍在执行工作的租约超时。

BoundedTaskExecutor 使用固定工作线程和有界队列，满载直接拒绝，不使用 CallerRunsPolicy 把阻塞 SDK 放到提交线程。 TaskHandle
表示本地 QUEUED／RUNNING／SUCCEEDED／FAILED／CANCELLED／TIMED_OUT，completion 不允许调用方修改实际结果。
队列中的工作取消／到期后立即移除并完成，运行中的工作只收到停止请求，未来结果要等 Java 工作退出后完成。 ExecutionCheckpoint
在开始、返回及实现主动检查的位置检查期限／取消／线程中断。onStop 可注册 socket 等停止资源，取消和到期时关闭 I/O，仍不提前完成工作。
close 请求合作式停止；shutdownGracefully 先停止接收新工作，限时等待结束，超时再请求停止并短暂等待实际退出。
不会强杀线程、自动取消供应商任务或替底层 SDK 设置 I/O 超时；实现应使用 deadline 设置连接／操作超时并在后续操作前
checkpoint.check 和重新授权。

开始前拒绝确认 NONE／CONFIRMED，BUSY／RATE_LIMITED／POLICY_UNAVAILABLE 可在重新检查条件后重试。 运行后停止或未知异常保守保留
UNKNOWN／UNKNOWN，不把取消或超时包装成可盲目重试。 任务主动抛出的 BaseException 保留所属阶段的事实，执行协调层仍须结合先前步骤记录整个调用的副作用。
上下文显式传入，不复制旧 UserOnlineInfo／ThreadLocal。旧 ThreadPoolUtil 仅增加 Deprecated 和迁移说明，旧线程池、MDC 和引用保持原样。

最小组合方式（仍需所属模块先落实认证、授权、参数与预算）：

```java
AdmissionAttempt waiting = admission.acquire(request);
waiting.completion().thenCompose(permit -> {
    try {
        TaskHandle<Result> task = executor.submit(request.context(), checkpoint -> {
            checkpoint.check();
            // 在专用工作线程内进行适用的审计持久化、再次授权和业务执行。
            return handler.execute(checkpoint);
        });
        return task.completion().whenComplete((result, failure) -> permit.close());
    } catch (RuntimeException failure) {
        permit.close();
        throw failure;
    }
});
```

运行后的控制通过 TaskHandle.requestCancellation，等待阶段通过 waiting.cancelWaiting。 后续执行协调层将这些句柄关联耐久
executionId，并实现业务控制授权；本批没有网络控制接口。

## 本地事件与背压

BoundedEventStream 固定 executionId 和 attemptId，拒绝混入其他流或重复／倒退序号。 每个订阅者只在 request (n)
后收到事件；非正需求终止该订阅者。满缓冲会清空该订阅者的本地积压并以 BUSY 断开，不静默继续拼接缺失输出。 回调在专用有界
dispatch 线程上执行，坏订阅者不能影响其他订阅者，取消释放其缓冲及尚未执行的派发项。 complete 按需求排空后结束；close
明确丢弃本地积压。慢回调必须返回后才能收到终止通知，不通过并发 onError 打断正在执行的 onNext。

EventDelivery 仅表示本地入队／断开数量，不是消费确认或耐久回执。无订阅者时不会保存事件，也没有游标或历史。 未来 AI 链路应先通过
ExecutionEventStore 持久化输出批次，再用本地流通知；断线或溢出后从权威存储重放。 网络订阅须在入口及批次边界授权，不能因持有
executionId 获得权限。 每个流有界，上层仍须限制总活跃流数和及时取消断开的订阅者。

## 审计和观测

AuditRecord 只接收主体／执行标识、资源引用、固定结果、原因码和策略版本，不提供正文、Token、密码、任意参数或自由错误文案字段。
集合防御性复制；app 编码采用显式 v1 二进制格式，按原因码／策略键排序，拒绝超过 64 KiB 的事实，不使用任意 Java 反序列化。
JdbcAuditSink 使用 REQUIRES_NEW / READ_COMMITTED 事务，提交成功后返回 AuditReceipt；外层业务事务回滚不会抹掉已记录的审计事实。
相同 eventId 与相同事实返回原回执，相同 eventId 不同事实或不一致的存储内容拒绝覆盖。 错误／提交结果未知时不返回成功回执，后续可按原
eventId 重试核对。SHA-256 支持事实一致性检测，不代替数据库受限账号、备份、审计保留或独立防篡改设施。

提供者负责追加，不自动捕获所有授权／外发调用。新协调链应按策略在敏感操作前显式追加适用事实，审计失败进入明确失败流程；本批未修改既有授权入口。
Telemetry 允许采样、明确关闭，不能作为审计替代。标签键仅为 COMPONENT／OPERATION／OUTCOME，取值须由实现保持有限集合；主体／执行
ID 放入追踪上下文。

## 私有文件产物

Artifact 增加完整 ExecutionScope 和 revision，补齐原声明的结构／时间校验；ArtifactRef 使用 sha256: 加 64 位小写十六进制摘要。
所有查询／读取／生命周期操作按完整租户、空间、主体 ID 和主体种类隔离。scope 必须来自已验证的服务端上下文；存储端口不能作为接收客户端自报身份的下载接口。
内部元数据归属和隔离不代替领域的读取／下载／分享权限。文章附件引用关系由文章领域管理，保存产物不会自动插入文章或授予他人访问权。

上传流式写入有界字节，计算实际摘要，核对可选 expectedDigest，force 文件、同目录原子重命名并同步目录，再提交元数据。
调用方拥有上传输入流的关闭责任；打开的 ArtifactContent 必须关闭。读取验证实际大小与摘要，复用同一文件句柄，拒绝符号链接，并在返回前再次核对当前元数据。
文件名由服务端 UUID 生成，API 不接收任意路径、网络位置、凭据或公开 URL；存储目录应放在受限的持久化数据路径，不能由静态 Web
服务器直接暴露。 此本地提供者要求文件系统支持 ATOMIC_MOVE 和目录 force，不以静默退化代替它们。

上传成功仅为 QUARANTINED，不能直接下载或跳到 AVAILABLE。可信文件验证服务经 VALIDATING 后才能开放；本批没有伪造恶意内容扫描或文件格式验证结果。
生命周期更新使用 expectedRevision 条件写入，过期临时产物不能读取／发布。REFERENCED 清除临时到期时间，禁止此最小接口自动删除或解除仍被引用的文件。
后续领域解除引用、保留、配额及孤儿回收需要单独流程，不按一个临时 TTL 删除正式附件。

数据库和文件系统不在同一事务：上传元数据提交出错时保留已写私有字节，避免删除可能已提交记录引用的数据，后续需核对孤儿。 删除先提交
DELETED 墓碑，再清理字节；清理失败仍拒绝新读取，可按当前墓碑 revision 重试。已打开的读取流不能凭数据库撤销收回已读取字节。
恢复、共享存储及多节点文件可达性仍需部署验证，本批未声明这些生产保证。

## 部署和接入

先执行 `arte-app/scripts/arte-execution-support-ddl-mysql.sql` 创建审计和产物元数据表。本次只提供增量脚本，没有对运行数据库执行。
app 的 NewExecutionSupportConfiguration 默认不开启，不改变旧文件、审计、线程池或旧 AI 的行为。

当前可显式启用单实例支撑：

```yaml
arte:
  execution:
    support:
      enabled: true
      mode: single-instance
      tenant-id: personal-1
      artifact-directory: /var/lib/arte/private-artifacts
      max-artifact-bytes: 16777216
      threads: 4
      queue-capacity: 32
      starts-per-minute: 60
```

组合层注册上述租户的 ai.interactive 分区，无供应商／连接专有分区的隐式默认放行。 多租户或按连接限额时，组合层须以已注册配置构造对应
AdmissionKey／Limits 集合。 LocalAdmissionController 的限制只覆盖当前实例，重启会重置其本地速率窗口； ai-new 的
DurableModelWorker
另以数据库单活租约和持久化启动窗口约束模型派发，见 [模型调用说明](../arte-ai-new/MINIMUM_MODEL_CALL.md)。配置拒绝将其自动启用为
multi-instance 模式； 多实例付费调用须由后续权威全局准入／预算提供者落实，本批未接入 Redis 全局额度或预算账本。
这些默认容量尚未经过负载基准测试。

在新入口可按 AdmissionController、TaskExecutor、ArtifactStore、AuditSink 和 Telemetry 注入。 AI 核心只依赖公共端口，不导入
app 提供者；下一步实现默认连接／绑定、ModelGateway、InvocationCoordinator、执行记录、预算和耐久事件链。

## 验证

```bash
# backend 目录：公共支撑和 AI 新包的独立编译／测试
mvn -o -pl arte-base,arte-ai-new -am test
# app 新提供者和上一轮身份／权限接入回归
mvn -o -pl arte-app -am -Dmaven.compiler.proc=full \
  -Dtest='ExecutionSupportStorageTest,ExecutionSupportConfigurationTest,IdentityAndContextIntegrationTest,AuthorizationIntegrationTest,EgressIntegrationTest,SecurityWiringIntegrationTest,PersonalScopeBackfillIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖队列／总并发／分区并发边界、优先级、速率窗口、取消竞态、等待期限、工作实际退出才完成、只读结果视图、事件背压与溢出、
坏订阅者隔离、审计幂等／冲突／外层回滚、产物重启恢复、作用域隔离、大小／摘要／符号链接、生命周期冲突、引用保留、删除墓碑和元数据故障。
存储集成测试使用 H2 和真实临时文件；仅剥离 MySQL 的引擎／字符集声明，不修改生产 DDL。 尚未完成真实
MySQL、跨实例、断电、负载、供应商调用或网络出口验证。
