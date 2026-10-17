# AI TOOL 实施记录

日期：2026-10-17。需求基线见 [ARTE AI TOOL 需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)
。本记录按批次更新，只有明确完成的批次表示已有代码和验证证据。

当前完成：S1-01、S1-02、S1-03。S1 整体尚未完成，TOOL 尚未开放使用。

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
