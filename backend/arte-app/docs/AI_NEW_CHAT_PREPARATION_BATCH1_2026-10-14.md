# 聊天首字优化第一批：准备快照与身份读取（2026-10-14）

## 结果与测量口径

第一批代码和相关回归已完成。提交路径的完整控制面查询收敛为入口准备和受理前复核两份快照；派发路径收敛为 Worker
准备和实际发送前复核两份快照。身份映射与账户创建时间一起读取，已取得的账户事实用于本次身份解析，避免重复读取。

同一隔离 H2 数据库（MySQL 模式）、同一组真实 JDBC/MyBatis/公共服务及聊天 Controller 用例的对照：

| 路径                   | 修改前 SQL | 第一批 SQL |  降幅 |
|------------------------|-----------:|-----------:|------:|
| 聊天提交到受理回执     |        320 |         77 | 75.9% |
| 派发开始到模拟首段正文 |        288 |        129 | 55.2% |

计数是 JDBC prepareStatement 次数；包含身份直接 JDBC、公共权限服务和 AI 持久化查询，不包含测试初始化、MVC
认证过滤器、驱动内部事务协议。测试执行过程中不调用真实模型供应商。

提交对照运行原始业务代码时，仍使用相同 SQL Recorder 和测试环境；临时副本只替换本批改动的原业务源码，不回退共享工作区中的其他修改。派发对照中，修改前的最后一次连接查询使用原
resolve，修改后使用明确的 resolveCurrent，均模拟网关校验、连接获取与发送前边界。

此前真实请求日志中的 321 次 SQL 与本次隔离对照的 320 次不是完全相同的测量口径。本报告不将 77 宣称为部署后真实 MySQL
的实测结果，不把 H2 执行时间当成生产 TTFT。运行服务未重启，尚未重新测量浏览器点击到正文绘制的时间。

详细计数及回归摘要：[batch1-preparation.json](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/docs/measurements/2026-10-14/batch1-preparation.json)。

## 实现

### 提交准备

- 新增 `ExecutionPreparationAuthorizationResolver`，一次取得当前写入资格和完整控制配置；数据库实现继续使用现有当前事务门控。
- `ExecutionContextFactory.prepareCurrent` 在 MVC 调用时捕获认证名称，每次订阅重新读取准备结果，并安装仅属于本次订阅的
  `ControlPreparation`。
- `NewAiChatController`、聊天 Service、上下文组装、历史读取和目录校验复用入口准备，不再由各层分别读取整套权限和配置。
- `AdmissionInvocationCoordinator` 明确开启新的准备边界，重新验证原上下文的全部权限、主体和配置；INVOKE 与 CONVERSATION
  不再各跑一次完整门控。
- snapshot、Turn、Invocation 和 Outbox 原有持久化事务保持原实现，合并这些事务属于第二批。

### 派发准备与实际发送

- Worker 派发开始重新取得一份配置与资格结果，预检、网关和连接获取复用它；预算预留价格从同一份准备配置中选取。
- `ConnectionManager.resolveCurrent` 明确区分发送前复核与准备阶段 resolve。生产装配实现会强制重新读取当前资格和配置。
- `DefaultModelGateway` 通过订阅 Context 传递本次可信 GatewayCall，HTTP filter 在实际发送前检查调用上下文，并复核具体
  binding、预算配置、连接与凭据。
- 发送前拒绝 binding 内容变更，即使该连接仍能通过其他绑定访问；凭据继续按 SecretRef 重新解析。
- `requireWrite` 继续绕过准备快照。展示读取快照仍不能准备或授权写操作；快照不能用于其他 execution/context，不进入持久化
  DTO、不跨请求缓存。

### 身份读取

- `JdbcPrincipalIdentityStore.AccountBinding` 同行返回身份记录和 `external_created_at`，当前读仍使用 FOR UPDATE。
- `RbacIdentityProvider.resolve` 使用已经从名称或认证会话取得的账户完成本次绑定核验，避免再按 ID 读取账户和单独查询创建时间。
- 主体状态、账户创建时间、映射退休、已核对绑定、名称与账户主键规则继续执行。新的写边界仍重新读取当前身份，不能沿用此前的账户状态。

## 剩余查询与后续工作

提交路径 77 次 SQL 的主要分类：

