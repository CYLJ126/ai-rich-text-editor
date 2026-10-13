# 只读授权与写门控重构（2026-10-13）

已实现只读资格快照、写门控内治理事实复用，以及新 AI 读接口的一次授权操作。

- `DatabaseAiControlService.readSnapshot` 在独立只读、REPEATABLE_READ 事务中调用公共 `batchCheck`；使用非锁定配置／预算
  Mapper。
- 会话、状态、结果、事件只加载资格和空间 AI 开通状态；预算、模型发现及对账读取按需加载同一份控制配置。
- `ExecutionContextFactory.readCurrent` 在 MVC 线程捕获认证名称，每次订阅重新取快照；通过私有 Reactor Context
  键供本次操作复用，不修改持久化执行上下文。
- 初始权限、scope、主体、上下文和总期限均校验；下一个请求能看到撤权，快照不能用于其他执行上下文或写操作。
- 写入／派发使用 `requireWrite` 重新授权。写门控的排序锁定读取和逐项资格检查在一个授权批次中复用事实，进入写回调前清除；之后的当前读看得到本事务自己的修改。
- 只读事务拒绝写门控和嵌套写事务。SSE 后续订阅、周期重新授权及 Worker 不复用建连时的展示快照。

查询次数验证（隔离 H2，真实公共身份／MyBatis／Service，配置发现不调用真实供应商）：

| 单次操作                       | 只读授权快照次数 | Prepared SQL 总数 | FOR UPDATE |
|--------------------------------|-----------------:|------------------:|-----------:|
| discoverChatOptions 对应读操作 |                1 |                18 |          0 |
| getBudget 对应读操作           |                1 |                19 |          0 |
| getConversation 对应读操作     |                1 |                16 |          0 |

次数包含 MyBatis 和身份端口的 prepared JDBC 查询，不包含驱动内部事务协议／提交。测试同时限制 SQL 数上限，验证发布
head、空间配置和预算定义各只取一次，普通会话不读取目录／预算定义，并检查重新订阅后撤权生效。

写门控回归：重复动作的主体、租户、空间、租户成员和空间成员各取得一次；写回调修改成员为 REVOKED
后再次当前授权被拒绝。现有跨租户锁顺序、事务回滚、旧快照下撤权、会话范围／版本、幂等、预算和 SSE 测试仍适用。

主要实现：[ReadOperationSnapshot](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/context/ReadOperationSnapshot.java)、[数据库来源](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java)、[写门控](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-core/src/main/java/com/arte/core/application/access/DefaultWorkspaceWriteGuard.java)。

数据库模式的读取计数／撤权验证：[DatabaseAiControlIntegrationTest](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/test/java/com/arte/app/platform/persistence/DatabaseAiControlIntegrationTest.java)。

运行中的后端需要重新构建并重启以加载此次代码；上述 SQL 计数不能替代部署后的浏览器耗时复测。

回归结果：358 项通过，3 项需要隔离 Redis 端点的跨实例通知测试跳过；无失败或错误。派发、生成、取消／重试、结算恢复测试使用本机模拟
HTTP 服务并已通过。
