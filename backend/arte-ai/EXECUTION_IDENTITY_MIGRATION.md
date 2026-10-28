# 第二批迁移：执行主体对齐与公共归属键

2026-10-28。本批只处理执行主体与 core 主体的语义对齐、三元归属键提取，不迁移授权上下文、执行事件、预算或搜索组件。

## 当前归属

| 类型／职责                               | 归属                                                                                      |
|------------------------------------------|-------------------------------------------------------------------------------------------|
| `Principal`                              | `com.arte.core.pojo.platform`，保存稳定主体 ID、类型及权威当前状态                        |
| `ExecutionPrincipal`                     | 保留在 AI，保存执行时稳定主体 ID、认证／展示名称和原类型编码，作为可持久化历史快照        |
| `ExecutionOwner`                         | 从 AI 迁至 `com.arte.core.pojo.platform`，字段仍为 `tenantId`／`workspaceId`／`subjectId` |
| `ExecutionOwners.from(ExecutionContext)` | 保留在 `com.arte.ainew.common.execution`，将 AI 上下文转为公共归属键                      |

`ExecutionOwner` 没有 AI／app／Spring／Reactor 依赖。它描述执行对象的归属，不代表资源治理归属、当前成员资格或访问许可。 标识继续使用旧
`ContractChecks.id` 规则，保留 256 个 UTF-16 单元上限，不做 trim 或改为更严格的 `PlatformChecks.id`，避免改变历史 ID。

## 主体、名称与状态

- `ExecutionPrincipal.from(Principal current, String currentSubjectName)` 接收可信边界刚读取的公共主体及其当前名称， 只允许
  ACTIVE 主体构造新快照；不从用户名生成主体 ID，也不在快照中持久化当前状态。
- `sameIdentityAs(Principal current)` 只比较稳定 ID 与主体类型。名称和当前状态独立核验；返回 true 不能证明当前允许执行。
  不提供从历史快照反向制造 ACTIVE `Principal` 的接口。
- `subjectName` 是执行当时的认证／展示名称，仍参与原固定配置解析与旧用户上下文桥接，不能仅视为展示标签。 RBAC
  适配继续核验当前账号名、映射、账户创建时间与状态，用户改名后须重新授权；同名新账号不能继承旧主体或历史执行归属。
- `ExecutionPrincipal.Kind` 保留为旧快照编码适配，通过 `platformKind()`／`Kind.from(PrincipalKindEnum)` 显式映射 core 类型。
  公共枚举已有数字 JSON 编码 0／1，AI 已有 USER／SERVICE 字符串编码，不能直接替换枚举而改变快照、配置或摘要。 本批没有给原本只支持用户的
  RBAC 路径新增服务主体权限。

接线包括：工作空间 AI 读／写准备及审批名单从当前公共主体创建快照；FIXED 授权、Worker 当前身份查找、工具历史读取及使用资格
复用同一身份比对。原当前状态、名称、空间、菜单权限及事务内重验保持独立。

## 持久化及调用方兼容

- AI／app 的 owner 字段和端口统一使用 core `ExecutionOwner`；原 `ExecutionOwner.from(context)` 改为 AI
  `ExecutionOwners.from(context)`。
- owner 的字段、JSON 形状、归属摘要、搜索归属键、工具调用幂等键及提交摘要保持不变；名称、账号状态、trace 和子执行 ID 不进入归属键。
- `ExecutionPrincipal` 仍只有 `subjectId`／`subjectName`／`kind` 三个快照字段。旧 USER／SERVICE 调用继续按原根别名及版本读取、写出。
- 不需要 DDL、历史 JSON 回填或重新计算归属键。Java 调用方须更新 owner 导入及转换调用后统一重编 core／AI／app，不混用旧二进制依赖。
- 与第一批相同，不承诺旧 Java 原生序列化字节的跨包名兼容；生产持久化仍使用现有版本化 JSON 编码。

## 验证与固定样本

迁移前先核验契约／上下文／快照兼容基线：29 项通过。基线发现第一批样本已被 JSON 排版器格式化，原测试按原始字符串比较、
替换稳定别名会受空白影响；本批将样本读取统一规范化后比较及变异测试，不修改历史样本数据，也不放宽别名断言。

`src/test/resources/identity-contract-v1/` 中的样本由第二批迁移前实现一次性生成并固定，测试不使用当前实现重建预期值：

- USER／SERVICE Invocation：完整执行上下文、身份、版本及根别名。
- Conversation：原 owner 三元组。
- FIXED Grant：原主体类型字符串及配置字段。
- vectors：原主体、上下文和归属规范化 JSON、归属摘要、搜索归属键、工具调用幂等键和提交摘要。

新增测试包括 core `ExecutionOwnerTest`、AI `ExecutionIdentityMigrationTest`，以及真实 JDBC／临时 H2 的 RBAC
改名重授权、同名新账号与停用状态测试。原主体快照结构、旧文本校验、精确编码与生命周期边界分别验证。

三个模块执行 `clean test -DskipTests`，清除旧 class 后主代码及全部测试源码重新编译通过。
定向集成回归覆盖身份、会话、受理、Worker、存储、审批、预算、工具聊天、工作流及查询；未连接业务数据库，未执行 MCP 用例。
这不是生产部署、多节点、真实供应商或全仓全量验收。

本批按用例去重： **57 类、809 项，806 项通过、零失败、零错误、3 项跳过**，其中新增 13 项。 三项跳过均为
`InvocationHttpIntegrationTest` 的可选独立 Redis 用例，不计为通过。 身份／契约／上下文专项 92 项全部通过；登录及会话 mock
在沙箱内遇到 JVM 附加限制，获准环境中的扩展回归已重跑通过。

身份与兼容专项复现（JDK 21、临时 H2）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dtest=ExecutionOwnerTest,PlatformPackageBoundaryTest,PlatformEnumSerializationTest,PublicContractBoundaryTest,PublicContractsTest,ExecutionIdentityMigrationTest,PublicContractMigrationTest,DataContractsTest,ExecutionContextTest,ExecutionContextFactoryTest,ReactiveExecutionContextTest,LegacyUserContextScopeTest,RbacIdentityProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本机日志：`/private/tmp/arte-identity-migration-baseline-fixed.log`、
`/private/tmp/arte-identity-migration-compile.log`、`/private/tmp/arte-identity-migration-contracts.log`、
`/private/tmp/arte-identity-migration-regression.log` 和 `/private/tmp/arte-identity-migration-results.json`。
这些临时日志不属于长期部署验收工件。
