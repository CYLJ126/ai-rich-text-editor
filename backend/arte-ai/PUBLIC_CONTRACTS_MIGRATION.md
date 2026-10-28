# 第一批公共契约迁移

2026-10-28：将已经稳定的中立值对象及基础工具从 `ainew` 提取至 `arte-core`。 本批只调整代码归属，不改变 HTTP 字段、内部 JSON
类型别名、快照版本、幂等摘要、存储键或容量校验规则。

## 类型与职责

| 原类型                                              | 当前归属                                                    | 说明                                                                         |
|-----------------------------------------------------|-------------------------------------------------------------|------------------------------------------------------------------------------|
| `ainew.common.reference.DefinitionRef`              | `core.pojo.reference.DefinitionRef`                         | 固定版本定义引用                                                             |
| `ainew.common.reference.ArtifactRef`                | `core.pojo.reference.ArtifactRef`                           | 本系统产物引用                                                               |
| `ainew.common.reference.ResourceRef`                | `core.pojo.reference.ResourceVersionRef`                    | 资源内容的版本／草稿引用；与 `core.pojo.access.ResourceRef` 的授权定位键分开 |
| `ainew.common.value.StructuredValue`                | `core.pojo.value.StructuredValue`                           | 不可变、有界的 JSON 值树；容量常量属于值树自身                               |
| `ainew.common.validation.ContractChecks` 的基础方法 | `core.utils.validation.ContractChecks`                      | 通用结构校验；AI 原入口委托 core，保留所有原方法和常量                       |
| `ainew.serialization.CanonicalJson` 的通用算法      | `core.serialize.CanonicalJson`、`core.utils.ContentDigests` | v1 JSON 规范化、UTF-8 SHA-256、长度前缀组合键；AI 原入口委托 core            |

表中包名均以 `com.arte.` 为前缀。四个值类型采用新的 Java 包名；仓库内 AI／app 主代码和测试调用方同步更新。
不保留另一套同名旧值类型，以免两个类型在快照登记和公开端口中混用。

`SourceRef`、执行身份／上下文／事件、搜索及 AI 领域对象继续留在 AI。
`MAX_PARTS=64` 和 `MAX_TOOLS=128` 仍由 AI 兼容入口定义；结构化值保持 `MAX_ITEMS=256`、
`MAX_TEXT_CHARS=1_000_000`、`MAX_NODES=10_000`、`MAX_DEPTH=32`。

`PlatformChecks.id` 保持原有严格规则；公共 `ContractChecks.id` 保留旧 v1 的非空白和 256 个 UTF-16 单元限制，
不在迁移中增加首尾空白或控制字符限制。两种规则有意分开。

## 编码与升级边界

- core 值对象不依赖 AI、app、Spring、Reactor 或 Jackson；JSON 的类型包装由消费方编码器管理。
- `JacksonExecutionRecordCodec` 继续使用稳定别名及受信白名单，结构化值的内部包装和枚举空值表示保持原样。 HTTP 标准 JSON
  与内部快照格式继续分开；Java 包名不是合法的负载类型别名。
- `CanonicalJson` 是已有内部 v1 规范化算法，不宣称符合 RFC 8785。对象键排序、数组顺序、精确数字及其原有标准化方式保持不变。
- `ContentDigests.key` 继续按每段 UTF-8 字节的四字节大端长度前缀计算 SHA-256，避免改变已存储主键、归属键及幂等键。
- AI 的 `CanonicalJson.structured` 仍在原处解析证据 JSON，保留 32 KiB 输入字节和 24 层解析深度策略，不成为 core 的统一场景限制。
- 本批不需要新 DDL 或历史 JSON 回填。需重新构建使用这些 Java 类型的 core／AI／app 及其他 Java 调用方，不能混用新旧二进制依赖。
- 包名迁移不承诺旧 Java 原生序列化字节兼容。生产持久化使用已有版本化 JSON 编码；`Serializable` 只用于内部契约及可信测试往返。

## 验证

迁移前先运行 `DataContractsTest` 和 `ResolvedBindingCodecTest`，26 项通过。
`src/test/resources/public-contract-v1/` 中的快照及摘要向量由迁移前实现一次性生成并固定， 测试不会用新实现覆盖预期文件。

- core：`PublicContractsTest` 验证引用、不可变性、值树边界和原校验行为；`CanonicalJsonTest` 验证精确数字、规范化及哈希向量。
- core：`PublicContractBoundaryTest` 检查新公共包没有对 AI／app／运行框架的反向依赖。
- AI：`PublicContractMigrationTest` 读取旧 Schema／工具结果快照，覆盖所有结构化值类别、定义引用、资源版本／草稿及产物引用，
  重新编码后的规范化字节逐字相等；同时验证 AI 委托入口、旧摘要向量和证据解析边界。
- 相邻回归覆盖真实 MyBatis／临时 H2、受理／派发、工具 Schema／管理、聊天及平台配置装配。

本批结果（JDK 21，按用例去重）：

- `arte-core`／`arte-ai`／`arte-app` 执行 `clean test -DskipTests`，主代码及全部测试源码重新编译通过，排除旧 class 残留。
- 迁移契约与快照专项 7 类、46 项全部通过，其中本批新增 17 项。
- 扩展定向回归共 37 类、565 项：558 项通过、4 项既有失败、3 项跳过。 三项跳过均为 `InvocationHttpIntegrationTest` 的可选独立
  Redis 用例，不计为通过。
- 模型网关与派发的 56 项用例在沙箱内曾因禁止监听本机端口而失败；获准使用 loopback 模拟服务后全部通过。
- 以下四项在临时目录以迁移前 `HEAD` 源码、同一份本地构建配置复现，未因本批迁移新增，也未在本批修复：

| 既有失败                                                                                                   | 复现结果                                                                                    |
|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `PlatformPackageBoundaryTest.roleAndStateEnumsStayInEnumsPackagesAndPublicApisContainNoStubImplementation` | 原 `PublicTransactionRunner.readOnly` 的默认 `UnsupportedOperationException` 与边界断言冲突 |
| `SearchContractsTest.typedClientAcceptsDslAndPreservesCompleteSortPrecision`                               | 原 `ConversationSearchIndex.Read` 对含 null 的排序值执行 `List.copyOf`，抛出空指针异常      |
| `PlatformMcpToolIntegrationTest.mcpProfileDefaultsExampleAndFilteredRuntimeAreClosed`                      | 当前本地默认配置的 MCP endpoint 为占位地址，而示例为空                                      |
| `PlatformMcpToolIntegrationTest.publicMcpSubmissionUsesCurrentHostPermissionsAndFreeDispatch`              | 轮询后的模拟 MCP 请求数预期 5、实际 0；迁移前同样失败                                       |

基线复现为收集所有失败使用了 `-Dmaven.test.failure.ignore=true`；其 Maven 汇总的 SUCCESS 不代表用例通过。 本批不宣称全仓测试全绿。

本次本机日志：`/private/tmp/arte-core-migration-compile.log`、`arte-core-migration-contracts.log`、
`arte-core-migration-regression-rest.log`、`arte-core-migration-network-app.log` 和
`arte-core-migration-prechange-failures.log`， 这些文件均位于 `/private/tmp/`，不属于长期验收工件。

复现命令使用 JDK 21：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dtest=PublicContractsTest,PublicContractBoundaryTest,CanonicalJsonTest,PublicContractMigrationTest,DataContractsTest,ResolvedBindingCodecTest,TurnCandidateCodecTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

上述检查不替代部署、业务库升级、真实供应商、多节点或生产性能验收。
