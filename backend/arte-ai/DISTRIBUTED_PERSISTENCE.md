# AI 执行存储与预算事务契约 v1

本轮将原来空的 ExecutionStore、ExecutionEventStore 和 BudgetService 落实为 Reactor 方法契约，提供 `ainew.persistence.mybatis.MybatisExecutionPersistence` 的同库事务实现。所有新运行代码在 `com.arte.ainew`，没有自动注册 Bean，也没有接入旧 AI、控制器或供应商调用。

## 1. 原子边界

| 操作 | 一次事务中的保证 | 竞争／恢复结果 |
|---|---|---|
| accept | 幂等记录、Invocation、可选 Turn、会话版本／活跃调用门闩、ACCEPTED 事件、派发 Outbox 一起提交 | 同键同摘要返回原 Invocation；同键异摘要拒绝；会话版本过旧或已有活跃调用拒绝 |
| createAttempt | 校验 Invocation 版本、期限、前一次尝试的安全重试事实；分配唯一序号、递增 fencing token，关联 activeAttempt，写 STARTED 事件及 Outbox | 每次创建增加 Invocation.version；消息重投不能无条件创建新尝试；未知副作用先核对 |
| acquireLease / renewLease | 租约依据数据库时钟，检查版本／activeAttempt；接管时递增 fencing token；续租增加 Attempt.version | 过期 Worker 的追加、发送标记、续租和提交全部拒绝 |
| markDispatch | 在外部调用前耐久标记 MAY_HAVE_EXECUTED；有 budgetRef 时验证有效 RESERVED 预留 | 必须成功提交后才发送；发送失败／超时不恢复 NOT_STARTED |
| updateConditionally | 两份版本、Worker、token、有效租约同时校验；只保存有明确事实的失败 Attempt | 可能执行且副作用未排除时拒绝；Invocation 终态另走 commitCompletion |
| appendBatch | 同一 Invocation 跨 Attempt 分配连续 sequence；整批事件、batchKey、防重摘要、输出字节计数及发布 Outbox 一起提交 | 同键同批次返回原序号；同键异内容拒绝；超过输出上限整批拒绝 |
| commitCompletion | Invocation 终态、Attempt、ResultRef、终态事件、完成防重记录、发布 Outbox 一起提交 | 同 completionKey 返回原提交快照；已知终态不能覆盖；结果字节应由可信结果存储预先落盘 |
| reserve | 锁定账户，检查币种、固定费率、余额；预留、held、Attempt 关联一起提交 | 每 Attempt 最多一个预留；重投不重复占用；余额不足拒绝 |
| settle | 预留版本 CAS、settlementKey 防重、账户 held／charged、预留状态及结算事实一起提交 | 同键异内容拒绝；最终结算只能一次；数据库故障整笔回滚 |

可预期拒绝返回 `StoreOutcome.Code`；数据库、编码或事务故障进入 Mono.onError。不能把 onComplete、取消订阅、构造快照或写入本地缓存当作耐久成功。读对象接口在不存在或归属不符时返回空 Mono；调用者仍须检查当前权限和 grant。

幂等作用域固定为 tenant + workspace + subject + capability.id。定义版本、绑定版本和目标会话／动作身份进入服务端规范化 requestDigest，不通过改变定义版本绕开同键防重。字符串键采用 UTF-8 长度前缀 SHA-256，避免拼接歧义及 MySQL 排序规则造成大小写混淆。requestDigest 仍由可信准入层计算，存储不从客户端自报摘要推断输入正确。

会话受理使用 AdmissionCatalogStore 的独立新表，不读取旧会话表。新 Turn 从 sequence=1 连续递增；已有 Turn 的重新生成仅追加 Invocation，并比较 Turn.version，保持用户输入、历史路径和已有选中候选。受理增加 Conversation.version；确定终态释放活跃调用门闩，UNKNOWN 继续占据门闩直至核对收敛。`findConversation` 和 `findTurn` 提供当前受理快照，不替代完整会话编辑／删除服务。

## 2. Worker 与崩溃恢复

1. 准入层完成当前授权、Schema、固定引用、上下文快照与规范化摘要验证，调用 accept。成功提交后才生成 AcceptedExecution。
2. 独立轮询器 claim(DISPATCH) 获取耐久任务指针及受信 owner，读取原 Invocation，并通过 ExecutionContextFactory 重新授权／恢复执行上下文。
3. 使用当前 Invocation.version 创建 Attempt；存储分配序号和 fencing token。重复派发先读取现状，不能在冲突后直接递增尝试数。
4. 计费绑定必须提供 budgetRef。reserve 成功会增加 Attempt.version；重新读取 Attempt 后构造 Guard。
5. markDispatch 成功也增加 Attempt.version；重新读取最新 Guard，再调用供应商。输出先 appendBatch，外部发布交给 Outbox。事务内部不执行供应商请求或推送。
6. 验证结果并耐久保存结果字节，再 commitCompletion。明确取消／失败也提交相应事实；尚无 Attempt 时使用 CompleteBeforeAttempt。费用使用独立结算证据提交，不因执行结束自动释放。

