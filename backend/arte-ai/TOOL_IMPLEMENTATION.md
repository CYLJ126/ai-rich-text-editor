# AI TOOL 实施记录

日期：2026-10-17。需求基线见 [ARTE AI TOOL 需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)
。本记录按批次更新，只有明确完成的批次表示已有代码和验证证据。

## 1. S1-01：定义、风险、能力与默认策略

状态：完成值对象及契约测试，S1 阶段整体未完成。代码位于 `src/main/java/com/arte/ainew/pojo/tool`，复用现有公共引用、JSON
值树及通用能力契约，不依赖 Spring、SDK、运行身份或连接实例。

| 类型                | 已实现字段与职责                                                                                                                                                                |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ToolDefinition      | definition、providerId、title、description、tags、capability、inputSchema、outputSchema、configurationSchema、defaultConfiguration、capabilities、risk、defaultPolicy、checksum |
| ToolRiskProfile     | sideEffect、destructive、idempotency、requiredScopes、allowedNetworkOrigins；区分业务副作用、幂等依据、权限声明和网络外发                                                       |
| ToolCapabilities    | features、outcomeQuerySupported；区分工具协议能力与按原操作身份核对业务结果的能力                                                                                               |
| ToolExecutionPolicy | timeout、maxAttempts、retryBackoff、maxInputBytes、maxOutputBytes、approvalMode；表达默认上限，不负责有效策略合并或运行时控制                                                   |

`ToolInvocation`、`ToolResult` 及现有调用端口保持原契约。模型协议里的 `GenerationRequest.ToolDefinition` 仍为描述模型可提议的工具；本批新增
`pojo.tool.ToolDefinition` 表达平台工具产品契约，二者尚未接线。

### 1.1 固定身份与不可变性

- 工具引用 type=tool；能力引用 type=capability；三个 Schema 引用 type=schema，均复用 DefinitionRef 的固定版本规则。
- 工具 ID 采用点分段形式，每段以 ASCII 字母起始，后续允许字母、数字、下划线和连字符，至少两段，例如 text.countCodePoints。总长不超过
  256 字符；展示名和供应商映射名不作为权限身份。
- providerId 不超过 256 字符；标题不超过 200 字符；描述不超过 4096 字符；标签不超过 32 个，每个不超过 64 字符。
- 集合防御性复制；非敏感默认配置使用现有 StructuredValue.ObjectValue，深度及节点限制沿用公共契约。构造器不能识别配置内容是否包含秘密，后续配置管理仍须约束敏感字段。
- checksum 必须为 64 位小写 SHA-256 十六进制声明；本批未计算规范化摘要、加载 Schema 或校验默认配置。
- discoveredCapability () 单向投影同一份 Schema、features 和 sideEffect，只返回 DISCOVERED。发现描述不代表发布、授权或可执行。

### 1.2 风险与能力约束

| 风险声明                                   | 默认策略最低人工控制   |
|--------------------------------------------|------------------------|
| READ_ONLY 且非破坏性                       | NONE，可由策略收紧     |
| RESOURCE_WRITE／EXTERNAL_EFFECT 且非破坏性 | REQUESTER_CONFIRMATION |
| destructive=true 或 UNKNOWN                | INDEPENDENT_APPROVAL   |

READ_ONLY 与 destructive=true 相互矛盾，构造时拒绝。独立审批可满足本人确认的最低强度；反向不能替代。上述只是结构层下限：重要外部副作用等仍需发布策略识别；UNKNOWN
即使通过构造校验也不得自动开放执行。

幂等依据为 UNVERIFIED、NONE、DOMAIN_OPERATION_KEY、REMOTE_OPERATION_KEY；声明不证明领域或远端确有防重实现。必要 scopes
是声明，不是当前主体已授权事实。只读可以声明网络外发，不能据此推断无泄露风险。

允许目标仅保存 HTTP (S) origin，可带有效端口和根路径 `/`，不允许业务路径、用户凭据、查询或片段；集合最多 256 项，每项不超过
2048 字符。结构检查不解析 DNS，也不实施目标匹配、SSRF 防护或当前外发授权，后续发送边界必须核实实际地址与策略。

通用特性仅允许 STREAMING、ASYNC_TASK、TASK_QUERY、CANCEL、USAGE_REPORTING；模型输入、TOOL_CALLS、STRUCTURED_OUTPUT
不作为工具适配器能力。ASYNC_TASK 必须同时声明 TASK_QUERY，供远端任务恢复跟踪。outcomeQuerySupported 独立于
TASK_QUERY，不因支持远端任务查询就推断能核对已生效的业务结果；费用核对另行处理。

### 1.3 默认执行策略边界

| 字段           | 结构范围与含义                                                                |
|----------------|-------------------------------------------------------------------------------|
| timeout        | 1 秒至 1 小时；一次逻辑调用的总期限预算，包含排队和人工等待，不为每次尝试重置 |
| maxAttempts    | 1—10，包含首次；实际能否重试仍须检查未执行证据或可信幂等依据                  |
| retryBackoff   | 非负且不超过 timeout，表示允许重试时的最低退避                                |
| maxInputBytes  | 1—1,000,000 字节；编码后的输入上限                                            |
| maxOutputBytes | 1—32 MiB；编码后的输出上限                                                    |
| approvalMode   | NONE／REQUESTER_CONFIRMATION／INDEPENDENT_APPROVAL，不得弱于风险最低要求      |

这些范围是绝对结构上限，不是服务器默认配置。有效策略还须结合工具、绑定、任务、服务器限制和剩余 deadline
收紧；当前类未实现合并、时间调度、字节计数、审批决定或预算账户管理。

### 1.4 验证证据

新增 `src/test/java/com/arte/ainew/contract/ToolDefinitionContractsTest.java`，18
项测试覆盖引用及身份、嵌套不可变性、能力单向投影、风险与人工控制一致性、幂等声明与尝试上限的区别、远端任务与业务核对的区别、策略范围、origin
结构及值对象序列化往返。

在本地 JDK 21.0.8、Maven 3.9.16 下执行：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=com.arte.ainew.contract.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；DataContractsTest 13 项、InvocationApiContractsTest 6 项、ToolDefinitionContractsTest 18 项，共 37
项，失败／错误／跳过均为 0。模块生产代码和测试代码编译通过；这是契约测试回归，不是全量测试或工具执行验收。

序列化测试只证明 Java 值对象往返，不证明现有内部白名单 codec 或 HTTP JSON 已支持新增类型。构建仍有既有重复 H2
依赖、资源编码及旧代码弃用／unchecked 提示，本批未调整这些内容。

### 1.5 后续批次与未运行项

下一批 S1-02：实现需求 §5.3 的 Schema 子集校验及相应测试；本批仅保留固定引用。

本批未实现目录／发现／发布、Schema 内容校验、摘要真实性、类型化本地执行目标、绑定及策略合并、持久化编码、TOOL
受理与派发、审批、业务核对、预算、模型工具轮次或前端。没有新增 SQL、权限或菜单数据；没有启动应用、连接数据库、调用模型／工具，也没有执行全量、性能、多实例或浏览器验收。