| 表／工作                                  | 次数 | 仍存在的原因                                     |
|-------------------------------------------|-----:|--------------------------------------------------|
| RBAC 账户                                 |    8 | 两个准备边界的账户与当前主体状态核验             |
| 公共身份映射                              |   10 | 身份解析、主体门控和当前映射核验                 |
| 公共租户/成员/策略及空间/成员/策略        |   14 | 两个准备边界的治理事实与空间存在性核验           |
| AI 空间配置、预算定义、发布 head/revision |    8 | 两份独立当前配置，每份各一次                     |
| AI 锁表                                   |    8 | 现有持久化命令各自仲裁                           |
| 会话、Turn                                |   11 | 会话读取、历史检查、序号及受理事务校验           |
| 受理、调用、快照、账户、事件、Outbox      |   14 | 幂等查询、快照写入、受理和通知写入               |
| 表名提取器未分类的 SQL                    |    4 | 已计入总数；按 FROM/INTO/UPDATE 分类的模板未覆盖 |

剩余项：

1. 身份事实仍在公共写门控的不同核验阶段重复读取。进一步复用必须保留当前锁定读、退休和账户主键重用检查，不能简单扩大到任意写回调后的缓存。
2. 会话仍有两次应用层查询，以及序号、幂等和版本的多次存储校验。第二批可将准备结果与最终短事务协调，减少先读后重读。
3. 派发首段前仍有 129 次 SQL。准备配置已只读两份，但 Invocation/Attempt 版本读取、lease/fencing、额度获取、预算预留和
   dispatch 标记仍分散在现有事务中；第二批优先收敛这些命令。
4. 实际发送前目前是一次完整新配置复核。第二批可设计版本/资格的轻量复核，但必须继续验证本次具体 binding、预算和连接，不能只判断空间开通。
5. Worker 的一秒轮询和整批等待本批未改动，属于第三批。数据库 RTT、HTTP 冷热连接、SSE 建连与前端绘制仍需后续真实部署测量。

## 验证

相关回归共 609 项：596 项通过、13 项跳过，无失败或错误。

- core 公共权限、治理锁和工作空间回归：159 项通过。
- 新 AI 提交、生成、预算、派发、租约、取消、HTTP/SSE 等：297 项中 293 项通过，4 项需要隔离 Redis 的测试跳过。
- app 公共身份、持久化和工作空间：153 项中 144 项通过，9 项因缺少隔离 Redis/MySQL 或基准开关跳过。
- 新增准备快照的跨线程共享、每次订阅刷新、上下文隔离、只读快照拒绝写准备、requireWrite 独立复核、发送前撤权与 binding 变更拒绝测试。
- 新增真实数据库服务链路测试：入口准备后撤销成员资格，内部组装可复用旧准备，但受理前新资格核验拒绝提交；没有新增 Invocation
  或 Turn。
- 新增聊天提交和模拟派发的 SQL 上限断言，验证每条路径只有两次锁定 AI 空间配置读取。
- 现有身份回归覆盖退休映射、账户禁用、名称修改、主键重用和并发初始化。

测试使用 JDK 21、现有 `workspace-baseline` profile，补充该 profile 的公共身份测试源码范围；Mockito 显式以 agent 启动。本机模拟
HTTP 测试需要允许本机端口绑定，未访问真实供应商。

JDK 21 下 `mvn -Pworkspace-baseline -pl arte-app -am -DskipTests package`
已成功，生成可运行的 [arte-app-boot.jar](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/target/arte-app-boot.jar)。

相关回归命令（Mockito jar 路径使用本机 Maven 缓存中的实际路径）：

```sh
JAVA_HOME=<JDK21> mvn -Pworkspace-baseline -pl arte-app -am \
  '-Dtest=com.arte.ainew.**.*Test,!com.arte.ainew.admission.ProfileConfigurationTest,com.arte.core.application.access.*Test,com.arte.core.application.workspace.*Test,com.arte.app.platform.identity.*Test,com.arte.app.platform.persistence.*Test,com.arte.app.platform.workspace.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:<mockito-core-5.23.0.jar> test
```

测量及新增回归主要位于 [DatabaseAiControlIntegrationTest](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/test/java/com/arte/app/platform/persistence/DatabaseAiControlIntegrationTest.java)
和 [ControlPreparationTest](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/test/java/com/arte/ainew/admission/ControlPreparationTest.java)。

验证范围限制：默认 app 测试编译已有 `NewAiRequestObservationTest` 调用私有 mysqlId 方法的问题，未在本批修改；两项
ProfileConfigurationTest 装配错误来自当前 DATABASE 模式与独立 ai 测试缺少 app 授权 Bean 的组合，本批相关回归排除此配置测试类。尝试全量
core 测试还遇到 PdfUtilTest 原生进程退出，最终只运行与本批相关的公共权限和工作空间测试。上述限制不算本批通过的验证。

部署后仍需沿真实 MySQL、8080 代理与浏览器链路复测 `AI_HTTP_METRICS.sqlCount`
、受理时间、发送前准备耗时及点击到正文绘制时间；当前结果证明查询结构下降，不证明一秒首字已达成。