Guard 同时包含 Invocation.version、Attempt.version、activeAttemptId、Worker 和 token。createAttempt 增加 Invocation.version；续租、接管、预留、发送标记和 Attempt 更新增加 Attempt.version；appendBatch 增加事件序号／字节计数，但不增加两份执行版本；终态提交增加两份执行版本。冲突后重新读取权威状态，再判断操作是否仍被允许，不盲目 retry()。

过期且 NOT_STARTED 的 Attempt 可以通过 EXECUTE 租约接管。已经 MAY_HAVE_EXECUTED／CONFIRMED 的尝试只允许 RECONCILE 租约，markDispatch 对此租约拒绝；核对提交必须提供受信 evidenceRef。UNKNOWN 不能创建新 Attempt，只能核对为 SUCCEEDED／FAILED／CANCELLED。完成核对的 evidenceRef 和结算的证据类型／引用随事务耐久保存。证据引用的真实性和远端状态由可信协调器／账单服务验证；非空字符串本身不是证明。

只有已知、允许重试的失败，且没有发送或已排除副作用，才能新增 Attempt，仍受 maxAttempts 与绝对 deadline 限制。存储不能保证外部系统 exactly-once；供应商幂等请求键可提供额外保证，未知结果始终先核对。Worker 和 Outbox 轮询器是下一阶段应用实现，本轮没有启动后台执行线程。

## 3. 预算与费用事实

预算账户由可信控制面显式创建，初始 held、charged 和 version 必须为零。一个账户只有一个币种和固定费率版本；计费估算由可信费率策略提供，客户端不能指定任意低估额度。快速限流不能代替此账户事务。

费用未知时 settle(PENDING_RECONCILIATION) 保留全部 held。预留到期只禁止新的发送，不自动返还资金；取消、网络超时和用量缺失同样不作为零费用证据。

最终结算使用 PROVIDER_BILL；明确未发送的释放使用 PROVEN_NOT_DISPATCHED，并在事务内再次检查 Attempt.dispatch。已发送但确认零费用的释放需要可信账单证据。最终结算一次性从 held 减去原预留，并把实际 charge 计入 charged。实际费用超过预留／限额时仍完整入账，available 可以为负，此后新的预留被拒绝。

reserve 重投返回已有预留的当前快照；settle 同键重投返回该结算首次提交的快照，后续核对使用新键和当前预留版本。通过 reservation(owner,id) 读取最新状态。相同 settlementKey 的金额、用量、状态、recordedAt 或证据发生变化时拒绝，最终状态不接受另一个键再次扣费。

## 4. 发布、重放与保留

Outbox 是数据库中的耐久指针，有独立 Worker、租约及 token。领取超时可重投；发布后 acknowledge，旧 token 的 ACK 被拒绝，同一次已提交 ACK 可重放。DISPATCH 的 ACK 表示消息已处理，不代表 Invocation 已成功。claim 的交付语义为至少一次，消费者以 messageId／业务键防重。

EVENT 指针只代表“有已提交事件可读”，发布通知可以乱序、重复或丢失；订阅者按排他 Cursor 从 EventStore 有序读取，不直接用通知推进权威终态。replay 为最多 256 条的一页，next 保留最后读取位置，不启动执行。

discardThrough 仅裁剪已知终态、已确认发布的历史，并耐久保存 retainedAfterSequence；过旧游标返回 CURSOR_EXPIRED，订阅层改读权威快照。裁剪后的 append 重投若原批次已不存在也返回 CURSOR_EXPIRED，不能冒充再次成功写入。调用者负责保留期限／归档策略；当前不自动清除幂等、结算或 Outbox 记录。

## 5. 显式装配与部署

