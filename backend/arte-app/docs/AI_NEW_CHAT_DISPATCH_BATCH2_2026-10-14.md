# 首字优化第二批：合并派发短事务与发送前复核

## 结果

第二批在同库存储中增加原子派发准备，将领取核验、Attempt 创建、预算预留和耐久派发标记放到一个短事务中。模型 HTTP
请求仍在事务提交后执行。发送前继续重新检查当前资格，配置发布版本及空间配置版本一致时复用已验证内容，变化时回退到完整读取。

同一隔离 H2（MySQL 模式）、真实 JDBC/MyBatis/公共服务及模拟网关的对照结果：

| 指标                       | 第一批基线 | 第二批 |              变化 |
|----------------------------|-----------:|-------:|------------------:|
| 聊天提交 SQL               |         77 |     77 |              持平 |
| 派发开始到模拟首段正文 SQL |        129 |    105 | 减少 24 次，18.6% |
| 同一派发阶段 JDBC commit   |         15 |      7 |  减少 8 次，53.3% |

版本稳定的发送前复核单独测得 18 次 SQL，仍包含账户、身份、成员、策略、空间状态和当前发布指针核验，不再读取目录 revision
内容及主体预算定义。

计数排除了初始化、领取消息和 Worker 最初重建上下文；派发计数从 `GenerationDispatcher.dispatch`
开始，到模拟网关完成发送前复核、准备输出第一段正文为止。事务提交次数为相同 Recorder 捕获的 JDBC commit 调用，不能与 SQL
次数相加。本轮没有实际模型调用、没有重启运行服务，不能将这些数字宣称为真实 MySQL 或浏览器首字耗时。

第一批基线取自开始第二批时的工作区源码副本，只增加相同的 commit
计数器；没有回退共享工作区。详细结果与回归摘要见 [batch2-dispatch.json](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/docs/measurements/2026-10-14/batch2-dispatch.json)。

## 原子派发准备

新增 [DispatchPreparationStore](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/spi/persistence/DispatchPreparationStore.java)
，由同一个 `MybatisExecutionPersistence` 实现：

1. 按统一顺序锁定 Invocation、Outbox 领取、账户和预留记录。
2. 比较 Invocation 版本、领取 Worker/token/归属及数据库时钟下的领取租约。
3. 复用原有重试限制、取消事实、Attempt 序号和 fencing 分配规则，创建未发送 Attempt。
4. 锁定账户，检查归属、币种、费率、可用额度，写入预留和 held。
5. 事务内复用已创建/更新的 Attempt 和预留，减少重新加载；派发前再次检查领取、Attempt 租约、执行期限、取消和预留期限。
6. 保存 MAY_HAVE_EXECUTED 发送事实，提交成功后才进入模型网关。

预算不足只提交未发送、无预留的 Attempt，应用仍按原有流程保存 INSUFFICIENT_BUDGET 已知失败；不会增加 held，也不会发送
HTTP。其他拒绝或故障整体回滚创建、预留、计数器、事件和通知。

新命令不返回可重放的发送许可。同一命令重复或两个实例同时处理同一调用，只允许一次成功；重复调用按版本/状态/领取校验拒绝。提交后进程崩溃仍按已有可能发送状态收敛，不能盲目重发。

优化路径仅在执行、预算、Outbox 均为同一个存储实例时启用。自定义预算包装器、不同存储或不支持此接口的实现继续走独立命令，以保留其校验、故障注入和结算恢复行为。

事务内事件和快照可复用阶段时间戳，权限、租约、执行期限及预留期限的校验仍取得新的数据库时刻，尤其在可能等待的锁定读取之后重新核验。新增
`DISPATCH_PREPARATION_STARTED/COMMITTED` 耗时标记，原 ATTEMPT_CREATED/BUDGET_RESERVED/DISPATCH_MARKED
在合并提交后报告，不能把它们的时间差当作三个独立事务耗时。

## 发送前复核

- 准备结果增加可选 `catalogRevision`，仅在订阅 Context 内传递，不进入持久化执行 DTO，不跨请求缓存。
- 数据库来源先执行当前写门控，重新核验原主体及全部权限、账户状态、成员、策略、空间资格和 AI 开关。
- 当前发布版本与准备版本相同，且当前空间 grantRef 与准备版本相同时，复用不可变目录和该空间版本对应的预算定义。
- 发布版本或空间配置变化、来源未提供凭证时，完整重新加载配置；网关继续检查本次具体 binding、预算、连接和凭据。
- MySQL 发布指针使用 `FOR SHARE` 当前读，允许其他复核读取，同时阻止发布写入越过本次短事务。H2 使用兼容的 FOR
  UPDATE。共享锁及当前读语义参见 [MySQL 官方说明](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
  。MySQL SQL 方言通过 BoundSql 验证，实际执行验证使用 H2，并未测量真实 MySQL 的锁竞争。

## 验证

新增或扩展的验证覆盖：

- 同一调用重复及跨实例竞争，只创建一个 Attempt、一个预留和一个发送许可。
- 不同调用竞争同一预算，不得超额增加 held。
- 预算不足保留未发送状态，模拟 HTTP 请求数为零。
- 派发标记写入故障，Attempt、held、预留、计数器和事件全部回滚；故障移除后可正常准备。
- 已写入预留后，领取租约、Attempt 租约或预留期限过期，全部回滚。时钟推进由事务内真实预留记录驱动，不依赖辅助方法调用次数。
- 取消/陈旧领取拒绝创建 Attempt。
- 优化路径的本机模拟 HTTP 只调用一次、成功结算，再次轮询不重发。
- 配置不变时省略内容读取；空间版本或目录发布版本变化后完整复查；版本未变化也能拒绝成员撤权。
- MySQL/H2 当前发布指针 SQL 方言选择。

相关回归覆盖公共权限、治理锁、新 AI 受理/生成/派发/预算/租约/取消/HTTP/SSE、公共身份和持久化。沿用第一批的 JDK
21、workspace-baseline、显式 Mockito agent 和隔离模拟 HTTP 配置；默认 app 诊断测试编译问题及独立 ai 模块
ProfileConfigurationTest 装配问题仍按第一批报告的范围说明处理。

最终相关回归共 622 项：609 项通过、13 项跳过，失败和错误均为零。跳过项仍为需外部 Redis/MySQL 环境或显式基线开关的测试，逐项记录于测量
JSON。JDK 21 下 `mvn -Pworkspace-baseline -pl arte-app -am -DskipTests package` 成功，`git diff --check` 通过。

## 剩余工作

派发首段前仍有 105 次 SQL，其中包括当前权限链、并发许可、输出前的状态读取及多条事务内事件/锁表操作。提交路径仍为 77 次
SQL；快照字节写入与受理、会话/幂等/序号读取未在本批进一步合并。

Worker 一秒轮询及整批等待仍属于第三批；数据库 RTT、HTTP 冷热连接、SSE 建连和浏览器绘制需后续真实部署测量。一秒首字目标尚未验收，不能用
H2 时间或合并后的阶段日志代替端到端测量。
