# AI TOOL 实施记录

日期：2026-10-18。需求基线见 [ARTE AI TOOL 需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)
。本记录按批次更新，只有明确完成的批次表示已有代码和验证证据。

当前完成：S1-01—S1-08 本地管理后端基线；16 个管理 API 与现有当前授权接线完成，实际执行仍待 S2。TOOL
默认关闭；S1-04／S1-06／S1-07／S1-08 增量及 TOOL RBAC 定义尚未在配置中的业务数据库部署。

§1—§7 保留各批完成时的范围与验证记录；最新管理入口、部署及验证状态以 §8 为准。

S1 后续原始分批顺序：S1-04 工具定义／Schema／提供者存储；S1-05 本地登记与发现；S1-06 发布、废弃与停用；S1-07 绑定及修订；S1-08
管理 API 和现有授权接入。批次编号不因实现细节调整而改成另一项工作。

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

S1-01 完成时的下一批为 S1-02；该批实际结果见下节。S1-01 的值对象本身仅保留 Schema 固定引用。

本批未实现目录／发现／发布、Schema 内容校验、摘要真实性、类型化本地执行目标、绑定及策略合并、持久化编码、TOOL
受理与派发、审批、业务核对、预算、模型工具轮次或前端。没有新增 SQL、权限或菜单数据；没有启动应用、连接数据库、调用模型／工具，也没有执行全量、性能、多实例或浏览器验收。

## 2. S1-02：Schema 子集编译与值校验

状态：独立校验组件及针对性测试完成，尚未接入目录、发布、配置或实际 TOOL 调用。

生产代码：`src/main/java/com/arte/ainew/application/tool/schema/ToolSchemaValidator.java`；测试：
`src/test/java/com/arte/ainew/tool/schema/ToolSchemaValidatorTest.java`。不新增依赖或 Spring Bean，不改变现有生成校验和持久化编码。

### 2.1 使用与固定语义

```java
var validator = new ToolSchemaValidator();
var plan = validator.compile(schemaRef, schemaDocument, ToolSchemaValidator.Purpose.INPUT);
var result = plan.validate(arguments, effectiveMaxInputBytes);
// result.valid()；失败查看 violations() 中的 code、instancePointer、schemaPointer。
// 调用方应显式拒绝失败；本组件不派发工具、不发布能力、不授予权限。
```

- compile 接收 type=schema 的固定 DefinitionRef 和
  StructuredValue.ObjectValue；编译计划保留固定引用、不可变文档与用途，可跨线程复用，每次值校验使用独立预算和错误集合。
- 文档根必须声明 `$schema: urn:arte:ai-tool:schema:v1`。本地识别标识，不执行网络解析；不接受其他 dialect 或子 Schema
  重声明。平台采用受限子集及默认关闭对象规则，不宣称完整兼容标准 2020-12。
- INPUT 和 CONFIGURATION 根显式为 object；OUTPUT 可为 object、array、string、number、integer、boolean、null，或省略 type。type
  只允许单一字符串。省略 type 时，专属约束只匹配相应值类型；所有对象默认拒绝未声明属性。
- 支持 enum、const、title、description、default；properties、required、布尔 additionalProperties；单 Schema
  items、minItems、maxItems；minLength、maxLength；minimum、maximum、数值 exclusiveMinimum／exclusiveMaximum。title／description
  仅接受字符串；不支持布尔 Schema、联合 type、引用、组合、正则、format 或其他未声明关键字。
- 在 properties 值及 items 处编译子 Schema；不把 properties 中的业务字段名或 enum／const／default
  中的字面对象当作关键字。未知关键字、形状错误、枚举为空或存在语义重复、计数越界、上下界冲突均在编译时拒绝。
- 计数关键字必须是 0 至 Integer.MAX_VALUE 的数学整数；关闭对象的 required 名称必须已在 properties
  中声明，additionalProperties=true 时允许要求未声明字段。
- default 须符合所在 Schema，编译时验证；不会合并、补写或转换输入。ToolDefinition.defaultConfiguration
  仍需后续调用方用对应配置计划显式校验，本批未接入其发布流程。
- 数值复用有界 BigDecimal，不经过 double；1 与 1.0 在 enum／const 比较中相同，数学整数包含 1.0。精度及 scale 仍受
  StructuredValue 限制。字符串按 Unicode 码点计数，不做归一化；拒绝孤立 UTF-16 代理项。