目标数据库为 MySQL 8，全部新表使用 InnoDB。`scripts/arte-ai-new-ddl-mysql.sql` 提供建表与索引 DDL，按既有脚本风格声明字段、约束、索引及中文注释。表名、约束名及索引名统一使用 `arte_ai_*` 命名；新存储会话表使用 `arte_ai_conversation_new`，避免与旧会话表重名。脚本由部署流程显式执行，不放入运行时 classpath、不随应用启动执行；Maven 仅将它复制为测试资源，测试通过 ResourceDatabasePopulator 显式初始化临时 H2 数据库。该脚本用于首次建表，不能在已有同名表的数据库上重复执行。若此前已手动创建其他前缀的执行存储表，须通过独立迁移处理表名及已有数据，再使用新 Mapper。`arte_ai_conversation_new` 与旧 `arte_ai_conversation` 使用不同表名及数据结构，新旧实现可以在同库中分别使用各自表；不能直接复用旧会话表。此脚本不包含删除、覆盖或迁移旧表的操作。本轮未修改任何实际数据库。

```java
// 控制面迁移完成后装配；专用线程数量、队列容量按数据库连接池和准入容量设置。
Scheduler persistenceScheduler = Schedulers.newBoundedElastic(8, 256, "ainew-mybatis");
var persistence = new MybatisExecutionPersistence(
        dataSource, new JacksonExecutionRecordCodec(), persistenceScheduler);
// 同一个 persistence 提供 ExecutionStore、ExecutionEventStore、ExecutionOutboxStore、
// AdmissionCatalogStore 和 BudgetService；应用关闭时 dispose persistenceScheduler。
```

MybatisExecutionPersistence 显式创建独立 ExecutionSqlSessionFactory 和 SqlSessionTemplate，共用同一 DataSourceTransactionManager 管理的连接。该工厂不注册为默认 Bean，不加入旧 MapperScan，也不继承旧审计、权限、乐观锁或分页插件；cacheEnabled=false、localCacheScope=STATEMENT、默认 SIMPLE 执行器，锁查询和数据库时钟均不从缓存返回。内部快照的 SQL 参数日志关闭，避免输入／授权数据进入 debug 日志。Mapper 按 Execution、Admission、Event、Outbox、Budget、System 划分，SQL 全部位于同名 XML，投影使用 PersistenceRows 类型，不用动态表名或 Map 强转。JacksonExecutionRecordCodec 位于 persistence.codec，编码协议独立于数据库实现。

这是 Reactor 接口下的 MyBatis／JDBC 阻塞隔离适配器，数据库事务仍是阻塞调用；Mono 为冷执行，整个事务在同一个 Scheduler 工作线程内完成。误用 Reactor 非阻塞线程 Scheduler 会在开始数据库工作前报错；需要全链路非阻塞时另实现同契约的 R2DBC 适配器。所有端口必须共享同一权威 DataSource，不能将某个写端口替换到另一数据库后仍宣称原子提交。数据库与 JDBC 会话时区统一 UTC；租约／受理／更新时间使用数据库时钟，不用各 Worker 的本地时间仲裁。

内部快照采用独立 Jackson mapper、schemaVersion=1 和稳定类型别名白名单，拒绝任意类名及未登记输入／事件版本，不使用 Java 原生反序列化。StructuredValue 在内部快照使用显式类型包装；传输层标准 JSON DTO 和 Schema 升级迁移仍须单独实现。

本轮提供权威存储与账本基础，不代表 AI 层已完整达到上线条件。仍须接入真实准入／协调器、供应商与结果存储、轮询发布／订阅、证据验证及部署迁移。授权解析、费用策略和结果字节校验不能省略。

## 6. 验证

从 backend 执行：

```bash
mvn -o -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.core.interceptor.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.contract.*Test,com.arte.ainew.context.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

新测试使用两个独立适配器共享 H2 MySQL 模式数据库，覆盖并发受理、会话门闩／重新生成、CAS、过期 Worker、安全重试／未知结果核对、跨实例事件序号、输出上限、第二条事件写入失败回滚、终态／结果／Outbox 回滚、发布者崩溃重领、游标过期、预算并发不足、待对账、重复结算、超额实费及账本故障回滚。另有独立工厂隔离、同事务多 Mapper 回滚、数据库时钟／锁查询不走缓存和错误 Scheduler 拒绝测试；旧插件测试验证 JDBC 实际绑定、多行 INSERT、BATCH／REUSE、忽略作用域、解析失败、创建人写保护及 MyBatis-Plus 分页／乐观锁兼容性。MyBatis 迁移阶段的 77 项相关回归及应用编译已通过；本次 DDL 调整验证了 26 项存储测试，包括装配阶段不访问数据库、不执行 DDL 的验证。H2 契约测试不能替代部署环境中的 MySQL 驱动、隔离级别、锁竞争和故障切换验证。

旧业务插件的策略、兼容性变化及使用示例见 [MyBatis 拦截器说明](../arte-core/MYBATIS_INTERCEPTORS.md)。新 AI 的 owner 校验和数据库并发协议不依赖这些旧插件。