数值、枚举及字符串长度语义参考 [JSON Schema 验证规范](https://json-schema.org/draft/2020-12/json-schema-validation)
；对象默认关闭、未知关键字拒绝、显式根用途及 default 必须有效是本项目的额外约束。

### 2.2 资源边界与错误契约

| 维度              | 默认值与规则                                                                 |
|-------------------|------------------------------------------------------------------------------|
| Schema 深度／节点 | 16／2048；包括元信息和所有字面数据                                           |
| Schema 字节       | 256 KiB 的紧凑 JSON UTF-8；配置绝对上限 1,000,000 字节                       |
| 实例深度／节点    | 24／8192；包括允许的额外字段                                                 |
| 实例字节          | validate 必须传入已收紧上限；不得超过 Limits.valueBytes，默认绝对上限 32 MiB |
| 错误数            | 默认 32，可配置 1—64；超过时 truncated=true，不能当作完整错误集合            |

可通过不可变 Limits 降低限额；深度和节点配置始终低于公共 StructuredValue 上限。先执行整值容量检查，失败直接返回；通过后才进入
Schema 匹配。预检累计 UTF-8、必要 JSON 转义、键及标点，不分配完整 JSON 副本。字节口径为紧凑
JSON，不包含原请求空白，也不表示任意传输／存储编码大小；后续边界必须限制实际原始字节和实际保存字节。

编译错误抛出 InvalidSchemaException，携带错误码及 Schema Pointer；值错误返回不可变 ValidationResult。错误包含稳定 Code、实例和
Schema 的 JSON Pointer（根为空字符串），对 `/`、`~` 正确转义，遍历顺序固定；不回显实际值、候选值或配置内容。LIMIT_EXCEEDED
表达容量不足，INVALID_UNICODE 表达非法代理项；TYPE、REQUIRED、ADDITIONAL_PROPERTY 等区分业务输入问题，供后续 HTTP／表单做安全翻译。

### 2.3 验证证据与追踪

代码版本：基于 `0eb34b929afcd3265b14d59613dc359a7adf5541` 的当前未提交工作区。环境：本地 JDK 21.0.8、Maven 3.9.16。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；Schema 新增 30 项、既有契约 37 项，共 67 项，失败／错误／跳过均为 0，模块生产代码和测试代码编译通过。覆盖七类类型、嵌套对象／数组、缺失与
null、关闭／开放对象、额外字段限额、unsupported 关键字、业务字段名、默认值、精确数值、枚举／const、码点、字节／深度／节点限额、安全错误及并发复用。字节边界用现有
JSON 库的编码结果作对照，不以字符数自证。

需求追踪：FR-07.3 对应编译拒绝及发布前置校验组件，FR-07.4 对应值校验组件；FR-07.1／AC-03 仍需后续 TOOL
受理、派发、结果及前端接线验证，不能因组件测试通过而标为端到端完成。

### 2.4 当前边界与下一批

Schema 已解析为 StructuredValue 后才进入本组件。原始 JSON 的严格有界解析、重复键／尾随内容／NaN／Infinity 拒绝须由后续传输适配实现；本批没有公开
JSON 接口。Schema 引用查找、checksum 计算与防漂移、缓存／发布管理、有效配置／策略合并及前端表单均未实现。

本批未新增 SQL、配置、权限、菜单、运行时接线或前端。没有启动应用、连接数据库、调用工具／模型；未运行全量测试、生产性能、多实例或浏览器验收。原有
Maven 依赖／资源编码及旧代码编译提示仍存在。

S1-02 完成时的下一批为 S1-03；实际结果见下节。

## 3. S1-03：本地／远端类型化执行目标

状态：目标契约、绑定兼容、调用结构检查及编码边界完成；TOOL 目录、适配注册和实际调用仍未开放。

### 3.1 模型与运行边界

| 类型／位置                               | 已完成内容                                                                                        |
|------------------------------------------|---------------------------------------------------------------------------------------------------|
| pojo.control.ExecutionTarget             | sealed Local／Remote 值对象，只持有受管固定引用                                                   |
| ExecutionTarget.Local                    | adapter 必须为 type=tool-adapter 的固定版本；不持有 Bean、类名、脚本、回调或连接                  |
| ExecutionTarget.Remote                   | connection 为固定连接引用，operation 为有界操作键；不携带地址或凭据                               |
| ResolvedBinding                          | 组件改为 definition、capability、target、contextWindowTokens、rate，只有 target 保存执行位置事实  |
| GatewayCall                              | 本地目标必须配 ToolInvocation；继续核对固定绑定／能力、可执行性、主体／scopes、期限及耐久派发身份 |
| FixedControlCatalog／DefaultModelGateway | 现有目录仍只接生成；明确拒绝本地目标，模型发现对本地 TOOL 返回不支持                              |

本地目标只适用于 TOOL，contextWindowTokens 必须为空；rate 可空，不伪造模型费率。远端生成仍必须声明有效窗口，rate
的现有格式校验不变。声明本地适配引用不等于已注册实现，不得按引用字符串反射加载或选择任意
Bean；真实注册、固定工具映射、当前授权和发布校验留给后续目录批次。

保留原六参数 ResolvedBinding 构造器，将 connection／remoteOperation 单向转为 Remote；两个旧访问器从目标投影。对本地目标调用这些访问器明确抛出
IllegalStateException，不返回 null 或占位连接。既有生成调用方式可继续使用，新增代码使用五参数类型化构造器。

为保持现有 Spring 固定配置，兼容六参数构造器使用 ConstructorBinding 元数据；arte.ai-new.bindings 仍严格接受原六字段，新增
target 配置字段会被拒绝。没有关闭未知属性校验，也没有新增配置项或装配 TOOL Bean。未来 TOOL 配置须在自己的控制面中显式解析目标。

本地调用仍须提供 Attempt 已持久化的派发身份；当前字段名 remoteRequestId
沿用，代表该次操作身份，不用本地目标绕过该检查。免费派发、预算资格、审批、结果核对及业务幂等未在本批实现。

### 3.2 编码与旧数据兼容

新增 `serialization.ResolvedBindingJson` 显式编解码模块，使用封闭 LOCAL／REMOTE 映射，不启用按 Java 类名的默认多态。分别注册到执行快照
mapper 和平台 PlatformJson，不修改应用全局 mapper。

| 入口／格式                 | 读取                                                 | 写入                                           |
|----------------------------|------------------------------------------------------|------------------------------------------------|
| 旧平面绑定 body            | connection／remoteOperation 映射为 Remote            | 平台固定生成目录及管理响应继续保留原六字段     |
| resolved-binding 根别名 v1 | 仅兼容旧平面远端 body                                | 新内部写入不再选 v1                            |
| resolved-binding 根别名 v2 | body.target.kind=LOCAL／REMOTE，按各自字段白名单读取 | 新内部绑定快照统一写 v2                        |
| 其他内部根记录             | 保持已有 v1 规则                                     | Invocation、Attempt、结果、事件、账本等仍写 v1 |

v2 示例（省略 capability 等共同字段）：

```json
{"schemaVersion":2,"type":"resolved-binding","body":{"target":{"kind":"LOCAL","adapter":{"type":"tool-adapter","id":"text-counter","version":"v1"}}}}
```

远端 target 为
`{ "kind": "REMOTE", "connection": { "type": "connection", "id": "model", "version": "v1" }, "operation": "test-model" }`
。平台生成目录兼容模式拒绝写 Local，不能悄悄生成伪远端数据。未知 kind／版本、非整数或溢出版本、新旧字段混合、版本与形状不一致、错误引用／类型和越界窗口均拒绝。

此前 JacksonExecutionRecordCodec 未登记 ResolvedBinding 根别名；v1 读取分支表示对旧字段形状的显式兼容能力，不表示历史
Invocation 已经持久化这种根记录。数据库模型目录原本由 PlatformJson 保存旧平面形状，已用真实平台 mapper
验证往返；本批不读写实际目录数据库、不批量重写记录。未来首次启用新根记录消费前，须先升级所有读端；旧程序不认识新别名，不能宣称可与旧节点混用。

### 3.3 验证证据

代码版本：基于 `4d420f0d1cc8b3008867f7c796817f8642dcd4a2` 的当前未提交工作区。环境：本地 JDK 21.0.8、Maven 3.9.16。

新增 25 项：ExecutionTargetContractsTest 8 项、ResolvedBindingCodecTest 13 项、PlatformBindingJsonTest 3
项、ConfigurationAssemblyTest 新增严格拒绝 target 配置 1 项。既有 GenerationAssemblyTest 补充真实装配的模型网关拒绝本地
TOOL 的断言。

执行以下有针对性的兼容回归（仓库根目录）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.persistence.ResolvedBindingCodecTest,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.ainew.persistence.MybatisExecutionPersistenceTest,com.arte.app.platform.persistence.PlatformBindingJsonTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；arte-ai 228 项、arte-app 3 项，共 231 项，失败／错误／跳过均为 0。Reactor
编译通过；覆盖旧构造器、严格固定配置、嵌套目录、版本化目标往返、类型／字段防混用、空或非对象 body 拒绝、模型发现、真实本机
HTTP／SSE 交互、生成派发及预算／H2 存储回归。初次沙箱执行的本机端口绑定报 Socket Operation not
permitted；获得本机绑定能力后重跑上述最终命令通过。测试 H2 不是生产 MySQL 语义证明，受控 HTTP 不是实际供应商验收。

另行运行 ProfileConfigurationTest：4 项中 1 项通过、1 项失败、2 项错误。两个装配用例缺少
ExecutionAuthorizationResolver；一个资源用例期望根 spring.config.import 但当前未找到。已在临时副本中还原本批修改前的
ResolvedBinding、固定目录、网关、GatewayCall、codec 及配置测试，移除新增目标／编解码模块后独立复跑，复现完全相同的 3 处问题。因此未将
profile 套件标为通过，也未为本批改写既有授权／profile 行为。

需求追踪：FR-04.3／FR-20.3 的契约及兼容验证完成；AC-04 的完整绑定管理、AC-28 的部署切换及 AC-30 的产品回归门槛仍随实际功能开放继续验收。构建既有
H2 重复依赖、资源编码、旧代码弃用和本地 DNS 库回退提示未在本批调整。

### 3.4 当前边界

没有新增部署 SQL、配置项、菜单、权限数据、前端或业务工具实现。没有实现 TOOL 目录、类型路由、真实适配注册、Schema
发布接线、绑定策略／配置合并、工具受理／派发、免费／付费工具预算或模型工具循环。新绑定根编解码器已具备，但尚无 TOOL
业务流程将其持久化并恢复执行。

没有启动完整应用、连接配置中的业务数据库／Redis、执行部署迁移、调用真实模型／远端工具或进行浏览器验收；未运行全量、生产性能和多节点部署验证。用户已有其他工作区改动保持原样。本次停在
S1-03，S1 整体未完成。

## 4. S1-04：工具定义、Schema、提供者存储

状态：必要 SPI、MyBatis 持久化、基线／增量 DDL 及版本／事务验证完成；实际业务数据库尚未部署增量。没有实现 S1-05 的本地登记发现或其他后续功能。

### 4.1 类型、职责及接线

| 类型／位置                                  | 已完成职责                                                                        |
|---------------------------------------------|-----------------------------------------------------------------------------------|
| pojo.tool.ToolProviderDefinition            | 固定 tool-provider 引用、LOCAL／HTTP／MCP、提供者适配固定引用及可选连接           |
| pojo.tool.ToolSchemaDefinition              | 固定 schema 引用、不可变 JSON 原文、真实 checksum                                 |
| pojo.tool.ToolDefinitionRevision            | 冻结 ToolDefinition、固定提供者引用及本地／远端执行目标                           |
| spi.persistence.ToolCatalogStore            | 三类精确版本读取、有限游标列表、跨表原子批量登记                                  |
| persistence.mybatis.MybatisToolCatalogStore | 独立工厂、固定 SQL、事务锁、完整性核对及数据库失败传播                            |
| serialization.ToolCatalogDigests            | Schema 标准 JSON 摘要、工具产品契约摘要 v1                                        |
| NewAiAdmissionConfiguration                 | newAiToolCatalogStore 复用既有物理数据源、内部 codec、有界数据库调度器及 UTC 时钟 |

目录定义沿用专项 §3.2 的平台受管范围，底层存储不另建租户／工作空间认证授权体系，也不提供公开
API。当前空间可见性、工具使用资格、资源权限仍须由后续管理／调用边界通过既有授权实现；直接获得 SPI 对象不是产品使用许可。

提供者适配引用 type=tool-provider-adapter，代表受管的定义来源／协议接入契约，区别于单工具执行目标 Local.adapter 的
tool-adapter。LOCAL 提供者没有 connection，HTTP／MCP
使用固定连接引用；本批不解析连接、凭据或实际适配实现。提供者当前启停、活跃版本选择、同步结果及草稿／发布状态不是不可变历史版本的职责，后续批次另行完成。

### 4.2 存储契约与完整性

`put(Batch)` 是冷 Mono：订阅后在有界数据库线程进行基础校验／编码，再在一个 Spring 事务中锁定版本及依赖、检查冲突与关联并写入三张表。只保存不可变固定引用，不做覆盖
UPDATE、原地编辑或 latest 选择。

- 同引用、同内容返回 REPLAYED；存在新事实返回 APPLIED。判断重放使用解码后的值，避免 Set 编码顺序导致跨进程误冲突；重放保留原创建时间。
- 同引用异内容返回 IDEMPOTENCY_CONFLICT；缺少固定依赖返回 NOT_FOUND；提供者与目标类别／连接不匹配返回
  INVALID_STATE，均不提交部分业务记录。
- 输入结构／checksum 错误及非法 Schema 通过 onError 拒绝。数据库、编码、约束或事务失败同样传播
  onError，整批回滚，不捕获为伪成功或伪冲突；未增加透明重试。
- 提供者、Schema 及工具引用用类型／ID／版本的长度前缀 SHA-256 定位，稳定
  ID／版本另有唯一投影；读取后还要核对原始引用、快照摘要和依赖投影。版本标签区分大小写，不按字符串或创建时刻判断新旧。
- 复用 arte_ai_lock，所有批次按确定顺序锁定目标和依赖。目录锁使用 INSERT … ON DUPLICATE KEY UPDATE 的排他锁路径，再执行 FOR
  UPDATE 核实；不以 INSERT IGNORE 的共享锁升级作为目录并发保证。该模式仍需生产 MySQL 下验证，测试不构成“绝不会死锁”的承诺。
- 工具对固定提供者及三个 Schema 有数据库外键。写入时验证 input／configuration 为对象根，output 符合支持子集，默认配置符合固定配置
  Schema；本地／远端目标与提供者类型一致，远端连接精确一致。
- 时间按公共 PlatformTimes 策略截断到微秒，存为 UTC Unix 微秒；仅记录首次登记时刻，不把时间当作发布版本或全局提交顺序。

Schema checksum 覆盖标准 JSON 文档；工具 checksum 覆盖产品契约并排除 checksum 自身，集合排序、JSON
数组保序、十进制保持精确。固定提供者版本和执行目标由 ToolDefinitionRevision
的不可变版本及内部字节完整性保护。它们都不替代后续包含配置、当前使用范围和实际执行选择的计划／发布摘要。

新增根别名 tool-provider、tool-schema、tool-definition-revision 均为 schemaVersion=1，目标使用显式
target-local／target-remote 白名单；原 resolved-binding v2 的 kind 格式和所有既有执行／结果编码保持原义。自定义
ExecutionRecordCodec 须同步这些新别名并验证往返，不支持时明确失败；不能开启任意 Java 类名多态或原生反序列化。

容量：每批最多 32 个提供者、128 个 Schema、128 个工具；单条内部快照 UTF-8 上限 1,000,000 字节，整批上限 8 MiB。Schema 还须满足
S1-02 更低的运行限额；元数据分页最多 100 条，按索引主键游标返回 nextCursor。该分页不是已授权空间目录，也不提供跨页的完整同步快照或语义版本排序。

### 4.3 DDL 与环境交付

新增三张表：arte_ai_tool_provider、arte_ai_tool_schema、arte_ai_tool_definition。主键、精确版本唯一性、定义依赖外键、提供者查询索引、快照摘要、内部数据列和微秒创建时间均有
DDL 及中文注释。

- 新环境：使用更新后的 [arte-ai-new-ddl-mysql.sql](scripts/arte-ai-new-ddl-mysql.sql)，已包含工具表。
- 已有基线环境：执行 [arte-ai-tool-catalog-migration.sql](scripts/arte-ai-tool-catalog-migration.sql)，仅新增工具表并复用原
  arte_ai_lock。
- 执行前停写、备份并核对三张工具表不存在；出现同名表须先核对实际结构，不能重复运行完整基线，也不通过 IF NOT EXISTS 掩盖漂移。
- 脚本只作为部署文件和测试资源，不进入运行时 JAR；Bean 构造不访问数据库、不执行
  DDL、不登记或发布工具。未部署时旧生成路径不因构造该存储而主动访问新表，工具存储实际调用会明确报缺表错误。

本批只在临时 H2 MySQL 模式执行基线、移除测试工具表后再执行增量，验证已有基线场景；测试还核对两个脚本的工具建表段完全一致。配置中的业务数据库／MySQL
未执行本批 DDL，也未检查或改动其中的数据；其他环境仍需按上面步骤独立部署。

### 4.4 验证证据与未完成项

代码版本：基于 `d9e7cc2851f914a74118bcc7688a2bed4335f399` 的当前未提交工作区。本地 JDK 21.0.8、Maven 3.9.16。

新增 ToolCatalogStoreTest 29 项：两独立适配器读取、同版本重放／漂移、精确大小写版本、目标／提供者冻结、跨表缺依赖、SQL
中途故障回滚、两实例及多订阅者并发、反向输入顺序、数据库唯一性／外键、伪造
checksum、Schema／默认值、远端声明、快照／索引损坏、分页、错误传播、UTF-8／批次上限、白名单编码及基线／增量一致性。既有装配测试补充默认关闭和新存储构造零
SQL 验证；工厂显式 Mapper 数量由 9 更新为 10。

最终兼容回归命令（仓库根目录）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.app.platform.persistence.PlatformBindingJsonTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；arte-ai 262 项、arte-app 3 项，共 265 项，失败／错误／跳过均为 0，Reactor 编译通过，新增存储测试 29 项全部通过。受控
HTTP 测试使用本机临时端口，需允许本机绑定；H2 中的故障注入产生预期 SQL 错误日志，不输出输入／配置参数。该结果不是全量测试或
MySQL／生产性能验收。既有 ProfileConfigurationTest 的三处基线问题见 S1-03 记录，本批未重跑该套件，不列为已通过。

FR-01.3 对应本批固定版本原子登记；FR-07.3／07.4 的组件已用于存储校验，但发布、实际参数／结果验证和前端验收仍未完成。当前 put
只保证目录事实的事务；管理命令回执、当前授权及审计一致提交仍是 S1-08 接线门槛，不能把后续独立 Store 调用简单串联就宣称已满足专项
§13.3。

没有实现本地来源注册、自动发现、同步快照激活、草稿／发布／停用、绑定及修订、管理
API、工具执行或前端；未新增权限初始化数据、其他运行开关或真实业务工具。没有连接真实供应商或配置中的业务数据库，也未运行生产
MySQL 隔离／锁竞争、多节点、压测、浏览器或完整应用验收。

S1-04 完成时的下一批为 S1-05；实际结果见下节。代码和脚本完成不代表工具已开放或数据库已部署。

## 5. S1-05：本地工具显式登记与完整发现

状态：本地来源、显式注册、手动完整发现及针对性验证完成。没有新增真实业务工具 Bean，不扫描任意 Bean；发现结果仍为
DISCOVERED，不表达发布、当前授权或可执行性。

### 5.1 组件及使用

| 位置／类型                                         | 职责                                                                                       |
|----------------------------------------------------|--------------------------------------------------------------------------------------------|
| spi.adapter.LocalToolDefinitionSource              | 一次返回完整 Definitions（Schema／工具集合）；空 Mono 是异常，不是空目录                   |
| application.tool.discovery.LocalToolRegistry       | 组合模块显式指定 LOCAL 提供方、来源实例、固定 tool-adapter 引用及 BusinessToolAdapter 实例 |
| application.tool.discovery.LocalToolDiscovery      | 顺序读取来源，校验完整候选，调用一次 ToolCatalogStore.put，成功后原子替换节点 State        |
| LocalToolDiscovery.Snapshot                        | 不可变提供方／Schema／工具索引、内容清单摘要及 UTC 微秒装载时间，不保存发布状态            |
| config.NewAiToolConfiguration／NewAiToolProperties | 独立默认关闭开关、严格属性绑定、有界元数据线程池；不启动同步或执行                         |

组合模块通过构造器注入真实领域 Query／Command 给 BusinessToolAdapter，然后显式提供一个 LocalToolRegistry
Bean。以下为组合代码示意，变量表示组合模块已构造的对象，不是新增的生产示例类：

```java
var registration = new LocalToolRegistry.Registration(
        providerDefinition, // LOCAL；type=tool-provider，adapter 为 type=tool-provider-adapter
        definitionSource,   // LocalToolDefinitionSource；完整 Schema 及工具版本
        Map.of(adapterRef, businessToolAdapter)); // adapterRef：type=tool-adapter，固定版本
var registry = new LocalToolRegistry(List.of(registration));
// LocalToolDiscovery 仅由内部管理流程显式调用；refresh() 返回 Mono，订阅才开始。
var command = discovery.refresh(); // Mono<RefreshResult>
// 订阅后从 RefreshResult 读取 code()、state().snapshot()，不是工具调用结果。
```

提供方、定义来源和执行器实例在受信代码中绑定；不按配置中的类名、Bean 名称或任意字符串反射加载。一个节点同一提供方只登记一个接入版本，tool-adapter
固定引用不得跨来源重复登记；注册表、Definitions、Snapshot 的集合均防御性复制。构造和未订阅 refresh 不读取来源、数据库或外部服务。

### 5.2 完整性、失败及并发

1. 每个来源显式返回完整 Definitions；逐来源合并时限制总量，超过 128 个不同 Schema 或 128 个工具立即终止，不再读取后续来源。注册表最多
   32 个提供方。
2. 工具必须属于登记的固定提供方及 LOCAL 目标；执行器必须在该来源内显式登记，其固定 capability、kind、输入／输出
   Schema、features、sideEffect 须与产品定义的单向投影一致。
3. DISABLED、缺失或声明不匹配的执行器拒绝装载；EXECUTABLE 声明只作为匹配元数据，目录仍投影 DISCOVERED，不调用
   invoke、不推断额外权限。
4. 候选必须自带全部 Schema；相同引用相同原文可以跨来源共享，矛盾原文拒绝整轮。重算 Schema／工具
   checksum，按真实用途编译输入、输出、配置，并校验默认配置。同一 capability 引用不得声明矛盾内容。
5. 当前视图的同引用内容漂移在持久化前拒绝；重启后或已移除版本的历史漂移，由 S1-04 底层不可变存储再次拒绝。任一来源失败，不先登记已成功的来源。
6. 只有一次原子 Batch 返回 APPLIED／REPLAYED 后才安装整份节点快照；数据库错误或拒绝不替换旧快照。原有单条 1,000,000 字节、整批
   8 MiB 及 Schema 限额继续生效。

State 一次读取 snapshot 和 lastResult；首次成功前
snapshot=null。SOURCE_FAILED、SOURCE_EMPTY、SOURCE_TIMEOUT、INVALID_DEFINITION、ADAPTER_MISMATCH、CONTRACT_DRIFT、STORE_REJECTED、STORE_FAILED
保留最近完整快照并令 discoveryHealthy=false。刷新成功才恢复健康。错误只返回稳定码，日志不附带原始 Throwable 或默认配置。

完整空 Definitions 会移除该来源在本次视图中的工具，但不会删除历史定义或停用已发布工具。该行为只表达“当前节点发现到了什么”，不能绕过后续停用流程。

每个节点最多一个刷新在进行；新并发请求返回 BUSY，不更改全局状态。一个 refresh 返回值通过 cache
共享一次命令，多订阅不重复读取或写入；首次订阅后，取消某个订阅不会取消正在进行的刷新或提前释放保护，避免晚到旧刷新覆盖较新目录。
每个来源读取有独立超时；同步读取被调度到专用有界线程，不占用 HTTP／Reactor 事件线程。该超时不证明任意不合作的 Java
代码可被强制停止；来源及 capability () 必须只读取受信、有界元数据。 APPLIED／REPLAYED
表示底层固定版本登记结果，不表示节点视图有没有变化；例如完整空来源可只重放提供方历史记录，同时安装更小的当前视图。判断视图内容使用
snapshot.checksum。

清单摘要为 local-tool-discovery-v1，按 type／id／version 的精确字符串排序，覆盖提供方接入版本、Schema
摘要、工具摘要、固定提供方和执行目标；不含发现时间或来源顺序。 同内容节点可比较该摘要，但本批没有自动进行节点比较，也没有把
discoveryHealthy 接入 Worker 就绪、领取或公开健康接口。

### 5.3 配置与部署边界

已同时落实配置类、[YAML 示例](examples/ainew-tool.yml)、`src/main/resources/ainew/config/application.properties` 过滤模板、
`../profile/common.properties` 默认值和 `../profile/app.properties.example`。

| 配置                                      | 默认及范围                                                               |
|-------------------------------------------|--------------------------------------------------------------------------|
| arte.ai-new-tool.enabled                  | false；AI 主开关也必须为 true；开启时必须提供显式 LocalToolRegistry Bean |
| arte.ai-new-tool.discovery.source-timeout | 10s；1ms—1m；每个完整来源的读取期限，不是工具调用期限                    |

专用线程池为 2 线程、每线程队列 16，应用关闭释放。启动不访问数据库、不发现、不发布或执行，也不自动同步。 当前只接受这两个已实现的
TOOL 参数；auto-sync、执行、审批、聊天及远端配置仍是未来设计，填写时会被严格属性绑定拒绝。

本批没有新增 SQL 或内部持久化根别名；复用 S1-04 三表及原子登记。配置中的业务数据库未执行 S1-04 增量；实际启用及刷新前仍须按
§4.3 停写、备份、核对并部署增量。现有生成及平台目录未改变，也未修改现有认证、授权、空间或 Worker 规则。

### 5.4 验证证据

代码基于 `c0624bdf5b87f581f8c410fe954732027fdf6d7d` 的当前工作区；本地 JDK 21.0.8、Maven 3.9.16。新增测试位于
`src/test/java/com/arte/ainew/tool/discovery`，覆盖显式登记、完整／空来源、共享
Schema、摘要／用途／默认值、实现匹配、漂移、并发、多订阅、取消等待、容量、默认关闭和配置模板。 其中真实持久化集成使用临时 H2 MySQL
模式，验证两个独立节点清单、重启后漂移整批拒绝、SQL 故障保留旧视图和显式恢复；不连接配置中的业务数据库。

最终回归命令（仓库根目录）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.app.platform.persistence.PlatformBindingJsonTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；arte-ai 301 项、arte-app 3 项，共 304 项，失败／错误／跳过均为 0，Reactor 编译通过。本批新增 39 项：
LocalToolDiscoveryTest 30、LocalToolDiscoveryAssemblyTest 6、LocalToolDiscoveryPersistenceTest 3；既有
ConfigurationAssemblyTest 补充 TOOL 默认关闭的断言。

第一次在受限沙箱中运行时，55 项既有受控 HTTP 测试因 Socket Operation not permitted 失败；允许本机临时端口绑定后按同一命令完整重跑通过。
SQL 故障注入产生预期错误日志；既有 macOS Netty DNS native fallback、重复 H2 依赖、资源编码及旧 API 提示仍存在，本批未处理。
这不是全量或 MySQL／生产性能验收；既有 ProfileConfigurationTest 的三处基线问题见 S1-03，本批未重跑整套该类，不列为通过。本批新增的专用
profile 测试只核对 TOOL 两个参数从默认值、示例到实际过滤资源的传递。

### 5.5 需求追踪及下一批

FR-01.4 对应本地显式登记与完整节点视图；FR-01.2／AC-01 的本地失败保留路径已有证据。FR-01.1 的正式草稿生命周期、FR-03.1
的发布校验及 AC-28 的集群就绪／滚动发布仍须后续接线，不能因发现测试通过而标为产品级全链路验收。

State／Snapshot 是节点进程内发现结果，不是新增活动目录数据库权威；重启后须重新显式发现。没有实现远端来源、完整同步快照的数据库激活、Redis
通知／周期对账、多节点执行就绪、自动发布、绑定、管理授权／审计事务、实际工具执行或前端。 S1-08
对外接线前，发现导致的目录变更仍须纳入管理命令回执、当前授权与审计事务，不能把底层服务直接暴露为公开接口。

S1-05 完成时的下一批为 S1-06，实际结果见下节。未运行全量测试、实际 MySQL、生产性能／负载、多节点部署或浏览器验收；没有新增生产业务工具、启用
TOOL 或执行部署 DDL。

## 6. S1-06：发布、废弃与停用

状态：内部生命周期治理、发布预检、状态／管理防重／审计原子事务及针对性验证完成。当前只有本地单次最终结果协议可发布；TOOL
仍默认关闭，未新增管理 HTTP、生产工具、绑定或实际派发。

### 6.1 契约与生命周期

| 类型／位置                                                                    | 本批职责                                                                                         |
|-------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------|
| pojo.tool.ToolRelease                                                         | 固定工具的当前治理状态、行版本、首次发布材料、停用前状态及废弃截止                               |
| pojo.tool.ToolReleaseCommand                                                  | 带 expectedVersion、幂等键和原因的管理语义；审核示例、风险／幂等依据、最低人工控制及可选兼容前驱 |
| pojo.tool.ToolPublicationValidation                                           | 服务端检查结果、固定契约摘要、节点清单摘要和稳定问题码；不携带异常正文                           |
| pojo.tool.ToolReleaseReceipt                                                  | 实际操作者、原始命令、领域决定、拒绝原因、当时状态及预检结果；同时作为不可变管理审计             |
| application.tool.release.LocalToolPublicationValidator                        | 当前发现与实现、Schema／示例、风险／幂等／人工控制及已支持协议预检                               |
| application.tool.release.ToolReleaseService                                   | 受信内部编排；发布／重新启用先预检，停用／废弃不依赖来源健康                                     |
| spi.persistence.ToolReleaseStore／persistence.mybatis.MybatisToolReleaseStore | 当前状态、原始审计分页与管理事务，不缓存撤销状态，不替代当前授权                                 |

状态规则：

- 有固定定义但无治理行：派生 DRAFT、version=0、publication=null，读取不写数据库；不存在固定定义则返回空 Mono。
- PUBLISH：仅 DRAFT→PUBLISHED，首次管理成功写 version=1、不可变首次发布材料与契约。
- DEPRECATE：仅 PUBLISHED→DEPRECATED；不推荐新建绑定，既有绑定可在截止前使用。
- DISABLE：仅 PUBLISHED／DEPRECATED→DISABLED，保存原状态及废弃截止；不需要健康来源或存在执行器，便于故障时撤销。
- REENABLE：仅 DISABLED→停用前状态，重新预检原审核材料及原固定契约；不把废弃状态改成推荐状态，不延长截止，不覆盖首次发布者／时间／兼容边。

成功变更 version 加一，拒绝及重放不推进。其他转换明确拒绝。废弃截止 existingBindingsUntil
可为空，表示未额外设定治理截止；到达明确截止时即失去既有绑定生命周期资格，无需等待后台修改状态。
已过截止不能重新启用。permitsNewBinding／permitsExistingBinding 只判断生命周期资格，不证明当前空间、权限、执行器、免费资格或
Worker 就绪。

已登记的固定引用连草稿也不能覆写；未登记的编辑工作副本可以修改，修改固定契约须新版本。本批未实现草稿编辑器。

### 6.2 发布前置检查及不可变材料

发布／重新启用须满足：最近完整发现健康且包含该固定工具；实际已登记 BusinessToolAdapter 当前非 DISABLED，且
capability／Schema／features／sideEffect 与产品定义一致。 检查使用有界元数据线程，期限沿用 discovery.source-timeout；不调用
invoke、不执行试用、不访问真实领域资源或远端服务。

重新编译输入、输出和配置 Schema，校验默认配置、真实 checksum 及 1—8 个非敏感正向输入输出示例。单个示例输入／输出最多 32 KiB
紧凑 JSON UTF-8，且不得超过工具更低的策略值；治理状态与回执分别受 1,000,000 实际编码字节上限限制。
示例只是正向契约材料，不能证明实际业务实现正确或资源授权已通过；领域边界用例仍须工具开发者提供。

UNKNOWN 风险、UNVERIFIED 幂等不发布。DOMAIN_OPERATION_KEY／REMOTE_OPERATION_KEY 必须附人工核实的实现依据；非空材料不是平台自动证明防重实现。
审核 minimumApproval 不得低于风险要求，默认策略不得低于审核要求；首批对所有 EXTERNAL_EFFECT 保守要求
INDEPENDENT_APPROVAL，未放宽 S1-01 的任何结构约束。

当前 BusinessToolAdapter 只有单次最终结果端口：features 必须为空，outcomeQuerySupported 必须为
false。STREAMING、ASYNC_TASK／TASK_QUERY、CANCEL、USAGE_REPORTING 或业务核对声明仍可被发现，但发布返回 UNSUPPORTED_CAPABILITY。
对应执行协议及验收落地后再扩展；不因描述元数据存在就宣称支持。远端发布、Secret 授权及多节点实现一致性仍未接入。

首次发布保存原审核材料、实际操作者稳定主体、发布时间、节点清单摘要及 tool-release-contract-v1。契约摘要覆盖工具
checksum、实际提供方、固定目标及三类 Schema 摘要，引用排序；不包含授权、治理行版本或操作时间。
事务内重新核对固定事实、Schema／示例与风险约束，拒绝过期（超过 1 分钟）、未来时间或摘要不匹配的报告。报告只能由内部服务产生，不是可从
HTTP 绑定的客户端凭据。 重新启用重新检查当前实现与原契约，不以首次通过的报告替代当前资格。

### 6.3 兼容前驱及治理事务

compatibleFrom 只接受同一稳定工具的另一固定版本，前驱须已 PUBLISHED／DEPRECATED；首次发布目标才允许建边，发布后不能改写。
当前采用保守自动检查：三类 Schema 内容摘要相同，风险／必要权限／网络 origin、工具能力、默认策略及非敏感默认配置相同，并有人工语义兼容依据。
不满足者可以作为不带兼容边的独立版本发布；不会按 v2／v10、大小写、更新时间自动推断兼容。更宽泛的 Schema 兼容证明及绑定选版留给
S1-07。

同一稳定工具的治理锁串行化兼容边变更。前驱唯一索引禁止多个后继；“已发布前驱→首次发布目标”且边不可改写，按构造顺序无环，不以版本字符串或时间排序证明无环。
重新启用保留原边，不重新分叉。

MybatisToolReleaseStore 复用物理 DataSource、独立 MyBatis 工厂、有界 DB 调度器及 UTC 微秒时钟，不引入另一套身份或工作空间体系。实际操作者使用现有
core.pojo.platform.TrustedActor／Principal；构造受信对象不等于完成认证或当前授权。

管理事务按以下顺序执行：命令键／稳定工具身份锁排序取得 → 核对历史回执与语义 → 当前治理行版本及状态检查 →
发布事实／兼容检查 → 状态与不可变审计回执同事务写入。
同一真实主体类别／subjectId／幂等键跨工具及动作共用键空间；语义摘要包含固定工具、动作、expectedVersion、原因、审核材料和废弃截止，不包含重算预检时间。
同键同语义重放首次决定；改变语义返回 IDEMPOTENCY_CONFLICT，不写第二份记录。NOT_FOUND、VERSION_CONFLICT、INVALID_STATE
也固定保存首次回执，防止之后环境改变时同键变成新操作。

StoreOutcome 外层 APPLIED／REPLAYED 表示回执首次登记／重放，真实领域结果读取 value.outcome；预检、状态、兼容拒绝原因可从回执稳定码读取。
数据库故障或编码超限传播 onError 并回滚，不把故障保存成假拒绝。审计写入失败时治理状态和兼容占位一起回滚；提交不发送额外业务通知或调用工具。
当前状态与审计索引均核对白名单快照字节摘要、实际引用、状态、行版本和微秒时间。history 最多 100 条，用不透明主键游标，不承诺时间排序或跨页同步快照。

治理回执本身是本批原子审计记录；S1-08 对外接线须先复核当前管理／审计读取资格，不能把本服务直接暴露成 HTTP，也不能用额外独立
AuditSink 调用冒充同事务审计。 公共治理审计如需投影，应在该阶段落实其可靠一致性，不在本批增加没有消费者的通知设施。

### 6.4 编码、DDL 与装配

新增白名单根别名 tool-release、tool-release-receipt，均写 schemaVersion=1，拒绝小数、字符串或未知版本；不开放类名多态。旧目录、绑定
v2、执行／事件／结果格式保留原义。 工厂显式 Mapper 数量由 10 增为 11；新 SQL 使用唯一限定的方法名，避免短名与旧 Mapper
歧义。新查询不经过旧 MapperScan 或全局 MyBatis 插件。

新增 arte_ai_tool_release 当前治理表、arte_ai_tool_release_command 管理防重／审计回执表。工具与前驱外键指向不可变定义；前驱唯一约束防分叉，治理行约束正版本与三个持久化状态。
不存在治理行的已登记定义派生草稿，不回填伪造发布或伪操作者；缺表故障不会伪装成空目录／草稿。

- 新环境使用更新后的 [完整基线](scripts/arte-ai-new-ddl-mysql.sql)。
- 已部署 S1-04 的环境执行 [生命周期增量](scripts/arte-ai-tool-release-migration.sql)；尚未部署目录表时先执行 S1-04 增量。
- 执行前停写、备份并核对两张新增表不存在；不运行完整基线覆盖旧库，不用 IF NOT EXISTS 掩盖结构漂移。
- 脚本只作为部署文件与测试资源，不进入运行时 JAR，Bean 构造及启动均不执行 SQL 或 DDL。

NewAiAdmissionConfiguration 构造底层存储但不访问新增表；两个主开关打开后，NewAiToolConfiguration 增加发布预检和内部治理服务
Bean。 沿用已有两个配置参数，不新增自动发布开关；YAML 示例已同步。默认 TOOL 仍关闭，未修改本地 app.properties 或启用生产工具。

### 6.5 验证证据

代码基于 `305cb34e68689a44148b03e97b424d858363256a` 的工作区；本地 JDK 21.0.8、Maven 3.9.16。 新增 ToolReleaseTest 33
项，使用临时 H2 MySQL 模式与真实
MyBatis，覆盖状态／截止、原契约恢复、两实例并发、成功及拒绝重放、跨工具键冲突、真实主体、元数据故障下撤销、当前实现、示例／风险／幂等／能力门禁、过期／伪造摘要、事务内重验、审计故障回滚、兼容边／数据库唯一及外键、快照完整性、分页、严格编码和基线／增量一致性。
既有装配测试补充默认关闭、治理存储构造零 SQL及治理服务无自动操作断言。测试仅在 H2 执行本批脚本；配置中的业务数据库未连接、未检查、未修改。

最终针对性回归命令（允许本机受控 HTTP 临时端口）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
PATH="/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin:$PATH" \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.app.platform.persistence.PlatformBindingJsonTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS；arte-ai 334 项、arte-app 3 项，共 337 项，失败／错误／跳过均为 0，Reactor 编译通过。 随后整理等价的 Optional
取值及 import 后，本批 33 项和装配 6 项共 39 项再次通过，生产／测试代码重新编译通过；最终差异检查及基线／增量 DDL 一致性检查通过。
测试中保留 DDL 连接，处理 H2 2.4.240 的 IN 检查表达式持有已关闭创建会话的问题；实际事务仍使用独立连接。初轮发现的 Mapper
短名歧义已通过唯一方法名修复，未弱化工厂断言。 故障注入的 SQL 错误为预期；既有重复 H2 依赖、macOS Netty DNS native
fallback、资源编码及旧 API 提示仍存在。本批未重跑有已知基线问题的整套 ProfileConfigurationTest，未把它列为通过。

### 6.6 需求追踪、边界及下一批

FR-03.3／03.4、FR-05.3 对应本批状态事务、本地发布检查和保守兼容边；FR-01.1 的发现未发布版本已具有明确派生草稿语义。
AC-02／03／05／08 当前只完成本批组件验证。FR-03.2 的下一敏感执行边界仍须 S2 消费最新治理状态；不能把 permitsExistingBinding
当作完整执行资格。 当前绑定、follow-compatible 自动解析／升级预览、多节点就绪及异构领取、远端发布、实际工具执行、当前管理授权、HTTP
和前端均未完成；没有调用领域工具、真实模型或远端供应商。

S1-06 完成时的下一批为 S1-07：绑定及修订，实际交付见 §7。S1 整体尚未完成，未运行全量测试、实际 MySQL
隔离／锁竞争、生产性能、多节点部署或浏览器验收；业务数据库的
S1-04／S1-06 增量仍须按部署流程单独执行。

## 7. S1-07：私有绑定、不可变修订与固定解析

状态：完成内部本地绑定管理／解析及本批验证。S1-07 完成时下一批为 S1-08，实际管理 API 与当前授权接入见 §8。 未注册生产工具、启用
TOOL 或改变原生成 BindingManager／FixedControlCatalog，也未接入 Worker、聊天或默认绑定创建。

### 7.1 代码与引用

| 类型／位置 | 职责 |
| --- | --- |
| pojo.tool.ToolBindingDefinition／Revision／Settings | 稳定个人身份、当前指针及完整不可变配置；真实 owner 和稳定 toolId 创建后不能改变 |
| ToolBindingCommand／Result／Receipt | 完整保存、启停、升级确认；稳定拒绝原因及原始命令审计，不混淆领域决定和回执首次／重放 |
| ToolBindingResolution／UpgradePreview | 固定实际工具及合并配置／约束；只读升级预览和确认摘要 |
| application.tool.binding.ToolBindingConstraints | 对象递归合并、数组／标量／null 替换；策略取交集 |
| ToolBindingStore／MybatisToolBindingStore | 同库指针／修订／审计事务、分页、审核链选择、预览重算及固定读取 |
| ToolBindingService | 有界元数据线程复核当前健康发现视图和实际适配器声明，不调用 invoke |
| serialization.ToolBindingDigests／ToolCatalogDigests | 集合排序的管理语义摘要、标准 JSON 配置摘要与字节容量 |

创建 SAVE 的 bindingId 为空、expectedVersion=0；服务端按 owner 三元组、主体类别与幂等键派生稳定 ID。 修改必须提供稳定 ID 和当前
expectedVersion，成功增加一个治理版本并追加完整 `binding/{id}/rN`；名称或启停变化也保留历史。 同工具允许多个命名绑定。SAVE
不能更换工具基线，须经过 UPGRADE；稳定 toolId／owner 无法更换，无变化返回 NO_CHANGE。

动态解析保存内容寻址的 `binding/{id}/p1-{SHA-256}`，包含来源 rN、真实 owner、实际产品及提供方／目标、发布契约摘要、合并配置与摘要、有效策略、外发范围和
Secret 引用。 同一有效计划复用原材料；新兼容发布或有效服务器约束变化产生不同 p1，不改写 rN。计划摘要中的集合先排序，读取另外核对实际存储字节摘要及原始身份。
解析材料不含调用参数、Invocation、临时授权或预算资格，不是 S2 ToolExecutionSnapshot；S2 仍需把参数和实际引用绑定到耐久受理及派发。

### 7.2 配置、策略、归属及撤销

- 每次 SAVE／UPGRADE 都提交完整覆盖层，只与工具默认配置合并，不与上一 rN 合并；对象递归，数组／标量替换，null 保留为显式值，再校验配置
  Schema。
- 配置标准 JSON 字节容量复用当前 arte.ai-new.limits.max-input-bytes，与工具 arguments 的 maxInputBytes
  分开；固定读取也复核当前配置容量。保存前还检查整份固定材料的编码容量，避免留下无法解析的绑定。
- 工具默认策略、可选绑定策略及服务器 limits 求交集：超时／次数／输入输出取小、最低退避取大、审批取强。退避超过最终期限返回
  POLICY_CONFLICT；上限不是安全重试授权。
- 服务器总期限／输入输出沿用原 limits，尝试次数绝对上限 10，不新增预算系统或复制 Worker 参数。允许集合及单次调用进一步收紧待
  S2／S4。
- 网络范围 null 表示继承，空集表示禁止外发；新增工具未声明的目标返回 NETWORK_NOT_ALLOWED。绑定不能开启实现未支持的协议能力。
- 当前本地端口没有凭据槽／Secret 使用授权，非空引用明确返回 SECRET_UNSUPPORTED，不读取凭据值，不假造已验证引用。远端连接与凭据另在
  S5 落实。
- 所有读取核对 tenant/workspace/subject；越界不返回私人配置或当前版本。管理只接受 ACTIVE 且与 owner.subjectId 相同的可信
  actor，但仍需要 S1-08 检查当前 RBAC／空间资格。
- SET_ENABLED (false) 只复制原设置为新修订，工具停用或发现失败不妨碍撤销；重新启用重验配置、策略及生命周期。绑定停用阻止历史
  p1，普通配置更新保留历史 p1。
- 固定读取不重新选择版本，检查当前绑定启用、实际工具生命周期和服务器约束；更严策略失配时拒绝，不原地改写旧材料。实际执行边界的再次检查仍待
  S2。

### 7.3 版本与升级

PINNED 仅使用固定基线；FOLLOW_COMPATIBLE 只沿 S1-06 唯一审核边前进，不按标签／时间排序，最多访问 128 个版本。
逐一验证身份、重复节点及生命周期；中间停用、废弃到期、缺失或链过长即拒绝，不跳过或静默回退。 新建及显式升级最终选择须为
PUBLISHED；已有绑定可在 DEPRECATED 截止前继续使用。固定 p1 读取只检查实际版本，不受新发布自动替换。

升级预览不写指针、修订、回执或解析材料，返回当前基线／实际版本、兼容性、目标方案、变更类别和摘要。
变更包含配置、版本策略、风险、能力、执行约束、网络、Secret 引用、名称及启停；完整目标方案可供后续管理响应呈现。 确认须同时提交
expectedVersion 和预览摘要，在事务内重算目标／治理事实；变化返回 PREVIEW_CHANGED。 没有审核路径时必须
acceptIncompatible=true，并提供通过目标配置 Schema 的完整设置，不代替当前授权。 旧基线停用后仍可显式预览并升级到可新绑定的目标。允许工具集合的影响分析留给
S4，不输出虚构 ToolSet 清单。

实际可用解析／固定读取／成功预览还通过 ToolBindingService 复核当前节点的完整健康视图、精确固定定义及实际
adapter.capability； 声明停用、缺失、异常或读取超时返回 EXECUTOR_UNAVAILABLE。读取在已有有界元数据调度器执行，期限沿用
discovery.source-timeout。 管理配置可在节点暂不可执行时保存，不以保存成功冒充可用解析；不调用 BusinessToolAdapter.invoke
或凭据解析器。

### 7.4 事务、编码与部署

管理先锁命令／绑定，再锁与发布共用的稳定工具身份；预览、解析和固定读取也遵循绑定→工具顺序。 同一个物理 DataSource、工作线程及
Spring 事务保存当前指针、rN、原始回执／审计；依赖数据源不一致会在构造时拒绝。 管理键在真实 owner 和 actor
范围跨绑定／动作共享；完整设置、原因、expectedVersion、预览摘要及确认参与语义比较。 首次成功或拒绝均保存原决定，同语义返回
REPLAYED，改变语义返回 IDEMPOTENCY_CONFLICT；SQL／编码损坏通过 onError 传播并回滚，不能伪装业务拒绝。 外层
StoreOutcome.APPLIED／REPLAYED 是回执首次／重放，领域决定读取 receipt.result。审计游标表示存储位置，不代表时间排序或跨页一致快照。

新增四个内部白名单根：tool-binding、tool-binding-revision、tool-binding-resolution、tool-binding-receipt，均写
schemaVersion=1，严格整数版本。 旧 resolved-binding v2／v1、生成配置六字段、Invocation／Attempt／结果／事件编码保持原行为。
单条内部材料实际编码不超过 1,000,000 字节，列表／历史最多 100 条；解析材料去重但未实现清理任务，生产容量需随 S2 实际受理量验收。

- 新环境使用更新后的 [完整基线](scripts/arte-ai-new-ddl-mysql.sql)。
- 已有环境先部署 S1-04、S1-06，再执行 [S1-07 绑定增量](scripts/arte-ai-tool-binding-migration.sql)
  ：binding、binding_revision、binding_resolution、binding_command 四表。
- 停写、备份、核对目标表不存在后显式部署；脚本不进入运行时 JAR，Bean 构造零 SQL，不自动创建绑定或发布工具。
- 配置中的业务数据库本次未连接、未检查、未执行任何迁移；其他环境同样需要显式部署，不能以临时 H2 通过代替 MySQL 验收。

### 7.5 验证证据

JDK 21、Maven 离线 Reactor 编译通过。新增 ToolBindingConstraintsTest 5 项、ToolBindingStoreTest 39 项，共 44 项。 使用临时 H2
MySQL 模式、真实隔离 MyBatis、多实例独立连接及 UTC 微秒时钟；保留 DDL 会话处理 H2 的 IN 检查表达式限制，不连接业务数据库。
覆盖完整配置／null／数组、Schema、UTF-8 字节及整份材料容量、策略冲突、Secret 拒绝、网络收紧、owner
隔离、多个命名绑定、不可变修订、分页、无变化、并发版本冲突、成功／拒绝重放、审计失败回滚、固定计划与当前撤销、服务器容量收紧、pinned／审核链／不兼容确认、停用中间节点、129
版本链超限、预览变化、旧基线修复、当前实现停用／缺失／超时及严格编码／完整性。

针对性回归命令（允许受控本机 HTTP 临时端口）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.app.platform.persistence.PlatformBindingJsonTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：BUILD SUCCESS，arte-ai 378 项、arte-app 3 项，共 381 项，失败／错误／跳过均为 0。现有测试同时补充新 Mapper
注册数量、依赖表清理及基线／增量分段断言，修正发布测试夹具的静态导入。 审计失败注入的 SQL 错误是预期结果；既有重复 H2
依赖、资源编码、旧 API、macOS Netty DNS native fallback 提示未在本批处理。 不是全量测试，也不是实际 MySQL
锁竞争、生产吞吐、多节点部署或浏览器验收；未将存在既有问题的整套 ProfileConfigurationTest 计入通过。

### 7.6 需求追踪与下一批

FR-04.1 对应不可变修订与管理事务，FR-05.1 对应显式链、固定材料及升级预览；AC-04／05／08 当前只完成本批内部组件验证。 FR-05.2 的
Invocation 重放、FR-03.2 的实际敏感执行边界、默认绑定及完整 TOOL 受理／派发仍待 S2。 S1-08 接入当前授权、管理 HTTP
和错误／预览响应；不得把底层 owner／actor 结构检查当作已完成当前 RBAC。 TOOL 仍默认关闭，未实现共享绑定、远端
Secret、允许集合、审批、工具聊天、领域工具执行或前端。

## 8. S1-08：管理 API 与现有当前授权

状态：本地管理后端基线完成。当前接口见 [TOOL 管理 HTTP API](TOOL_HTTP_API.md)，实际 TOOL 受理、执行、默认绑定及前端仍未实现。
本批没有启用开关、登记生产工具或连接配置中的业务数据库。

### 8.1 已交付入口与职责

`NewAiToolController` 提供 16 个 JSON POST 入口，复用现有认证、语言及 ResultContext：

- 目录：listTools、getTool、listProviders；普通目录隐藏草稿并按当前 requiredScopes 过滤，管理模式可见草稿。
- 本地刷新：refreshLocalCatalog，完整读取全部显式登记来源，保留 S1-05 的完整候选校验与视图原子替换。
- 发布治理：validateTool、publishTool、setToolState、getToolHistory。
- 本人绑定：listBindings、getBinding、getBindingRevision、saveBinding、setBindingState、getBindingHistory。
- 显式升级：previewBindingUpgrade、upgradeBinding；预览不写库，确认时重算实际方案及摘要。

`application.tool.management.ToolManagementService` 是公开用例门面；原本的内部发现、发布及绑定服务保持职责，不把内部 Store
当作当前授权入口。 目录按主键游标有界读取，生命周期批量查询，避免随每页工具数增加 SQL 次数。每页扫描上限 100，默认 20；响应
records／nextCursor／scanned／exhausted，过滤空页仍可能有下一页。 游标绑定真实主体／空间／操作／筛选条件，不作为权限凭据，不声明跨页快照或总数。

提供者仍为不可变部署定义。当前完整刷新替代本地“单提供者同步”的产品操作，保持全量视图语义；未实现
syncProvider／setProviderState，须在后续可变提供者治理与远端接入时实现。 不提供任意 URL、客户端注册适配器、默认绑定、TOOL
invoke、审批或工具聊天入口，不把这些计划项计为完成。

### 8.2 现有基础上的当前授权

新增 `spi.auth.ToolManagementAccess` 作为宿主端口，arte-app 使用 `PlatformToolManagementAccess`，复用：

- `RbacIdentityProvider` 的会话 PK、账户名、accountCreatedAt 和稳定主体映射，新增 actorCurrent 做锁定当前读的会话复核。
- 公共 PublicTransactionRunner、WorkspaceWriteGuard、AuthorizationService 的账户／租户／空间状态及成员政策。
- 原有 RBAC 关系表及启用角色。`RbacToolPermissionReader` 使用物理 appDataSource 的当前事务读取，不使用 token 权限缓存或
  ThreadLocal 数据源路由。
- 原有 DATABASE 空间 AI enabled／公共 AI 使用资格，或 FIXED 匹配真实身份与空间的 grant；不查询生成模型、费率或预算目录。

| 操作            | 当前检查                                                                |
|-----------------|-------------------------------------------------------------------------|
| LIST            | aiTool:list + WORKSPACE_READ + AI READ                                  |
| BINDING_WRITE   | aiTool:invoke + WORKSPACE_AI_USE + AI INVOKE + 当前工具必要 scopes      |
| BINDING_DISABLE | aiTool:invoke + WORKSPACE_READ；DATABASE 空间 AI 关闭后仍可撤销本人绑定 |
| MANAGE          | aiTool:manage 与 aiConfiguration:manage + WORKSPACE_READ                |

目录与发布是平台全局治理，空间 owner 不自动获得 MANAGE；绑定归属只由当前真实 tenant/workspace/subject 产生，越界读取／修改不披露他人版本。
既有 admin 操作标记只满足菜单检查，不绕过公共当前资格。FIXED 仍遵守现有启动配置规则，本批没有放宽其生成配置校验。
菜单操作不能变成业务执行 scopes；当前 DATABASE 只派生 ai:read／ai:invoke，额外业务 scopes 超出资格时隐藏或拒绝。 领域资源授权仍需
S2 在实际调用边界接入。

所有读取先复核当前资格；公开变更把最终门控传入 MybatisToolReleaseStore／MybatisToolBindingStore／发现管理 Store 的原事务。
顺序为当前公共账户／租户／空间和 RBAC／空间 AI 资格锁 → TOOL 命令及治理锁 → 条件更新与审计提交。
原成功或拒绝的幂等重放也执行门控；撤权不能通过重放读取旧成功。来源加载／发布预检不持有这些数据库锁，提交前重新授权。 初始化不读
SQL、不刷新来源；两个开关和公共身份／持久化／空间基础缺一不可，物理数据源必须一致，不注册允许式兜底。

### 8.3 刷新审计与事务

新增 `ToolDiscoveryManagementStore`／`MybatisToolDiscoveryManagementStore` 和 `arte_ai_tool_discovery_command`，与目录事实同库提交。
`LocalToolDiscovery.refresh(commit)` 允许管理用例在候选校验后注入授权及审计事务，原内部 refresh 仍可使用原存储路径。

- 命令键由真实主体／类别／Idempotency-Key 派生；请求语义包含完整本地刷新意图、Scope 和原因。
- 同键同语义返回首次回执，不再次读取来源；不同语义返回冲突。两个并发相同键不覆盖原候选事实。
- 成功目录及回执同事务，审计失败回滚事实且不安装节点视图；来源明确失败耐久保存首次拒绝。
- SQL 故障／节点忙不保存假拒绝。忙状态 409，允许同键重试；故障保留旧视图并标记不健康。
- 回执只保存固定主体、原因、决定、目录 checksum／数量和 UTC 微秒时间，不保存目录原文或异常内容。
- 原始 checksum 与当前节点 nodeDiscoveryHealthy／nodeChecksum 分开返回；重启后重放历史成功不能让本节点假装已完成发现。

新增 `tool-discovery-receipt` v1 白名单根，严格整数 schemaVersion；持久化读取核对字节摘要、原始身份及请求语义。 隔离
ExecutionSqlSessionFactory 注册 13 个显式 mapper，未修改全局 MyBatis 路由／扫描规则。

### 8.4 HTTP 值、回执与错误

`ToolManagementRequests` 使用严格白名单，Scope 不含 owner／actor／角色／执行 scopes；管理行版本使用十进制字符串。 配置和审核示例通过有界
`ToolJsonNodeDeserializer` 转为精确数值／显式 null 的标准 JSON，拒绝重复键、超限节点／深度／字段与异常数字尺度。
`ToolManagementValues` 处理 StructuredValue 转换、配置字节上限、审核示例每份 32 KiB 上限与常见凭据字段；当前本地绑定拒绝非空
Secret 引用。 凭据字段检查只是辅助，不能证明任意字符串不包含秘密。 内部快照 JSON 树也启用精确 BigDecimal 读取，修复已校验小数在持久化往返时被
double 舍入的问题；不改变根别名、字段及 schemaVersion。 HTTP + H2 测试覆盖高精度小数、大整数的保存、读取及同键重放。

`ToolManagementResponses` 显式投影，不返回内部多态 codec、执行器目标、连接地址、临时授权或审核示例原文；普通用户的工具详情不含默认配置。
`ToolManagementHttpResult` 区分回执登记 APPLIED／REPLAYED 与原始 outcome／rejection，拒绝返回对应 HTTP
状态及安全回执，防止首次保存拒绝被误报成功。 升级预览与发布预检按 valid 表达领域校验结果；传输／授权／基础设施错误使用对应状态。

新增七个 AI_TOOL_* 错误码及默认／en_US／zh_CN／zh_TW 消息；限定 Controller 的异常包装统一同步／异步错误，不返回
SQL、配置、审核原文或原始异常。 日志只记录方法、路径、状态、码及异常类别。

### 8.5 部署交付与环境状态

- 新环境：[AI 完整基线](scripts/arte-ai-new-ddl-mysql.sql) 已包含十张 TOOL 表；不要再重复执行各 TOOL 建表增量。
- 已有环境：S1-04 → S1-06 → S1-07 → [S1-08 刷新审计增量](scripts/arte-ai-tool-management-migration.sql)
  。停写、备份并核对目标表／索引，运行时不建表。
- RBAC：[TOOL 操作定义增量](../arte-app/scripts/arte-ai-tool-rbac-dml-mysql.sql)；新环境原 RBAC 基线 DML 已包含相同段。
  只补缺失定义，重复执行保留已有定义／停用状态；不新增菜单、不授予角色／用户。授予通过现有 RBAC 管理按需完成。
- 未连接、检查或修改配置中的业务数据库，未执行生产 MySQL 增量、权限授予或工具发现；TOOL 保持默认关闭。

### 8.6 验证证据

新增测试：

| 测试类                           | 数量 | 核心证据                                                                                                                                                    |
|----------------------------------|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ToolManagementHttpTest           | 26   | MockMvc + 真实 MyBatis/H2：目录／发布／绑定／升级、原始回执／重放、越界隔离、事务内门控、刷新原子性／失败回滚／节点健康、语言及安全错误                     |
| ToolManagementValuesTest         | 9    | 精确数值及快照往返、null、重复键、深度／宽度／scale、UTF-8 容量、示例大小、凭据字段、版本格式                                                               |
| ToolManagementAssemblyTest       | 4    | 双开关、缺授权失败、构造无副作用、16 条管理路由及未实现入口缺席                                                                                             |
| ToolDiscoveryContractTest        | 2    | 完整／增量 DDL 一致、白名单根及严格整数版本                                                                                                                 |
| PlatformToolManagementAccessTest | 21   | 真实公共身份／空间与当前 RBAC：撤权／重放、AI 关闭、账户停用／改名、空间归档、租户停用、移除成员、启用角色、FIXED grant、同物理写事务及授权关系锁持续至提交 |
| RbacToolDefinitionScriptTest     | 1    | 原 RBAC DDL 上重复执行定义增量，保持停用／版本、不增加菜单或授权关系；与基线段一致                                                                          |

合计新增 63 项。验证环境为 JDK 21、临时 H2 和本机受控 HTTP，不连接配置中的业务数据库或外部模型。 新增 63 项及选定兼容回归共
**507 项通过**（arte-ai 419、arte-app 88），0 失败／错误／跳过，JDK 21 Reactor 编译通过。
覆盖原有契约、Schema、发现、发布、绑定、持久化、生成装配／网关／派发、配置发现、平台身份／登录会话及 DATABASE 聊天配置。
这是针对性回归，不是全仓测试；未执行旧 AI 全量套件、真实 MySQL 验证、压力测试或前端验收。 H2 中的授权关系锁测试证明测试环境内的事务行为，不能据此宣称生产
MySQL 锁性能或整应用端到端已验收。

执行命令（仓库根目录）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-app -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.admission.ConfigurationAssemblyTest,com.arte.ainew.admission.ControlPreparationTest,com.arte.ainew.admission.GenerationAssemblyTest,com.arte.ainew.admission.GenerationEgressTest,com.arte.ainew.admission.ConfigurationHttpIntegrationTest,com.arte.ainew.admission.GenerationGatewayTest,com.arte.ainew.admission.DispatchIntegrationTest,com.arte.app.platform.persistence.PlatformBindingJsonTest,com.arte.app.platform.persistence.PlatformToolManagementAccessTest,com.arte.app.platform.identity.*Test,com.arte.app.platform.persistence.DatabaseAiControlIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

首次扩展平台回归受沙箱内 Mockito JVM self-attach 限制影响；取得本机测试能力后按上述完整命令重跑，最终日志
`/tmp/arte-s108-final-regression.log` 为 BUILD SUCCESS。 既有 S1-06／S1-07 测试的 LocalToolFixtures 静态导入已恢复，未修改其业务断言。
完整／增量脚本只交付，不通过本条测试命令连接或修改业务数据库。

### 8.7 需求追踪与下一阶段

FR-01 的本地发现／草稿、FR-02 的当前可见目录、FR-04 的个人绑定修订和 FR-05 的升级在管理 HTTP 已接线；对应
AC-01／AC-02／AC-04／AC-05／AC-08 的本地管理部分具备测试证据。 FR-03 的实际资源授权、默认绑定、Invocation 受理、TOOL
dispatch、免费／计费准备及执行期限仍留在 S2；审批、结果核对、工具聊天、远端和 UI 按后续批次实施。 本批未改变现有生成配置发现、模型调用、预算账户或
Worker 领取逻辑，S1 完成只表示本地管理后端基线完成。
