# AI TOOL 实施记录

日期：2026-10-19。需求基线见 [ARTE AI TOOL 需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)
。本记录按批次更新，只有明确完成的批次表示已有代码和验证证据。

当前完成：S1-01—S1-08 本地管理后端基线、S2-01 FREE／CHARGE 原子派发准备，S2-02 内部本地只读网关、S2-03 内部可靠受理／执行快照及
S2-04 TOOL Worker 派发／完成、S2-05 查询／取消／本地恢复、S2-06 分类容量／排队及 S2-07 目录／个人配置页面；17 个管理 API
与现有当前授权接线完成。S2-08 已完成调用预览／可靠提交／结果和恢复页面；S2-09 已补齐正式宿主执行授权、真实文本工具及本机集成验收／性能基线，正式参考环境性能待验，详见
§17。 TOOL 与分类调度 默认关闭；S1-04／S1-06／S1-07／S1-08／S2-03／S2-06 增量、TOOL RBAC 定义及 S2-07 菜单 DML 本轮未在配置中的业务数据库执行。

§1—§13 保留各批完成时的范围与验证记录；管理入口见 §8、派发准备见 §9、网关见 §10、可靠受理见 §11、Worker 见 §12、查询／取消及恢复见
§13、分类容量与排队见 §14，目录与绑定页面见 §15，调用预览与结果页面见 §16，本机集成与首次使用见 §17。

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

## 9. S2-01：FREE／CHARGE 原子派发准备

状态：完成内部计费计划、免费派发事务及模型收费路径兼容；S2 整体未完成，不开放实际 TOOL 受理／执行入口。

### 9.1 内部命令与兼容边界

`DispatchPreparationStore.Command` 增加封闭 `BillingPlan`：

| 计划   | 参数与约束                                                               | 准备结果                                                                                                     |
|--------|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Free   | 无金额、费率、预留有效期；存储仅接受 TOOL 且 context.budgetRef 为 null   | ready=true、billingMode=FREE；Attempt 无 reservation                                                         |
| Charge | Money、固定 rate、1 秒至 30 天的 retention；账户及费率继续按现有规则校验 | 成功为 ready=true、billingMode=CHARGE 且有 reservation；余额不足为 ready=false、NOT_STARTED 且无 reservation |

金额为零的 Charge 仍走既有收费准备，不自动改为 Free。缺失收费参数明确拒绝。保留原七参数 Command 构造、amount／rate／retention
访问器及三参数 PreparedDispatch 构造，均保持 CHARGE 含义；FREE 调用收费参数访问器抛出 IllegalStateException，不返回伪金额或伪费率。
结果构造校验 ready、派发状态、计费模式与 reservation 的一致性，不能把免费准备结果当作有费用许可的结果。

计划属于可信应用层内部命令，不接受客户端传入；仅检查 kind 与 budgetRef 不代表完成工具计费资格校验。后续 TOOL
受理／派发必须从受信定义和配置确定计划。 命令和准备结果不进入执行 codec；没有“旧命令快照解码为
CHARGE”的存储变更。Invocation／Attempt 继续使用原有快照格式，无 DDL、codec 版本或历史数据回填。

### 9.2 原子准备与安全检查

`MybatisExecutionPersistence.prepareDispatch` 保持 Invocation → 已领取 Outbox → 按需 Account → Reservation 的持锁顺序：

1. 读取并锁定权威 Invocation，核验 owner／版本；锁定 Outbox，检查完整领取身份、worker、token、未 ACK 及数据库时钟下的租约。
2. FREE 在创建 Attempt 前拒绝非 TOOL 或带 budgetRef 的调用，防止模型或预算绑定调用绕过预留。
3. 复用 createAttemptLocked 的取消、总期限、尝试上限、前次安全重试、唯一 Attempt ID、序号和 fencing 校验；保存 RUNNING 及状态事件。
4. FREE 不读取账户、不创建 Reservation／Settlement，再次检查领取，复用 markDispatchLocked 检查取消、总期限、执行租约及 EXECUTE
   purpose，写入稳定派发身份与 MAY_HAVE_EXECUTED。
5. CHARGE 保留原账户归属、金额／费率校验及原子预留；不足只提交未发送 Attempt。两条分支发生其他拒绝或写入错误均整体回滚。

准备成功只允许本次事务提交后的首次执行；重复命令返回版本冲突，不返回另一份可发送许可。实际调用必须在事务外，不能把准备重放当作外部业务重试。
FREE 的 Usage 仍为既有 unknown 测量语义，不伪造模型 token；免费无账户费用不等于没有耗时、步骤、并发或资源限制。

`GenerationDispatcher.startAtomically` 显式传入 Charge，保留原模型报价、预留及不足处理。现有通用 markDispatch
不放宽任何预算检查；本批没有修改 Worker 领取、模型网关或现有收费结算。

### 9.3 验证范围

新增 18 项测试：DispatchBillingContractTest 4 项、FreeDispatchPreparationIntegrationTest 13 项、既有
DispatchPreparationIntegrationTest 增补 1 项模型 FREE 拒绝验证。 覆盖旧构造／访问器、零报价、必填收费参数、领取归属、无账户免费派发、跨实例快照读取、并发／重复拒绝、取消、陈旧
token、领取／Attempt／总期限过期、 派发写入失败整体回滚及恢复重试、预算绑定 TOOL 与无预算模型不能使用 FREE、CHARGE
不降级为免费、结果模式一致性。付费的共享账户竞争、余额不足、失租及预留到期测试继续保留。

使用 JDK 21、隔离 H2 和本机受控 HTTP，执行契约、持久化、S1 工具管理及全部 admission 测试，42 个测试类共 584 项： **577 项通过，1
项失败、2 项错误、4 项按条件跳过**；不能记为全绿。新增 18 项全部通过，Reactor 主代码／测试代码编译通过。 4 项跳过均因未提供独立测试
Redis（ExecutionAssemblyTest 1 项、InvocationHttpIntegrationTest 3 项），未借用配置中的业务 Redis。 3 项失败均位于既有
ProfileConfigurationTest：2 项缺少平台 ExecutionAuthorizationResolver 装配，1 项 application-ai.yml 的旧 import 断言不匹配。
已在临时副本恢复本批三个生产类及原派发测试为 HEAD、移除两个新增测试，仅保留 S1 静态导入编译修复，重新执行该类，复现相同 1
失败／2 错误；确认不由 S2-01 引入，本批不扩大为配置改造。

扩展验证命令（仓库根目录）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.admission.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

日志：`/tmp/arte-s201-regression.log`；隔离基线复核：`/tmp/arte-s201-baseline-profile.log`。 最终核心验证采用相同 JDK／Maven
参数，将 Dtest 替换为
`DispatchBillingContractTest,FreeDispatchPreparationIntegrationTest,DispatchPreparationIntegrationTest,DispatchIntegrationTest,DispatchWorkerIntegrationTest`，
**73 项全部通过，0 失败／错误／跳过，BUILD SUCCESS**；日志为 `/tmp/arte-s201-final-targeted.log`。这 73 项是上述扩展范围的子集，不重复计入总数。
初次模型回归因沙箱禁止绑定本机端口报 Socket Operation not permitted，取得本机测试能力后重新运行；不能把首次受限运行记为模型行为失败。
本次不是全仓验证，未运行旧 AI 全量、arte-app 全量、真实 MySQL、多节点压力、浏览器或生产环境验收。

### 9.4 本批交付边界

- 无数据库结构或数据变更；未连接／修改配置中的业务数据库，TOOL 仍默认关闭。
- 未实现 S2-02 ToolGateway、TOOL 受理快照、默认绑定、分类领取／容量或真实业务工具；这些不能计入 S2-01 完成范围。
- 未实现工具付费定价、Run 总额／跨账户选择、业务结果核对、审批或 UI。S4 免费子执行须由受信选择器明确构造无 budgetRef
  的上下文，不能直接继承父预算后传 FREE。
- 修复当前基线中 ToolBindingStoreTest、ToolReleaseTest、ToolManagementHttpTest 缺少 LocalToolFixtures 静态导入的编译问题；未改业务断言或
  S1 生产逻辑。

## 10. S2-02：本地只读 TOOL 网关

状态：完成单次本地只读网关、固定配置调用材料、执行授权端口及条件装配；S2 整体未完成，尚无用户可调用的 TOOL 执行入口。

### 10.1 组件及契约

| 组件                                      | 已实现职责                                                                                                                                |
|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| application.gateway.DefaultToolGateway    | 实现 ToolGateway，复用固定绑定／Schema，重新授权、控制期限与取消、检查输出，不执行模型继续决策或内部重试                                  |
| pojo.tool.LocalToolCall                   | GatewayCall 与 ToolBindingResolution 的单次关联材料，传递固定配置／策略，校验归属、目标、工具及 trace／parent／预算／发布引用／操作键不变 |
| spi.auth.ToolExecutionAccess              | 宿主每次复核当前身份、空间、AI 资格、工具使用及实际资源的执行授权端口；无默认允许实现                                                     |
| BusinessToolAdapter.invoke(LocalToolCall) | 新增配置感知的重载；旧 GatewayCall 方法仅兼容空配置，非空配置返回 TOOL_CONFIGURATION_UNSUPPORTED，不进入旧执行方法                        |
| NewAiToolGatewayConfiguration             | 双开关且存在显式 ToolExecutionAccess 才装配网关及 newAiToolInvocationScheduler；已登记 Boot AutoConfiguration                             |

LocalToolCall 不持久化、不从 HTTP 绑定；它包含进程内取消对象，不是 ToolExecutionSnapshot。未新增 codec 根别名或修改
Invocation／Attempt 编码。 有配置的业务适配器应覆盖类型化重载，使用传入的 effectiveConfiguration，不重新选择当前绑定或
latest。领域实际 Query／Command 继续检查资源权限、版本与幂等。

ToolExecutionAccess 的宿主实现留给后续执行接线，须组合 arte_dev 现有公共身份、空间、AI 资格、RBAC 及领域权限，不另建授权权威。
仅提供本批测试授权端口不构成平台资源权限验收；本批没有向 arte-app 注入默认放行实现，也没有注册生产工具。

### 10.2 固定调用与安全边界

1. 订阅时检查当前取消、运行绝对期限及初始 Attempt 租约；按 owner／固定 p1-* 绑定引用读取已保存解析工件，不调用 resolve 重选版本。
2. 复用 ToolBindingService.fixed：绑定当前启用、发布／废弃有效性、契约摘要、服务器上限及节点发现／执行器当前能力均须通过。
3. 校验调用绑定、能力、目标与实际工具引用一致；仅允许本地 READ_ONLY、approval=NONE、无 Secret／网络／协议特性、无费用引用的调用，尝试序号不超过有效策略。
4. 按固定引用读取并核验三类 Schema 摘要；在有界执行资源编译并验证输入／配置，复核配置摘要，不补默认值或加载远端 Schema。
5. 调用 ToolExecutionAccess；结果不能为空，只能保留同一主体类别／稳定主体／租户／空间并缩小运行 scopes，且包含工具必要
   scopes。将当前授权传给适配器。
6. 授权等待后再次执行 fixed 复核，治理停用或执行器变为不可用会阻止实际入口；固定材料不得变化。
7. 通过同一 CAS 协调适配器入口与停止：尚未进入时停止可保存明确未进入事实，已进入后异常或中断不能再宣称未执行。
8. 检查 callId、只读副作用事实及所有已返回 output 的 Schema；按与 MybatisPayloadPersistence 相同的规范化
   InvocationResult.Tool 信封检查 UTF-8 字节，取工具与 Invocation 上限的小值，包含来源、产物、错误及编码开销。

FREE 费用资格须由后续可信受理／派发决定，网关不以缺少 budgetRef 自动授予免费使用资格；有预算引用、费率或 Reservation
的调用明确拒绝。 网关不读写预算、受理记录、执行状态、事件或终态；成功 ToolResult 不等于结果已保存，协调层仍须先持久化结果字节再以
fencing 提交终态。

### 10.3 期限、取消及结果事实

- 受理必须将产品策略与入口总上限求交，把包含排队和授权等待的期限固定到 ExecutionOptions.deadline；该接线属于后续批次。
- 网关以传入绝对 deadline 为上限，并附加 Attempt.createdAt + policy.timeout 的保护期限；Schema、授权等待和执行共用剩余时长，不为每个阶段重新分配期限。
- 同步适配入口、Schema 和编码使用专用有界 Scheduler；默认最多四线程、每线程 32 个排队任务，容器关闭时释放。期限及取消信号可取消订阅，不依赖同步入口返回。
- 取消订阅不证明底层业务已经停止；适配器仍须协作响应取消。进入后的异常／空 Publisher／空结果／超时／取消／无效输出均保守返回
  UNKNOWN／POSSIBLE、retryable=false，丢弃无效输出。
- 进入前拒绝返回 FAILED、KNOWN／NONE；安全错误只含稳定码、阶段与 traceId，不回显原异常、参数或配置。基础设施错误使用
  TOOL_PREFLIGHT_ERROR，区别于输入不合规。
- 合法的领域 FAILED／UNKNOWN 结果保留事实；只读声明不能覆盖适配器报告的真实副作用，发现 POSSIBLE／CONFIRMED 时拒绝正常成功。
- 每个 invoke 返回的 Publisher 只能订阅一次，再次订阅直接报错，不返回另一份“未执行”事实。此内存保护不提供跨
  invoke／跨进程防重；仍依赖耐久准备、领取／执行租约维护与终态事务。

### 10.4 装配与交付边界

`arte.ai-new.enabled` 与 `arte.ai-new-tool.enabled` 均开启且组合模块显式提供 ToolExecutionAccess 时，才创建内部
ToolGateway。 缺少此端口时保留已有工具管理，不创建执行线程池或默认授权；缺少其他必要执行依赖时装配失败，不提供空网关。 构造不执行
SQL、发现、授权或工具调用；不改现有模型派发器、管理 HTTP 路由和 TOOL 默认关闭配置。

本批没有实现 TOOL HTTP／受理快照、默认绑定、Worker 能力路由、分类领取／容量、执行终态协调、审批、业务核对、付费／远端工具或 UI。
未变更 DDL／数据库数据、RBAC 定义或历史 codec，未连接配置中的业务数据库，也未部署已有 S1 脚本。

### 10.5 验证证据

新增两类共 **45 项全部通过**：

| 测试类                     | 数量 | 证据                                                                                                                                                                       |
|----------------------------|------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ToolGatewayIntegrationTest | 40   | 真实 MyBatis／隔离 H2：发现、发布、绑定、FREE 准备后调用；当前治理／权限、配置传递、旧适配兼容、Schema／UTF-8、取消竞争、阻塞入口／期限、异常／UNKNOWN、结果信封及身份不变 |
| ToolGatewayAssemblyTest    | 5    | 双开关、无默认执行授权、只管理部署、构造零 I/O、缺依赖失败、Scheduler 释放及 Boot 注册                                                                                     |

JDK 21 Reactor 主代码／测试代码编译通过；选定契约、持久化、S1 工具管理及 admission 回归共 **625 项，621 项通过、4 项跳过、0
失败／错误，BUILD SUCCESS**。 4 项跳过均因未提供独立测试 Redis（ExecutionAssemblyTest 1 项、InvocationHttpIntegrationTest 3
项），未借用业务 Redis。 本次显式排除既有 ProfileConfigurationTest 的 4 项，其 3 项配置失败已由 S2-01 在改动前代码复现，未计入本次
625 项，也未声称修复。 测试使用本机受控 HTTP，不连接外部模型。H2 沿用既有 TOOL 集成测试持有建表连接的方式，避免 H2 CHECK
表达式引用已关闭建表会话；未为此放宽数据库约束。

执行命令（仓库根目录）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.tool.gateway.*Test,com.arte.ainew.admission.*Test,!com.arte.ainew.admission.ProfileConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终日志：`/tmp/arte-s202-regression.log`。新增 45 项包含在 625 项中，不重复累计。 这是组件与兼容回归，未运行全仓／arte-app
全量、实际领域工具授权、MySQL／Redis 多节点、压力测试、浏览器或生产环境验收，不能视为 S2 产品闭环或性能目标达标。

## 11. S2-03：内部可靠受理与固定执行快照

状态：完成独立本地免费只读 TOOL 的内部受理组件及同库原子提交，不代表 S2 执行产品闭环完成。

### 11.1 组件与固定材料

| 组件                        | 职责                                                                                                 |
|-----------------------------|------------------------------------------------------------------------------------------------------|
| `ToolAdmissionInput`        | 稳定 bindingId、原始结构化参数、可选总时长／尝试数／结果字节上限；不接受客户端版本、适配器或预算账户 |
| `ToolAdmissionService`      | 先定位重放；新调用按当前固定解析进行 Schema／策略校验和宿主授权，固定总期限后耐久受理                |
| `ToolExecutionSnapshot`     | 原始意图、实际 `InvocationRequest<ToolInvocation>`、完整固定绑定及收紧策略、Schema 原文及接收时刻    |
| `ToolAdmissionAccess`       | 宿主明确批准当前免费只读资格，并在真实写事务内再次复核身份、空间、AI 资格、工具及资源权限            |
| `MybatisToolAdmissionStore` | 同一物理数据库事务提交快照、Invocation、受理防重、首事件与 DISPATCH／EVENT Outbox                    |
| `ToolAdmissionDigests`      | 分离稳定意图与实际执行材料摘要，派生确定性 callId，避免随机身份／时刻造成重放冲突                    |

Schema 按固定引用去重保存，最多三份；校验 checksum、输入／配置 Schema 及真实 UTF-8 容量，不补默认值，不执行业务预检副作用。
实际请求的 attempts、outputBytes、timeout
与产品／当前服务器／请求上限求交；输入上限和最低退避等完整策略也写入快照，供后续派发／重试读取；总期限从进入受理组件开始计算，预检、授权、锁等待及后续排队／执行共用。
事务写入前和写完快照后再次检查新调用总期限，过期整体回滚。历史回执重放可以读取已经过期的原执行，不延长它，也不重新派发。

当前只支持独立本地、只读、无审批／外发／Secret／协议特性／业务结果核对的调用；parentExecutionId 和 budgetRef 必须为空。
免费资格来自受信宿主端口的明确批准，不能仅从预算引用为空推断。该端口无默认允许实现，也不能复用管理授权冒充调用授权。 受理不创建
Attempt，不调用 Gateway／业务工具，不创建零金额预留或预算账户，不选择模型费率。

### 11.2 事务、重放与治理

大快照在持锁前编码；沿用 codec 的 4,000,000 字符上限，并增加 8 MiB UTF-8 存储上限。构造器不访问数据库，所有 JDBC 在有界调度器完成。

写事务先复核并锁定当前宿主资格，再锁 owner＋稳定 bindingId＋key，复核绑定和工具生命周期，再复用通用受理的同步事务内方法。
通用方法保留生成入口原行为，不通过异步端口拼接第二笔提交。快照、Invocation、capability 受理索引、首事件及两类 Outbox 共享同一物理
DataSource 和工作线程；构造拒绝不同数据源，任一 SQL／完整性检查失败全部回滚，原唤醒回调只在外层提交后执行。
快照读取重验字节摘要、owner／绑定／幂等投影、实际材料摘要及与 Invocation 的请求关系，损坏不能降级为空或新受理。

- 新调用在写事务内重新按当前绑定及审核兼容链解析；预检后配置或实际选版变化返回 `AI_TOOL_PLAN_CHANGED`，不静默改用另一方案。
- 重放先读原快照，再重新取得当前宿主许可并按原 p1 引用复核治理；submissionDigest 比较原参数与请求上限，不比较后来配置或新版本。
- 同意图保留原 Invocation ID、acceptedAt、配置、版本及 deadline；不同意图返回 `AI_IDEMPOTENCY_CONFLICT`。
- 并发输家对最终胜出的实际快照再次执行当前宿主事务资格检查，不能仅检查自己的候选。
- 新增稳定绑定重放索引不替代原 owner＋capability.id＋key 围栏；不同绑定调用同一能力时使用相同键仍冲突。

`Invocation.contextSnapshotId` 保持生成上下文原义，TOOL 为 null；快照按 Invocation ID 一对一关联，不增加另一套执行状态／租约权威。

### 11.3 编码、部署与后续接线

新增根别名 `tool-execution-snapshot`，严格整数 schemaVersion=1；v1 只表达当前独立本地免费只读范围。 原
Invocation／Attempt／生成绑定与结果的编码不变；后续收费、子调用或审批须显式扩展并验证兼容，不能把当前空预算字段解释为任意模式。

新环境完整 DDL 已包含 `arte_ai_tool_execution_snapshot`；已有通用执行和 S1 工具表的环境显式执行
[arte-ai-tool-admission-migration.sql](scripts/arte-ai-tool-admission-migration.sql)。按 Invocation 外键及 owner＋稳定绑定＋key
唯一键约束。 表内 `snapshot` 字段沿用 S1 TOOL 存储命名；脚本仅复制到测试资源，运行时不自动执行。 本批只在隔离 H2
执行，未连接或部署配置中的业务数据库；其他环境部署前需停写、备份、核对先决表及 MySQL 8 的约束和锁语义。

本组件暂不自动装配，不新增 HTTP／配置开关或默认授权。当前 `InvocationDispatchWorker`／生成协调器仍仅处理 GENERATION， 后续应先完成
TOOL 路由、固定快照加载、当前宿主执行授权、FREE 派发准备及终态协调，再开放受理入口。
默认绑定、工作分类／容量、审批／业务核对、付费／远端工具、实际领域工具和前端仍待后续批次，TOOL 默认关闭。

### 11.4 验证证据

新增 **33 项全部通过**：`ToolAdmissionContractTest` 5 项及 `ToolAdmissionIntegrationTest` 28 项。 覆盖真实 MyBatis／隔离 H2
中的首次受理、Codec 往返／严格版本、相同键重放、不同意图冲突、兼容升级与改配置后原材料固定、
并发跨实例唯一受理、宿主当前许可与事务内撤销、owner／scope 越界、空授权拒绝、治理竞态、参数与完整收紧策略、
受理调度队列／预检／事务等待期限、过期历史回执、快照／DISPATCH 写入失败全回滚及提交后唤醒、损坏拒绝、跨 owner 隔离、跨绑定能力防重、
数据源不一致拒绝，以及新环境／增量 DDL 一致性。测试适配器若被调用会直接失败，受理不执行业务工具。

针对新测试及工厂／DDL 契约的 39 项全部通过，日志 `/tmp/arte-s203-targeted.log`。

JDK 21 Reactor 主代码／测试代码编译通过；最终串行兼容回归共 **658 项，654 项通过、4 项跳过、0 失败／错误，BUILD SUCCESS**。 本批
33 项包含在 658 项中，不重复累计。跳过项为缺少独立测试 Redis 的 ExecutionAssemblyTest 1 项及 InvocationHttpIntegrationTest
3 项；未借用业务 Redis。沿用 S2-01／02 的选择范围，显式排除既有 ProfileConfigurationTest 的 4 项， 其已复现的基线配置问题未声称修复。测试的
HTTP 仅使用本机受控服务，不访问外部模型。

执行命令（仓库根目录，JDK 21）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.tool.gateway.*Test,com.arte.ainew.tool.admission.*Test,com.arte.ainew.admission.*Test,!com.arte.ainew.admission.ProfileConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终日志 `/tmp/arte-s203-final-regression.log`。中间复验与编译重叠曾产生临时缺类错误，已单独串行重跑；最终结果以上述日志为准。
`git diff --check` 通过；未运行全仓／arte-app 全量、实际宿主资源授权、MySQL／Redis 多节点、压力测试、浏览器或生产验收。 H2
验证不能替代真实 MySQL 锁和部署验证，也不证明非功能性能目标达标。

## 12. S2-04：TOOL Worker 派发及完成

状态：完成内部本地免费只读工具的 Worker 执行链路；S2-05 查询／取消及进一步恢复、S2-06 分类容量／排队仍待后续。

### 12.1 组件及调用链

| 组件                                        | 本批职责                                                                                                    |
|---------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `application.tool.execution.ToolDispatcher` | 原快照／固定绑定及当前免费资格预检、FREE 原子准备、单次网关、结果持久化、租约及 Guard 下的终态提交          |
| `DefaultInvocationCoordinator`              | 按权威 Invocation.kind 路由 GENERATION／TOOL；原 submit 保持生成语义，TOOL 仍通过 ToolAdmissionService 受理 |
| `InvocationDispatchWorker`                  | 继续复用原领取、Outbox 续租、本机槽位和处理后 ACK，没有新增第二套任务权威或 Worker                          |
| `MybatisExecutionPersistence`               | 复用原 prepareDispatch／commitCompletion；增加 TOOL 结果引用的真实字节、类型及归属一致性校验                |
| `NewAiToolExecutionConfiguration`           | 三开关及显式宿主授权齐备时，装配内部受理 Store／Service 和 ToolDispatcher；与通用执行共用物理数据库         |

```text
ToolAdmissionService → 快照／Invocation／DISPATCH 提交
  → InvocationDispatchWorker → 权威 kind 路由
  → ToolDispatcher：原快照、原 p1、当前免费资格
  → prepareDispatch(FREE)：新 Attempt／耐久操作身份同事务提交
  → ToolGateway：当前资源权限／Schema／固定配置 → 领域只读适配
  → InvocationResult.Tool 字节保存
  → commitCompletion：Attempt／Invocation／结果引用／终态事件／EVENT Outbox 同事务
  → Worker ACK
```

派发不重新执行 follow-compatible，不替换已受理配置、Schema、工具版本或原 deadline。 宿主免费资格预检复用
ToolAdmissionAccess，核对同一 owner／主体类别、当前权限子集及必要 scopes；真正进入领域适配前另由 ToolExecutionAccess
和领域服务检查当前资源权限。预检不冻结授权，派发不执行新受理事务的 Permit 回调，也不创建第二份受理记录。
当前仅使用免费分支，不读取账户、预留、结算或模型计价，不伪造零金额预留。

### 12.2 结果与可靠完成

网关单次订阅，派发器再次约束 callId、只读副作用、输出 Schema 和完整信封 UTF-8 上限；不合规结果或网关意外异常保守为 UNKNOWN。
明确未进入业务的资格／治理／期限拒绝保存为已知失败／超时，未创建 Attempt 时通过 CompleteBeforeAttempt 提交，不伪造
ToolResult。 合法 FAILED／UNKNOWN 结果保留事实；retryable 不会自动触发新尝试，本批没有 TOOL 自动重试。

沿用 `InvocationResult.Tool`／`tool-result` v1，结果字节先写入原结果表。 完成事务核对真实行的
owner、Invocation、Attempt、类型／版本、字节摘要、partial、callId 及 outcome／error；拒绝错类型、跨调用引用和假成功。
Invocation、Attempt、结果引用、终态事件及 EVENT Outbox 一起提交。请求结果上限若连安全失败信封也无法容纳，仅保存 Invocation
错误事实， 不写超限结果，不宣称成功；这种失败可以没有 ResultRef。

结果写入、终态事务或租约故障向 Worker 传播，不 ACK，不能以 Publisher 完成代替可靠终态。 关键存储端口意外返回空 Publisher
同样视作基础设施故障，防止 ACCEPTED／RUNNING 工作项被误 ACK。 结果已保存但终态事务失败时可能留下未引用字节，本批不清理、不凭这些字节重新执行；进一步恢复在
S2-05 处理。

### 12.3 重复领取及租约

- FREE 准备只使用本次新提交的发送许可；并发重复领取由版本、领取 token 和数据库事务仲裁，不产生第二次业务调用。
- 已终态重投直接完成处理，ACK 故障后重领不重复写终态事件，也不重新调用工具。
- 活跃 Attempt 不创建下一次尝试；过期且可能执行的 Attempt 通过既有 stopExpired 保守收敛 UNKNOWN，不盲目重发。
- Worker 续租 Outbox，派发器续租 Attempt；每次写入重新读取最新 Guard，只有 VERSION_CONFLICT 有界重读，不重新订阅网关。
- 失租停止当前订阅，旧 fencing 不得提交；取消订阅不证明底层业务未执行，不伪造已知取消或零费用事实。

上述是本批派发防重及失败保守性，不表示 S2-05 的查询、取消、完整崩溃恢复或业务结果核对产品已完成。

### 12.4 装配、兼容及部署

同时开启 `arte.ai-new.enabled`、`arte.ai-new-tool.enabled`、`arte.ai-new-execution.enabled`，并由宿主明确提供
ToolAdmissionAccess、ToolExecutionAccess 后才装配 TOOL 受理与派发；无默认允许实现。
缺少任一端口时保留已有管理／生成能力；显式提供端口但缺必要网关或依赖则装配失败，不创建空派发器。 只启用 TOOL 不要求
ModelGateway、模型定义／连接、费率或预算；沿用现有 DATABASE 模式与宿主身份解析基础。 若没有任何派发器，通用执行装配失败，不能先受理不可执行的任务。

生成与 TOOL 共用一个通用协调器和 Worker。协调器统一使用四参数构造，ExecutionStore 始终必填，并按权威 kind
路由；生成派发器遇到非生成记录会拒绝处理， 不会把 TOOL 落为生成失败。自动消费仍由 worker-enabled 单独控制，默认不启动；构造零
SQL／发现／授权／业务调用，调度器关闭时释放。

本批没有新增 DDL、codec 字段／版本、Invocation 状态、HTTP 路由或配置项，未修改 TOOL 默认关闭配置。 S1 与 S2-03
既有部署脚本仍须按需停写、备份并显式部署；本批未连接、检查或修改配置中的业务数据库，也未注册真实领域工具或生产授权实现。
Worker 本机槽位仍为生成／工具共享；原生成限流保持，TOOL 分类领取、空间／提供者容量、过载与排队公平性由 S2-06 完成。
不能据此宣称多节点、容量隔离、性能目标或整个 S2 阶段通过验收。

### 12.5 验证证据

新增 **41 项全部通过**：

| 测试类                      | 数量 | 证据                                                                                                                                                                                                                                                                               |
|-----------------------------|------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| ToolDispatchIntegrationTest | 34   | 隔离 H2／真实 MyBatis、受理、网关及 Worker；结果／终态／事件／ACK、原版本与配置、并发重复领取、当前权限与治理、总期限、异常／空结果／Schema／越限输出、结果与终态回滚、空存储回执、ACK 故障、双租约续租／失租、缺快照、错 owner／scope／领取、结果引用完整性、生成节点不误处理工具 |
| ToolExecutionAssemblyTest   | 7    | 三开关、无默认授权、模型定义／网关全空的 TOOL 单独装配、生成兼容、缺依赖失败、单一 Worker／协调器、构造零 I/O、资源关闭及 Boot 顺序登记                                                                                                                                            |

针对性日志：`/tmp/arte-s204-targeted.log`。只读领域查询由受控测试适配器提供，不代表已注册生产业务工具或验证真实平台资源权限。

JDK 21 主代码及测试代码编译通过。最终兼容回归 **699 项，695 项通过、4 项跳过、0 失败／错误，BUILD SUCCESS**。 新增 41 项已包含在
699 项中，不重复累计。4 项跳过仍为缺少独立测试 Redis 的 ExecutionAssemblyTest 1 项与 InvocationHttpIntegrationTest 3
项，未借用业务 Redis；显式排除既有 ProfileConfigurationTest 的 4 项，沿用 S2-01 已复现的配置基线记录，未声称修复。
现有生成派发、收费、聊天受理、持久化和 S1／S2 工具回归均包含在本次选择范围；未调用外部模型。

执行命令（仓库根目录，JDK 21）：

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home \
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.tool.gateway.*Test,com.arte.ainew.tool.admission.*Test,com.arte.ainew.tool.execution.*Test,com.arte.ainew.admission.*Test,!com.arte.ainew.admission.ProfileConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终日志：`/tmp/arte-s204-final-regression.log`。`git diff --check` 通过。 未运行全仓／arte-app 全量、真实宿主权限和领域工具、真实
MySQL／Redis 多节点、分类容量压测、浏览器或生产验收。 临时 H2 与本机受控测试不证明生产约束、完整故障矩阵或性能目标达标；未进入
S2-05。

### 12.6 协调器构造收敛

移除两参数构造及 `ExecutionStore == null` 的生成兼容分支；统一四参数构造，强制提供 ExecutionStore， 始终读取权威
Invocation.kind 后选择派发器。生成单独装配仍可将 ToolDispatcher 置空；遇到 TOOL 时拒绝派发，保持原受理状态。 生成／HTTP
测试调用点及 TOOL 测试夹具同步调整，不再保留旧构造路径。

本次针对性回归选择
DispatchIntegrationTest、DispatchWorkerIntegrationTest、InvocationHttpIntegrationTest、ExecutionAssemblyTest 及
`com.arte.ainew.tool.execution.*Test`： **131 项，127 项通过、4 项 Redis 相关跳过、0 失败／错误，BUILD SUCCESS**。 JDK 21
主代码及测试代码编译通过，日志 `/tmp/arte-s204-coordinator-constructor.log`；本次未重跑上文 699 项兼容回归。

## 13. S2-05：查询、取消与本地恢复

状态：完成本地免费只读 TOOL 的通用查询接线、独立当前读取／控制资格、耐久停止传播及已保存结果恢复。 本批不实现公开 TOOL
提交、默认绑定、分类容量／公平排队、前端、写工具或外部业务 UNKNOWN 核对，不表示整个 S2 已完成。

### 13.1 查询与当前授权

- 状态、结果、最多 20 条快照、单页事件与 SSE 继续复用 `/ai-new/invocation`。读取沿原 ID，不新建 Invocation 或 Attempt。
- 增加 `ToolInvocationAccess`；通用 ai:read 后还要检查 TOOL 当前资格，缺少端口明确拒绝，不能通过通用查询绕过工具权限。
- 状态和事件检查本人 owner；批量快照隐藏无权项，结果字节返回前再次确认读取资格。暂存结果未被权威 Invocation 引用前不可对外读取。
- SSE 建连、通知补读和 10 秒撤权检查包含工具资格。单条连接只缓存不可变能力／归属，不缓存 Permit；关闭订阅不取消工具。
- 读取使用本次查询 deadline，允许读取原执行期限已过的历史。预算状态沿原事实，无预留显示 NOT_RESERVED，不虚构模型 Token
  或零金额费用预留。

公共平台 `PlatformToolInvocationAccess` 复用当前稳定身份、账户状态、空间成员／WORKSPACE_READ 与实时 RBAC。 READ 对应 aiTool:
list，CANCEL 对应 aiTool:invoke；两者独立，不从过期会话缓存取权限。FIXED 继续要求匹配当前稳定主体／Scope 的 grant， DATABASE
不要求空间 AI 仍启用，也不加载模型目录、费率或预算。 实际领域工具及其受理／资源执行权限仍由组合模块明确提供；本批没有注册生产工具或开放真实领域查询。

### 13.2 停止接口及事实

新增 `POST /ai-new/tool/cancelToolInvocation`，只接收严格白名单 `{scope, invocationId}` 与 Idempotency-Key；scope
的未知字段也拒绝。 本次创建 ai:read 上下文，按 TOOL 当前 CANCEL 资格提交，不能以此控制生成。缺少键为 400，失权为
403，归属不符／不存在为 404。 READ／CANCEL 不互相授予；只有取消资格的本人无需 aiTool:list 即可停止。

控制继续复用通用 requestControl。增加事务内授权回调：锁定本人执行后，重验并持有公共资格锁，随后保存命令、回执、取消标记及事件；
重放也先重验权限，授权失败全部回滚。取消端口未实现同事务门控时明确失败，不退回到先授权、后独立写事务。

无 Attempt 或已证明未派发时完成 CANCELLED；已可能执行时只记录停止意图。Worker 在准备后再读一次取消事实， 运行中每 200ms
串行读取，并向本机取消信号传播；即使自定义网关忽略信号，也会终止其订阅并保守保存 UNKNOWN。 该间隔是检测策略，不是生产取消
SLA；基础设施读取失败不伪造业务失败或 ACK。取消订阅不证明底层阻塞领域操作已停止。

HTTP ACCEPTED／202 只证明命令已提交，同键重放原回执；ALREADY_TERMINAL／200 不覆盖已成功或 UNKNOWN。
完成竞争按已知结果事实收敛，已保存成功不能因后来的取消意图变成 CANCELLED。 查询与 TOOL 停止端口不以新调用的 TOOL
开关作为访问前提；仍须保持现有通用执行装配可用。 本批未改变“至少有一种派发器”的启动约束，不能据此承诺无派发器的 TOOL
单独关闭部署可启动。

### 13.3 结果保存与恢复事务

`ExecutionResultStore.staged` 仅供内部恢复按原 owner／Invocation／Attempt／固定 resultKey 读取已存字节及真实引用；不授予浏览器可见性。
TOOL 新增结果字节前持有 Invocation 行锁，并验证当前活跃 Attempt 及数据库时钟下的租约；同键同内容的既存字节仍可重放，不能藉此再派发。

重领原 DISPATCH 后：

| 耐久事实 | 处理 |
|----------|------|
| 已终态 | 直接完成工作项，不重新调用、不重复终态事件 |
| 尚无 Attempt | 仍按原 deadline／快照走原预检及 FREE 准备，不分配新逻辑操作 |
| 活跃 Attempt 租约未过 | 不接管、不启动第二次尝试 |
| 过期 Attempt 且存在固定结果槽 | 校验原快照、callId、输出 Schema、字节限额、归属、摘要与 outcome，再提交恢复事务 |
| 无结果且确定 NOT_STARTED | 已知 INTERRUPTED；有取消事实时为 CANCELLED |
| 无结果且可能派发 | UNKNOWN，保留不确定事实，不调用网关或自动重试 |
| 结果损坏或不匹配 | 明确基础设施故障，保留未 ACK，不丢弃证据或伪造失败 |

新增内部 `ToolRecoveryStore.completeStoredToolResult`。在一个同库事务中锁 Invocation／Outbox／Attempt，
复核工作项领取、owner、版本、过期租约及原固定 tool-result 槽，再核对真实结果字节，推进 fencing 并提交 Attempt、Invocation、
结果引用、终态事件、EVENT Outbox 和证据引用。没有创建新 Attempt、租约派发许可、预算预留或业务调用。 事务故障整体回滚，重领仍沿原结果恢复；旧
Guard 不能覆盖新终态。

原 stopExpired 在同一 Invocation 锁下发现固定结果槽时拒绝丢弃证据；与新结果写入串行，避免“查无结果”后把并发成功丢为未知。
保存已发生的结果不重新申请新调用资格，原 deadline 到期、工具／绑定停用或执行权限撤回不阻断系统收尾；对用户返回仍须当前 READ
资格。 恢复保留原 deadline，不靠重计时获得执行资格。坏结果不会被公开，孤立字节的保留／清理策略仍按既有约束，未新增后台清理器。

这只是平台内已存结果的恢复，不是 §8.8 ToolOutcomeQueryAdapter、自动业务查询、人工证据审查或 ToolReconciliationCase。 缺少业务证据的
UNKNOWN 继续 UNKNOWN；免费没有预留也不能假定业务失败。本批没有复用生成账单核对来确认 TOOL。

### 13.4 部署与验证

没有新增 DDL、codec 类型／版本、状态、配置项或预算行为，TOOL 默认关闭保持不变。 未连接、修改或部署配置中的业务数据库；S1／S2-03
既有增量和 RBAC 定义仍按原部署清单显式执行。

新增测试覆盖见 ToolInvocationIntegrationTest 及 PlatformToolManagementAccessTest 中本批新增的 5 项：
查询归属／撤权／无默认授权、暂存不可见、标准 HTTP／严格参数、取消重放及同事务撤权、运行中停止、完成竞争、派发前后崩溃、 已保存结果恢复、原
deadline、停用后收尾、坏结果、事务回滚、旧 fencing、失租新字节拒绝及同内容重放。

JDK 21 Reactor 主代码与选定测试源码编译通过。串行兼容回归：arte-ai **728 项，724 通过、4 跳过、0 失败／错误**； 宿主
PlatformToolManagementAccessTest **26 项全部通过**，合计 **754 项，750 通过、4 跳过**。 日志
`/tmp/arte-s205-final-verification.log`。该次已包含本批 29 项工具专项及 5 项宿主新增测试，不重复累计。 随后将 TOOL
停止请求收紧为独立严格白名单，新增额外字段拒绝用例；最终 ToolInvocationIntegrationTest **30 项全部通过**， 日志
`/tmp/arte-s205-final-http-validation.log`。最终新增共 35 项（工具 30、宿主 5）；最后参数校验改动未再次全跑 754 项。

4 项跳过仍为缺少独立测试 Redis 的 ExecutionAssemblyTest 1 项与 InvocationHttpIntegrationTest 3 项，未借用业务 Redis。
继续显式排除 ProfileConfigurationTest 的 4 项，沿用 S2-01 已复现的配置基线说明，不声称修复。 首轮受限沙箱中的 55 项受控
HTTP 测试因本机端口绑定禁止失败，获本地监听权限后串行重跑通过；日志以最终两份为准。

最终兼容命令（仓库根目录，JAVA_HOME／PATH 指向 JDK 21）：

```bash
mvn -o -f backend/pom.xml -pl arte-app -am -Pworkspace-baseline \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=com.arte.ainew.contract.*Test,com.arte.ainew.persistence.*Test,com.arte.ainew.tool.schema.*Test,com.arte.ainew.tool.discovery.*Test,com.arte.ainew.tool.release.*Test,com.arte.ainew.tool.binding.*Test,com.arte.ainew.tool.management.*Test,com.arte.ainew.tool.gateway.*Test,com.arte.ainew.tool.admission.*Test,com.arte.ainew.tool.execution.*Test,com.arte.ainew.admission.*Test,!com.arte.ainew.admission.ProfileConfigurationTest,PlatformToolManagementAccessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最后严格 HTTP 参数校验复验：

```bash
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  -Dtest=ToolInvocationIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`git diff --check` 通过；未运行全仓／arte-app 全量测试、真实 MySQL／Redis 多节点、实际领域资源权限、生产工具、 性能压测、浏览器或生产验收。H2
和本机受控 HTTP 不证明生产数据库锁、完整故障矩阵或性能 SLA；未进入 S2-06。

## 14. S2-06：分类容量与排队

状态：完成本地免费只读 TOOL 的分类调度、耐久准入与排队收尾。默认关闭，业务数据库未部署；S2-07／08 页面、公开提交／默认绑定及
S2-09 集成验收当时尚未完成；本批后的状态见 §15。OUTCOME_CHECK 只有独占保留槽位，S3 前不创建或消费业务核对工作项。

### 实现职责与原子边界

| 组件                                                       | 职责                                                                                               |
|------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `DispatchPlan`／`OutboxMessage.WorkClass`                  | 服务器确定 GENERATION／TOOL_SHORT／TOOL_LONG；提供者、排队时刻及原期限与受理同事务固定             |
| `DispatchSchedulingStore`／`DispatchSchedulingPersistence` | 共用物理 DataSource 与真实事务，仲裁集群类别／空间／提供者许可、四维队列预留、空间轮转和同构执行池 |
| `SchedulingMapper`／Outbox 调度索引                        | 保存许可、队列计数、类别游标及池摘要；领取只读取有界调度元数据，不扫描或解码历史 Invocation 快照   |
| `InvocationDispatchWorker`                                 | 各类按本机空闲槽位领取；持续补充，不让长工具阻塞生成／短工具；排队到期扫描不占执行槽               |
| `MybatisExecutionPersistence`                              | Attempt 准备再次复核排队期限；开始、取消、终态和队列预留释放同事务；安全重试保留原排队期限         |

工具按固定发布定义的 `defaultPolicy.timeout` 分类；缩短本次 timeout 不会改变类别。提供者取固定工具的 providerId，生成取已固定连接的
providerId。客户端没有指定工作类别或提供者容量身份的入口。

准入先在同一短事务锁定调度权威，检查集群／类别／租户空间／租户主体四维计数，再预留并提交 Invocation、事件、Outbox
与工具快照；失败全部回滚，同键重放不二次占用。TOOL 首次准备或未执行终止释放预留；GENERATION 为安全重试保留预留至终态，因此其准入预留比实际等待数保守。
`queue_pending` 是实际排队标记，`queue_reserved` 是准入预留标记，不可互换。

分类领取先仲裁可用容量，再按类别内空间轮转领取；饱和空间／提供者可跳过，在同一空间继续寻找其他可服务提供者。游标跨实例保存，每轮每空间最多一条。领取、容量许可与
Outbox token 同事务，续租同步延期，ACK／defer 只释放原 token。旧领取过期后，仅在锁定原消息并重领时删除旧许可，避免许可与
Outbox 反向锁序。原模型并发／频率和预算检查继续生效，调度许可不替代它们。

排队到期收尾独立运行，未开始任务写入 `DISPATCH_QUEUE_TIMEOUT` 的已知无副作用终态，释放队列预留和会话门闩；准备事务再检查
deadline，避免扫描竞争。已开始或可能已经产生结果的任务仍按原执行／恢复证据收敛，不用排队超时抹掉执行事实。

### 开关、容量及故障行为

全部配置位于 `arte.ai-new-execution.scheduling`，示例见 `backend/profile/app.properties.example`，已接入配置过滤与严格属性绑定。

| 配置                                                                                     | 默认值／含义                                                                                              |
|------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `enabled`                                                                                | false；生产装配未启用时拒绝新增 TOOL 受理；原生成路径保留                                                 |
| `slots.generation`／`tool-short`／`tool-long`／`outcome-check`                           | 2／2／1／1，独占且不借用；启用时原 `arte.ai-new-execution.concurrency` 必须等于槽位总数，默认方案需设为 6 |
| `generation-concurrency`／`short-concurrency`／`long-concurrency`／`outcome-concurrency` | 16／16／8／4，数据库跨节点类别上限                                                                        |
| `workspace-concurrency`／`provider-concurrency`                                          | 2／8，相应空间／提供者跨类别共享                                                                          |
| `max-queued`、四类 `*-queued`                                                            | 1000；300／500／100／100；计数是准入预留                                                                  |
| `workspace-queued`／`subject-queued`                                                     | 100／20；按租户与空间／主体组合身份隔离                                                                   |
| `short-threshold`                                                                        | 10s，按工具发布契约 timeout 分类                                                                          |
| `short-queue-timeout`／`long-queue-timeout`                                              | 10s／30s，均不超过原 Invocation deadline                                                                  |
| `tool-manifest`                                                                          | 默认空；填写经审查的健康 LocalToolDiscovery 快照 SHA-256                                                  |

队列满为 `AI_DISPATCH_QUEUE_FULL`（205056，HTTP 429，Retry-After: 5）；受理队列权威不可用、池配置／清单不一致或缺失受信方案为
`AI_DISPATCH_UNAVAILABLE`（305003，HTTP 503），不降级无限执行。消费扫描失败保留耐久工作项并记录稳定错误类型，后续轮询恢复。

所有工具节点须完成健康本地发现，并与配置中的清单摘要一致。数据库固定调度配置及清单摘要，不一致的节点不得加入执行池。Worker 的
`toolExecutionReady()` 和日志反映执行池资格，未新增 Kubernetes 或全应用 HTTP
就绪探针。清单包含契约、Schema、目标和适配器版本引用，不能证明镜像或传递依赖字节一致，部署仍须统一代码镜像。

### 显式部署与配置变更

新环境使用更新后的 `scripts/arte-ai-new-ddl-mysql.sql`。已有环境在前序增量后执行
`scripts/arte-ai-dispatch-scheduling-migration.sql`，新增 Outbox 九个元数据列、三个索引及四张调度表。运行时代码不执行
DDL。脚本不使用 IF NOT EXISTS 掩盖结构漂移；客户端须遇错停止，禁止带 `--force` 执行。

1. 停止新受理，停止全部旧消费者；完成或核对已有工作，确认未确认的 DISPATCH 数量为零。备份并核对结构。增量脚本带未排空拒绝检查，不猜测或回填旧工具分类。
2. 执行增量，统一升级所有消费者并配置相同策略／清单，设置 concurrency 等于槽位总数；显式启用 scheduling，原
   TOOL／执行开关及授权条件仍须满足。
3. 完成健康本地发现，核对清单、Worker 执行池资格及受控试调用后恢复新受理。

运行中不得只关闭分类开关或混跑新旧消费者。改变槽位、限额或清单前同样停受理并排空工作、预留和许可；核实无活动记录后，由运维按审计流程重置
`arte_ai_dispatch_pool` 的固定配置记录再统一启动。没有运行时自动重置或绕过配置漂移的降级路径。

### 验证与边界

新增 `ToolSchedulingIntegrationTest` 覆盖：类别及固定提供者、并发准入／四维队列上限、回滚／重放、跨实例许可、续租／延后／旧
token、空间轮转与饱和提供者跳过、清单／池配置失败关闭、满槽排队到期与准备竞争、生成安全重试收尾、真实免费工具
Worker、自动长工具运行时生成／短工具隔离、严格配置绑定、增量未排空拒绝与新建结构兼容。

JDK 21 选定回归已通过：arte-ai 共 766 项，762 项通过、4 项 Redis 测试跳过；arte-app 的 `PlatformToolManagementAccessTest` 26
项全部通过。合计 792 项，788 项通过、4 项跳过，零失败／错误；本批 `ToolSchedulingIntegrationTest` 的 37 项全部通过。

回归命令沿用 §13 的 JDK 21 选定兼容命令，包含本批测试与 `PlatformToolManagementAccessTest`。继续排除已记录基线问题的
`ProfileConfigurationTest` 四项；Redis 集成测试没有连接业务 Redis，跳过项不计入通过。受控本机 HTTP 使用允许本机端口绑定的测试环境。最终
`git diff --check` 通过。

未运行全仓测试、真实 MySQL／Redis、多节点部署、浏览器或 W1—W4 生产压测。H2 与受控 Worker 只证明隔离环境中的事务／行为，不证明生产锁性能或
SLA。调度数据库使用短事务统一仲裁，其生产吞吐仍须实测；本批不宣称“高性能验收完成”。

## 15. S2-07：工具目录与绑定页面

状态：完成工具目录／详情、显式准备本人默认绑定、配置编辑／启停、版本升级预览／确认和历史。页面 `/AI/Tools`
，复用当前真实工作空间、现有身份／RBAC、ResultContext 与 Token／语言请求体系。S2-08 为调用预览／提交／状态／结果页面，S2-09
为只读阶段集成验收；本批没有公开 invoke API、工具聊天或预算管理页面。

### 页面与使用流程

- `frontend/src/pages/AI/Tools/index.tsx` 只作 Umi 页面入口；业务在 `features/aitool`，服务和类型在
  `services/arte-ai/tool.ts`。Tools 辅助文件及验收材料被约定式路由排除。
- 目录按名称／用途、标签、提供者和生命周期状态在服务器筛选，游标绑定过滤条件。分页没有
  total；可见空页仍继续，游标不推进明确报错。详情展示用途、风险与可用性，版本／限制／Schema 按需展开。
- 点击“准备默认配置”显式提交 `{}`，工具非敏感默认配置完整时直接准备个人配置；422 时展开业务字段。标量按 Schema
  填写，复杂对象／数组用受控 JSON 编辑；后端仍独立完成完整 Schema 校验。
- 默认身份按真实 owner＋稳定 toolId 唯一。并发标签页及不同键不创建第二份默认修订；已有配置、版本、改名与停用状态不被“准备”覆盖。命名绑定仍可并存，既有
  saveBinding 新建接口保留。
- 本人配置可以改名／完整替换配置、收紧限制、启停、选择
  PINNED／FOLLOW_COMPATIBLE；修改基准版本必须先预览并确认，不兼容升级须明确勾选。预览显示有效限制，内容变化清除旧预览与确认，准备／预览均不执行工具。
- 写命令发出前保存原输入与幂等键。重复点击只发送一次；网络／5xx／异常回执保留原命令，返回原空间或刷新后用同键确认。HTTP／业务拒绝与“已保存”分开处理，409
  不自动覆盖治理版本或草稿。
- 缓存、未确认命令按账号／租户／空间隔离；切换或卸载中止读取与浏览器等待，迟到响应不进入新范围，卸载不提交业务取消。未保存输入关闭详情／切换空间／刷新离开前提示；账号会话清除后隐藏原内容。恢复记录损坏或无法写入时不发送新的操作。

### 默认绑定与兼容

新增 `prepareDefaultBinding`，管理入口共 17 个；沿用 LIST／WRITE 当前资格，在原写事务内再次校验
requiredScopes。ENSURE_DEFAULT 由可信管理用例构造，固定所选发布版本与
PINNED；事务按默认身份及命令加锁，指针／修订／回执／审计原子提交。已有默认只返回原指针，不新增修订；同键回执可能是历史指针，页面随后读取当前绑定。

没有新增表或 DDL。ENSURE_DEFAULT 审计回执单独使用 `tool-binding-receipt` v2，旧 SAVE／SET_ENABLED／UPGRADE 保持 v1；新 codec
严格校验版本与动作，保留历史回执解码。开放新命令前须统一升级管理读写节点；回退须保留 v2 读能力，不能删除审计或只修改
schemaVersion。

Schema／配置响应额外提供精确的 documentJson／configurationJson，保留原对象字段兼容客户端。浏览器用受限 JSON 值树保存数字词法，不经
double 往返，更新其他字段同样保留精确小数、大整数和显式 null。写入嵌入已校验的原文对象，其他字段 JSON
转义；版本使用十进制字符串。常见凭据字段在本地保存前拒绝，后端继续独立检查。

只读 previewBindingUpgrade 允许内容无变化时查看有效交集；相同基准按已有绑定生命周期计算，变更基准按新绑定计算。实际保存仍拒绝
NO_CHANGE；生成、受理、执行和计费路径不因此放宽。

### 菜单与部署

交付 `backend/arte-app/scripts/arte-ai-tool-menu-dml-mysql.sql`，新环境 RBAC DML 同步包含。只登记 `aiTool` → `/AI/Tools`
菜单，不自动授予角色／用户、不覆盖已有同码／同路径定义（包括停用状态）。部署时先核对冲突，使用既有 RBAC 管理显式授予菜单及
aiTool:list／invoke；后端仍复核真实空间与 AI 资格，admin 不绕过它们。

本批没有连接业务数据库、执行菜单 DML、启用 TOOL 或分类调度。前序 DDL、授权和健康本地目录仍须按各批次步骤准备；页面可编译和接口可测试不代表生产菜单已可见或
S2 已验收。

### 验证与复现

浏览器通过 `scripts/tool-s207-browser` 的本机页面和 `ToolBrowserAcceptance#browserBridge` 验收：固定测试身份／空间，工具
HTTP 进入真实 MVC／MyBatis／临时 H2；不连接业务库，不执行工具。此桥不进入默认 `*Test` 回归，必须显式运行，绑定仅 127.0.0.1，最长
15 分钟，停止文件为 `backend/arte-ai/target/tool-browser-stop`。

```bash
# JDK 21；先启动隔离桥，显示就绪后再启动前端（两个终端）
mvn -o -f backend/pom.xml -pl arte-ai -am -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=org.apache.logging.slf4j.SLF4JServiceProvider \
  '-Dtest=ToolBrowserAcceptance#browserBridge' -Dsurefire.failIfNoSpecifiedTests=false test
cd frontend && node scripts/tool-s207-browser/server.mjs
# 浏览器访问 http://127.0.0.1:5217；验收后 touch 后端 stop 文件并停止前端进程。
```

已走查桌面及 390px
窄屏：目录→详情→默认准备→改名／精确数值保存回读→有效限制预览→停用。截图和范围见 [页面验收](../../frontend/src/pages/AI/Tools/ACCEPTANCE.md)
。宿主登录、生产 RBAC 菜单授予、真实 MySQL／Redis、多节点、生产工具和性能仍未验收。

最终选定回归：arte-ai 775 项（771 通过、4 项 Redis 跳过），平台授权 26 项全部通过，后端共 801 项／797 通过／4
跳过，零失败／错误。继续排除已有基线问题的 ProfileConfigurationTest 四项；未运行全后端仓库测试。新增 9 项真实 HTTP／H2
验证默认准备、当前授权、防重、精确数值、过滤及审计回滚。

前端选定范围（工具、arte-ai 服务、工作空间）143 项通过，其中本批 31 项；组件 Biome lint 与差异检查通过。全仓 tsc
存在未修改的编辑器／旧页面等错误，本批文件无类型诊断。完整 Umi build 本次等待后无后续构建输出，已停止，不列为通过；已核对唯一
Tools 约定式路由及本批页面的隔离编译／浏览器行为。没有将上述检查解释成生产验收完成。

## 16. S2-08：调用预览、提交与结果页面

状态：本批功能及隔离闭环完成；S2-09、业务环境上线与阶段整体验收未完成。 本批不增加 DDL、持久化 codec 布局／版本、费用账户、审批状态或默认授权；保留
S2-03 ToolExecutionSnapshot v1 读取兼容。 TOOL／分类调度默认值保持关闭，不执行业务数据库脚本。

### 16.1 后端方案确认与可靠提交

- ToolBindingStore／ToolBindingService 增加 inspect：只读计算当前绑定与兼容版本选择，并复核本节点实现；使用普通只读事务查询绑定／修订／发布，不取得
  FOR UPDATE 写锁，不调用 resolve 写解析材料或锁行。
- ToolAdmissionService.preview 与 submit 复用有效策略交集、Schema／参数／配置校验及当前免费只读资格检查。预览只构建临时内部材料，不执行工具，
  不写固定解析、Invocation、Attempt、Outbox 或预算。预览不占队列，不承诺后续准入成功。
- ToolAdmissionDigests.plan 固定原始意图、实际绑定／版本／完整契约／配置／策略／权限上限、Schema 校验和与 FREE 模式； 临时执行
  ID、trace、callId、绝对时间和授权引用不参与摘要。
- 新提交必须确认预览摘要；改变参数／请求限制或绑定实际方案返回 AI_TOOL_PLAN_CHANGED。受理事务复核当前材料、资格与容量，
  固定解析随真正受理一起保存。并发同键获胜材料也校验摘要。同键历史重放固定原意图／原计划／原 ID，不因后续配置或兼容发布漂移。
- NewAiToolSubmissionController 提供 previewInvocation／invokeTool；严格白名单只接收 scope、input 和提交摘要，键来自请求头。
  通过现有身份／空间只读资格快照创建上下文，includeConfiguration=false，不依赖模型、费率或预算。仅三开关同时开启才注册，
  缺少可信受理服务返回 503；没有默认允许实现。
- 公开响应显式投影有效版本、参数／配置原文、实际 inputSchema、执行上限和 FREE 费用说明；不暴露执行目标或临时权限。 通用
  InvocationResultResponse 为 TOOL 追加 outputJson 标准 JSON 原文，保留既有结果信封与 Java 三参构造。 UI 不把大整数／小数转成
  JS number，不把引用或模型内容作为 HTML 执行。

### 16.2 前端入口与恢复

`/AI/Tools` 增加“工具调用”页签及本人配置的调用按钮，复用当前真实账号、工作空间、Token／语言传输及既有查询／停止入口。
常用标量参数直接填写，复杂参数按需展开完整 JSON；JSON 编辑保留原数字、拒绝重复键。参数校验与配置敏感键检查按用途区分，
参数中的合法业务字段 token 不被当作配置凭据一律拒绝；服务端仍独立校验 Schema 与资源权限。

预览展示实际工具版本、绑定修订、参数、有效配置、执行限制与免费说明。任何输入变化撤销确认；配置修订刷新重新读取 Schema， 实际
follow-compatible 的输入 Schema 随预览返回。确认前保存原请求／原幂等键，重复点击单飞，未持久化恢复记录时不发送。

每账号／空间最多保留一条当前命令。未明确回执时刷新不自动重发，由用户原键确认；恢复时被撤权不删除原记录或换键执行。 已受理只按原
ID 查询。正常单飞每秒轮询，失败指数退避到 15 秒，页面隐藏暂停；终态和 401／403／404 后停止自动读，
可手动重新读取。停止先保存控制键，重复确认复用原键，202 只显示“停止请求已受理”，仍跟踪权威终态。

结果展示执行事实、预算 NOT_RESERVED、错误阶段／副作用／追踪 ID、结构化 JSON、来源／产物引用；不把来源当成自动可访问链接。 UNKNOWN
明确标记待核对，不自动重发或作为安全重试依据；明确结束查看后保留原编号并清空输入，新操作仍须预览与确认。 本浏览器会话按账号／空间最多保留最近
20 个编号，可只读恢复；它不替代服务端执行历史或审计。 未发送草稿仍采用现有离开确认；切空间不撤销服务器执行，返回原空间可恢复。存储损坏或未能保存恢复记录时阻止新调用。

### 16.3 验证证据

- 后端选定兼容回归（§13 命令、JDK 21）：arte-ai **785 项，781 通过／4 项 Redis 跳过**，arte-app
  PlatformToolManagementAccessTest **26 项通过**。沙箱首轮本机 HTTP 测试因 Socket Operation not permitted 失败，
  使用本机绑定能力重跑通过；不计为功能回归失败。
- 最终补齐 inputSchema 投影和精确 outputJson
  后，ToolInvocationPreviewTest／ToolSubmissionHttpTest／ToolSubmissionExecutionTest **13 项全部通过**。连同最终受影响的
  ToolBindingStoreTest／ToolReleaseTest／ToolDispatchIntegrationTest， 定向回归共 **119 项全部通过**
  （与上述兼容集大量重叠，不重复累计）。覆盖预览零业务／锁行写入、并发管理写锁下预览不等待、
  请求期限覆盖当前身份解析、临时身份不影响摘要、过期计划、原键固定材料、撤权、 严格请求及缺端口失败关闭，以及公开预览→受理→真实
  Worker→结果→同键重放仅执行一次，免费调用零 reservation。
- 前端 features/aitool、services/arte-ai、features/workspace **157 项通过**。覆盖重复点击、精确原文、预览失效、网络／刷新恢复、
  当前撤权、停止回执、UNKNOWN 显式结束查看与保留原编号、隐藏页面不轮询、只读历史查询、损坏存储及账号／空间隔离。
- 真实浏览器：显式 ToolRunBrowserAcceptance#browserBridge＋frontend/scripts/tool-s208-browser。固定测试身份、真实
  MVC／H2／Worker， 默认桌面与 390px 均完成预览／确认布局走查；实际提交、结果和刷新保持同一 ID，桥关闭输出
  invocations=1、reservations=0。 截图和复现范围见前端 [验收记录](../../frontend/src/pages/AI/Tools/ACCEPTANCE.md)
  。没有访问业务数据库、业务 Redis 或模型。
- 本批页面／组件 Biome lint 与 git diff --check；全仓 tsc 仍有既有未修改页面／编辑器／搜索验收脚本诊断，本批文件无类型诊断。
  本批未重跑既有阻塞的完整 Umi 构建，隔离 Vite 页面编译与实际浏览器验收不等于生产构建通过。

### 16.4 生产开放前提与下一批

本节为 S2-08 完成时的记录，后续接线与验收状态见 §17。当时 arte-app 已提供管理和历史读取／停止的当前平台授权，但尚无生产
ToolAdmissionAccess／ToolExecutionAccess 或真实
LocalToolRegistry.Registration。 本批保留该显式接入边界，不登记测试适配器、不以只读元数据或管理权限代替免费／资源授权。三开关开启但缺受理服务时，新接口返回
503。 宿主实现必须检查现有稳定身份、当前账号／租户／空间、AI 使用、aiTool:invoke、实际资源及受信计费策略；提交与重放在同库原事务复核，
执行边界再检查当前资格。实际业务适配仍须使用原领域授权。

S2-09 才进行具体业务库／Redis、多节点、故障矩阵、性能与首次真实使用验收，并逐项记录生产工具及宿主授权缺口；不能因页面与 H2
用例通过即宣布开放。 审批、写工具、远端、付费工具、工具聊天及自动业务结果核对均未进入本批。

## 17. S2-09：正式宿主接线与本机集成验收

状态：按本轮确认完成本机集成及性能基线；正式参考环境双节点、规定数据规模、30 分钟 W1—W4／PF-01—08 保留待验。S2
生产开放门槛不变。本批首次使用和配置步骤集中在 [使用与验收](TOOL_ACCEPTANCE.md)。

### 17.1 正式实现与宿主授权

- arte-app 新增 `PlatformToolExecutionConfiguration`，按现有包路径显式装配 `LocalToolRegistry`、`LocalTextTools` 和同时实现
  ToolAdmissionAccess／ToolExecutionAccess 的 `PlatformToolExecutionAccess`。修复开启 TOOL 后缺少 LocalToolRegistry
  的启动缺口，不注册空目录或默认放行端口。
- 正式工具 `text.countCodePoints@v1` 按 Unicode 码点计数，输入 text，输出 codePoints，配置必须空对象。仅处理本次文本，无
  Secret／网络／文件／模型／业务资源；5 秒／1 次尝试／受控字节上限。完整契约与实际适配器一起登记，免费策略比较完整固定版本及有效配置，不按名称、READ_ONLY
  或空预算推断。
- 受理、同键重放、Worker 和实际网关调用均使用现有当前身份／空间／RBAC／AI 资格。抽取既有管理门控供可信执行复用，提交／重放仍在原物理写事务最终校验。
  `RbacIdentityProvider.findCurrentExecutionPrincipal` 锁定既有映射与账户，核对当前名称及账户生命周期，不构造 JWT
  或按任务名称新增身份。
- 明确的账户／空间资格丧失转换为当前授权拒绝，在调用算法前失败收尾；AUTHORITY_UNAVAILABLE、SQL 等基础设施错误保留原语义，不能伪装成撤权。既有管理
  Session、READ 历史和停止授权规则保持。
- 明确 FREE 的调用保持 NOT_RESERVED、零费用账户／reservation、不选聊天模型；未新增第二套预算，也未实现付费工具或 Run 编排。

### 17.2 启动发现和部署清单

本节保留 S2-09 时的 5 秒 v1 与历史清单；当前开发期 30 秒 v1 及新清单见 §26。

新增 `arte.ai-new-tool.discovery.refresh-on-startup`，共享和示例默认 false。显式启用后 `ToolStartupDiscovery` 在 Worker
启动前完整发现，30 秒总等待上限，失败拒绝启动；仅登记不可变部署事实，不执行 DDL、不自动发布／建绑／授予角色，不伪造管理员会话或管理审计回执。构造发现服务仍零
SQL，手动管理刷新仍有独立当前授权／审计。

`LocalToolDiscovery.manifest(batch)` 提取原有摘要算法，按提供者／Schema／工具固定排序，忽略来源顺序和加载时间；生产文本清单可在启动前计算，不需要先起服务取值再重启。当前唯一文本工具清单：

```properties
arte.ai-new-execution.scheduling.tool-manifest=331756c89f707d42a1142275e1be4d4711b6ef76ef6ac7df4b996ce463cd6569
```

Worker 仍要求本节点健康发现且与固定池清单匹配；已知摘要不授予健康或执行资格。变更清单和分类策略仍须停写排空、统一升级并按
§14 处理固定 pool 记录，没有自动清库／忽略漂移的路径。当前被忽略的本机 app.properties 已填入上值和启动发现开关；共享配置仍保持关闭。本批没有新表或
codec 布局变化。

### 17.3 MySQL 实测发现的锁修复

独立 MySQL 8.4 双 Worker 同时领取，复现 `SystemMapper.ensureLock` 使用 INSERT IGNORE 后同时 FOR UPDATE 的共享锁升级死锁，InnoDB
最近死锁记录明确显示两个事务互持相同主键 S 锁并等待 X 锁。改为 `INSERT … ON DUPLICATE KEY UPDATE lock_key=lock_key`
，在已存在锁行时直接取得排他锁，然后沿用原锁定读取和事务顺序。没有用业务自动重试掩盖重复派发；修复后 MySQL 双 Worker
完成一次逻辑调用、一次 Attempt、零预算预留。

### 17.4 验证结果和范围

- `PlatformToolExecutionIntegrationTest` 7 项：正式 Spring／当前平台权限／MVC／真实工具；发现→显式发布→本人默认准备→预览→可靠提交→双
  Worker→持久结果；同键相同编号、撤权拒绝新提交及重放、AI 关闭后历史可读、参数／他人空间拒绝、Unicode／空文本／大输入及非法输入、关闭
  Spring 后新节点发现并恢复原调用、重复取消零 Attempt、账户生命周期变化派发前拒绝。
- 独立 MySQL 8.4.11：上述 7 项和 `ToolMySqlMigrationAcceptanceTest` 1 项共 8 项全部通过。新建全量 DDL 正常；在空随机测试基线上撤去新增结构后按
  S1-04→06→07→08→S2-03→06 原始脚本恢复，11 张 TOOL 表、4 张调度表、Outbox 新列／索引及外键与全量一致。不是带历史数据或所有旧版本组合升级验收。
- 显式 `ToolLocalBaselineTest` 1 项通过：首次单列、预热 10 次、测量 50 次串行真实 MVC／平台授权／SQL。61 次调用均一次
  Attempt，零 reservation，队列预留归零。受理 P95 231.15ms、查询 P95 150.21ms、端到端 P95
  992.79ms；完整环境、冷样本、P50／95／99、测量边界见 [基线表](TOOL_ACCEPTANCE.md#本机性能基线)。端到端主动推进
  Worker，不含生产轮询间隔，不判定 PF 达标。
- 实际浏览器：`ToolPlatformBrowserAcceptance#browserBridge` 与 `frontend/scripts/tool-s209-browser`，正式组件／真实工具／当前平台授权／临时
  MySQL，固定测试登录会话。目录→详情→默认准备→调用→预览→确认→SUCCEEDED／NOT_RESERVED，A😀中输出 3；刷新恢复原编号
  bb588219-dafb-47ac-81d8-f0e7ea530d57；390px 的预览与结果可用。关闭统计
  invocations=1、attempts=1、reservations=0，窄屏再次预览不执行。桥和 Vite
  已关闭，证据见 [页面验收](../../frontend/src/pages/AI/Tools/ACCEPTANCE.md)。没有以固定测试会话替代生产 JWT／菜单验收。
- 前端既有相关范围 17 个文件／157 项全部通过；新验收桥 Biome check 通过，git diff --check 通过。产品 UI 未修改，未重跑已有基线问题的全仓
  tsc 或完整 Umi 生产构建。

最终后端兼容回归：arte-ai **789 项通过**，arte-app **69 项通过**（平台管理授权 26、正式执行 7、身份／会话／登录 36），合计 **858
项，零失败／错误／跳过**。其中原先跳过的 4 项 Redis 测试本次使用独立 Redis 通过；MySQL 8 项、显式基线及浏览器桥另外列环境证据，不与重叠回归重复累计。原
GENERATION／预算／事件和平台身份权限均在选定范围内。没有运行全仓测试、连接业务库／Redis、修改真实用户角色或调用外部模型。

最终日志：`/tmp/arte-s209-regression-final.log`、`/tmp/arte-s209-mysql-verified.log`、`/tmp/arte-s209-mysql-final.log`
（含基线）、`/tmp/arte-s209-browser.log`、`/tmp/arte-s209-frontend.log`。

### 17.5 复现与上线边界

JDK 21，沿用 §13 兼容命令，显式排除已知基线 ProfileConfigurationTest 四项和 `ToolRunBrowserAcceptanceTest` 页面桥，增加
PlatformToolExecutionIntegrationTest、RbacIdentityProviderTest、RbacSessionIdentityTest、RbacLoginIdentityTest；本轮使用独立
Redis 的 loopback 映射，不再跳过 4 项 Redis 测试。默认测试集不启动
ToolPlatformBrowserAcceptance／ToolLocalBaseline／ToolMySqlMigrationAcceptance，须按使用文档显式指定。

本轮仅创建并清理独立容器及随机 platform_test_* 库，未连接配置中的业务 MySQL／Redis。实际部署仍须备份、按现有版本应用脚本、显式授予菜单和操作权限、启用真实空间
AI、统一节点配置并进行真实登录试用；不能以测试容器已运行推断业务环境已准备。正式参考环境持续负载、双应用进程／跨主机故障、生产构建及恢复／回退演练继续待验，禁止将
S2-09 本机交付写成 S2 生产性能验收完成。

## 18. 受理超时、重复 SQL 与页面提示修正（2026-10-19）

### 18.1 实现

- 平台 BINDING_WRITE 门控已在当前事务检查 WORKSPACE_AI_USE；复用这一结论，消除门控回调中相同资格的第二轮锁读。
  入口与最终受理事务仍分别复核当前账户生命周期、RBAC、空间、AI 资格；没有跨事务授权缓存。
- 最终受理事务复用绑定解析时已核验的目录材料进行 Schema 完整性比较，避免再次读取相同工具、提供者及 Schema。
  发布／绑定当前状态的锁定、契约摘要和快照完整性检查保留。
- 通用执行、TOOL 受理及全局调度互斥使用原有 upsert 已取得的排他记录锁，省去紧随其后的重复 SELECT FOR UPDATE。 新 TOOL
  把全局调度仲裁后移至原子受理末段；容量不足仍回滚 Invocation、索引、快照、事件和 Outbox，GENERATION 受理顺序保持。
- 每次 JDBC 订阅独立持有最终信号：尚未开始的已取消任务跳过，已开始事务不因 HTTP 取消被中断，继续最终期限检查并提交／回滚。
  预期迟到期限／取消以 DEBUG 记录，意外迟到失败以 WARN 记录调用编号、traceId 及类型；不使用全局 Hooks 吞掉异常，不打印输入或异常原文。
- 页面将 HTTP 504／205005 明确显示为受理超时，并保留原正文／原键；旧 HTTP 409／205005 同样可确认原调用。 预览／查询只提示请求超时，刷新不自动提交。

### 18.2 验证与边界

- H2／平台选定回归 **146 项通过，零失败／错误／跳过**：通用执行持久化 52、TOOL 受理 30、提交执行 1、分类调度 37、平台管理资格 26。
  新增两项模拟写入之后 HTTP 先超时、事务随后分别期限耗尽或意外失败；均完整回滚、零唤醒、无 JDBC 中断及 dropped error。
- 一次性 MySQL 8.4：正式执行集成 7 项及 SQL 往返验证 1 项共 **8 项通过**；包含双 Worker、同键重放、撤权、取消及恢复。 当前空间政策
  FOR UPDATE 读取为入口和最终事务各一次，共 2 次。首次受理观测到 105 条涉及 arte_* 业务表的 SQL，
  含新调度池及计数器创建，排除会话设置、事务控制及数据库时钟语句；不是与业务日志 sqlCount 的相同统计口径。
  不据此声称生产延迟或并发指标达标；显式复现 `ToolLocalBaselineTest#admissionSqlRoundTrips`，输出
  target/tool-admission-sql-baseline.json。
- 前端相关 **7 文件／34 项通过**，包括超时提示、刷新后保留输入／幂等键和原调用确认；修改文件 Biome lint、git diff --check 通过。
  本批未重跑浏览器、全仓测试或完整生产构建。

日志：`/tmp/arte-tool-admission-regression.log`、`/tmp/arte-tool-admission-mysql.log`、
`/tmp/arte-tool-admission-ui-all.log`。 独立容器已清理，未连接业务数据库、改角色、执行 DDL 升级或重启当前业务应用。无新配置、Schema
或清单变更；后端重新构建重启、前端更新后， 须使用新 traceId 验证业务环境的 SQL 及锁等待是否仍超期。

## 19. 未确认提交的退出与恢复（2026-10-19）

原页面仅允许明确终态调用结束查看，没有回执时反复确认超时会永久占住当前入口。本次增加“暂存并开始新调用”，
明确说明原调用可能已受理、暂存不取消它、相同参数的新调用可能重复执行。确认后先保存原正文／原键，再释放当前操作槽位；
新调用仍须独立预览、确认并生成新键，不能把暂存当作自动重试或明确失败。

待确认列表按账号／租户／空间隔离，保存在当前浏览器 sessionStorage，最多 20 条，不静默淘汰；刷新保留，关闭浏览器会话后不保证保留。
恢复仅展示原提交，不自动发送；用户点击确认时复用原输入和原幂等键。取得并保存可靠回执后才从列表移除。
暂存写入失败、当前槽位清理失败、损坏记录或达到上限均保留原命令，不丢弃后继续发送。兼容旧版单条恢复记录，无需清理已有浏览器存储。

前端相关 7 个文件／40 项通过，含连续超时→暂存→刷新→新键独立提交→恢复原键、存储失败、上限、账号／空间隔离和页面完整交互。 修改的四个
TS／TSX 文件 Biome lint 和 git diff --check 通过。隔离浏览器以正式 ToolRunPanel 和模拟 504／成功响应验证同一路径，
原调用恢复后没有自动重发，证据见 [页面验收](../../frontend/src/pages/AI/Tools/ACCEPTANCE.md)。本次未运行后端或完整生产构建，未连接业务服务或数据库。
只需更新前端；当前业务环境反复超时的 SQL／锁延迟仍需分别定位，暂存不增加工具期限或改变服务端执行状态。

## 20. P01 分阶段观测与性能基线（2026-10-19）

- 在既有 RequestObservation Reactor Hook、ObservedScheduler 和 Druid 实际 JDBC 执行点中接入有界阶段上下文； TOOL 固定阶段按
  invocationId／traceId／nodeId 关联，包含单调耗时、UTC 起止、剩余期限、SQL 开始／完成／在途、错误及连接 ID。 父阶段包含子阶段，不能相加；未捕获
  JDBC 事件单独标记。FOR UPDATE 耗时不当作锁等待，lockWaitMs 保持 null。
- 初始授权、绑定／Schema、宿主资格、最终事务复核／写入、Worker 读取、派发预检／准备、网关、本地适配器、结果／终态提交均有阶段。 事务在真实
  JDBC 工作线程结束时记录；物理提交、重放提交、回滚及未知状态分别记录，HTTP 取消不提前结算真实事务。
- 后台领取及执行清除唤醒来源的请求诊断归属；仅移除诊断键，保留其他上下文，避免后台 SQL 污染原 HTTP 计数。 不增加
  SQL、不调整授权、派发容量、重试或期限；固定工具／manifest 及业务数据库结构保持。
- `scripts/measure_tool_phases.py` 只读统计阶段 P50／95／99、SQL／未观测／在途、期限、HTTP 对账和提交到 Worker 间隔，
  去重日志并隔离预览；缺失配对、入口早期失败及跨节点时钟问题显式展示，不将未执行阶段计为 0ms 成功。
- 独立 MySQL 8.4.11／Druid／正式平台授权／真实文本工具，10 次预热＋50 次串行样本，自动 Worker 使用原通知和恢复扫描。 50 次最终
  SUCCEEDED、零预算预留，每次入口＋受理阶段 SQL 与 HTTP 总数一致；预热后 HTTP 为 102 次 JDBC SQL， HTTP P95 240.387ms、提交到
  Worker 读取开始 P95 63ms、适配器 P95 0.186ms。此为本机基线，不代表业务数据库网络或生产性能已达标。
- 选定回归 181 项通过（core 5、ai 162、app 14），脚本 8 项通过，零失败／错误／跳过；包含原 HTTP／SSE、生成 Worker、重放当前 trace
  关联原调用编号、真实物理事务点及故障矩阵。没有运行全仓测试或浏览器验收，独立 MySQL 的基线不与重叠测试重复计数。

完整口径、环境、基线 JSON、复测及复现命令见 [分阶段观测与基线](TOOL_PERFORMANCE.md)。 本轮未连接业务数据库或
Redis、未调用外部模型、未重启用户应用；业务环境基线须重启加载代码后采集。本节为 P01 完成时的证据，后续 P02 见下一节。

## 21. P02 同一授权批次的身份与 RBAC 读取复用（2026-10-19）

- `RbacAuthorizationReads` 将原始账户、身份映射及当前 RBAC 操作集合限定在同一 appDataSource 实际事务的一次同步授权回调内。
  使用实际事务的同步器列表关联状态，REQUIRES_NEW 使用自己的状态；回调正常或异常退出立即清空，业务写入及下一轮授权不复用。
  普通快照与 FOR UPDATE 当前读使用不同键，不缓存缺失身份或最终允许决定；每次仍检查会话创建时间、名称、主体、状态和当前操作要求。
- `RbacIdentityProvider` 提供显式授权批次入口，主体 ID 与外部账户 ID 复用同一行的原始事实；`actorCurrent` 的身份映射也改为锁定当前读。
  `PlatformToolManagementAccess` 的管理初检、宿主使用预检与提交复核，以及 `PlatformToolInvocationAccess` 的读取／取消资格各自开启批次。
  Worker 的预检和网关授权继续独立执行，不跨受理、提交或执行边界复用资格。
- 公共空间门控原有的授权批次／锁顺序及事实复用保持；没有把其复用范围扩展到业务写回调。 TOOL HTTP 入口沿用独立只读快照，不为减少
  SQL 改成当前写事务；RBAC 仍读取原表的当前角色和操作，不引入登录缓存或新授权权威。
- 新增真实 JDBC 读取计数、普通／当前读分离、REQUIRES_NEW 挂起恢复、异常／回滚清理、同事务撤权及空间关闭回归； 正式 MVC
  集成增加同键并发提交、只生成一次 Invocation／Attempt，以及撤权后同键重放拒绝。
- MySQL 并发验收发现：最终授权建立 RR 快照后，另一同键事务可能已提交，但原普通受理查询仍看不到该记录。 提交内幂等定位及其关联
  Invocation 改用锁定当前读；预查和历史读取保留原模式。没有增加查询次数或重试事务， 并发失败不通过捕获重复键后伪造成功处理。

SQL 对比、本机 MySQL 复测及验证边界见 [分阶段观测与基线](TOOL_PERFORMANCE.md) 的 P02 章节。 最终定向回归 287 项、独立 MySQL
9 项分别通过；10 次预热＋50 次自动 Worker 样本均成功，HTTP SQL 每次 102→96。 本轮没有稳定延迟下降的证据，不能据此认定业务环境超时已解决；已有
RBAC 脚本尾段一致性测试失败单独记录。 没有数据库增量脚本或新增配置项；重新构建并重启后端后生效，本轮不启动或重启业务应用。
本节为 P02 完成时的证据，后续 P03 见下一节。

## 22. P03 固定工具材料批量读取与 Schema 编译计划复用（2026-10-19）

- `ToolCatalogStore` 增加内部批量 Schema 和完整固定材料读取端口。MyBatis 适配器以一条 UNION ALL 查询取得工具、提供者和去重
  Schema， 逐行保留原有字节上限、载荷摘要、精确版本、依赖索引及 Schema 内容检查；多个 Schema 的集合查询同样只执行一条 SQL。
  缺失工具返回空，缺依赖或损坏材料失败，不把缺失项伪装为完整材料。
- 绑定只读解析通过显式回调提供本次核验的材料，受理／预览准备在重新核对工具、Schema 集合和发布合同摘要后复用。
  材料引用在每次订阅中独立保存；最终提交仍重新读取当前绑定、发布和固定材料，不跨提交边界复用治理资格。
- 网关一次读取完整固定材料，并重新核对固定工具及发布合同摘要，随后编译输入／输出／配置校验计划。
  原调用前后两次固定绑定检查及当前宿主授权保留；已预热的编译缓存不能掩盖目录内容变化。
- `ToolSchemaValidator` 进程内复用不可变编译计划；键包含固定 Schema 引用、由本次原文重算的摘要、校验用途及完整 Limits。
  每次仍先检查原文容量和 Unicode，失败不缓存，实例值每次独立校验。LRU 最多 256 项，原文字节与节点估算权重最多 16 MiB，
  超出任一上限即淘汰；该权重不等于精确堆占用。编译在锁外执行，并发同键只保留一个成功计划；不缓存绑定、发布、输入值或授权决定。
- 新增单语句读取计数、Schema 去重／顺序／缺失、材料摘要及依赖索引损坏、预热后材料漂移、缓存隔离／并发／容量淘汰等回归。
  并发受理测试的阻塞屏障安排在 JDBC 调度器，模拟真实宿主异步授权，不占住元数据线程等待其他参与者。

最终定向回归 345 项、独立 MySQL 9 项分别通过，零失败／错误／跳过。 独立 MySQL 8.4.11 的 10 次预热＋50 次自动 Worker 调用均成功；预热后
HTTP SQL 为每次 85 条，P02 为 96 条。 绑定解析 8→4，Schema 准备 3→0，最终治理 13→9；网关预检
26→24。完整证据与边界见 [性能记录](TOOL_PERFORMANCE.md) 的 P03 章节。 不新增数据库脚本、依赖或配置；重新构建并重启后端后生效。本节记录
P03 完成时的状态，后续 P04 见 §23。本轮不连接业务库、调用外部模型或重启业务应用。

## 23. P04 受理事务批量写入与队列计数（2026-10-19）

- 四维队列容量由逐条锁读／更新改为一次 `IN ... ORDER BY counter_key FOR UPDATE` 和一次批量预留。
  全局、分类、空间、主体限额仍逐维比较，缺失计数器在原事务内批量创建；受理全局调度锁保留。
- 队列释放也先以同一主键顺序锁定四个计数器，再一次递减；缺失行或零计数拒绝并整体回滚，
  不通过补零或忽略受影响行数掩盖事实损坏。重复终态／控制重放沿用原有预留标志，避免重复释放。 该计数器逻辑由 TOOL 与
  GENERATION 共用，未调整分类限额、公平游标、许可或 Worker 领取策略。
- 仅新 TOOL 的 Invocation 在插入时初始化首事件序号为 1，直接保存 ACCEPTED 事件；后续事件仍走原序号递增路径。 同次受理的
  EVENT（序号 1）和 DISPATCH（序号 0）使用一条多行 INSERT，分别保留消息键和通知类型。 原生成首事件路径保持，未移除幂等／调用唯一性检查、当前授权或最终期限检查。
- 快照、受理索引、Invocation、事件、Outbox、容量预留及分类仍共用原物理事务；写入失败、队列满及期限到期全部回滚。
  两类唤醒仍只在提交后各执行一次，失败由原扫描恢复，重放不重复插入、预留或通知。

最终定向回归 242 项、独立 MySQL 集成 10 项分别通过；50 次自动 Worker 调用均受理并成功执行。 预热后 HTTP SQL
85→76、COMMIT_WRITES 26→17；本轮尾部延迟较 P03 更高，不能据此认定延迟改善。 验证及本机基线见 [性能记录](TOOL_PERFORMANCE.md)
§9。无需数据库脚本、新依赖或配置项；重新构建并重启后端后生效。 本节记录 P04 完成时的状态，后续 P05 见 §24。本轮不连接业务数据库、不重启业务应用。

## 24. P05 Worker 领取与许可校验优化（2026-10-19）

- 分类领取仍在原全局调度锁及物理事务内仲裁；先读取候选，空队列不再读取许可和游标，仍检查池策略和节点清单。
  有候选时一次读取有效许可，构建本事务内的分类／空间／提供者计数，并在每次新增许可后同步递增； 不把这些局部计数带到下一事务，也不替代数据库许可权威。
- 保持原空间轮转和饱和跳过规则，游标只读取一次，在成功批次末尾保存最后领取空间；零领取不写游标。 游标写入失败时，整批 Outbox
  领取、token 与许可一起回滚，不留下部分成功。
- Outbox 锁定行投影增加已存在的 work_class／finished，领取校验复用同事务锁定事实，不再重复查询元数据。 当前许可按
  message_key 主键读取单条，再由 Java 精确核验消息身份、token、类别和 lease_until，保留大小写语义；不再加载全池许可。
  已完成工作项的清理仍不占执行许可，过期及旧 token 继续拒绝。
- Worker、路由和 TOOL 派发边界的 Invocation 读取、当前宿主授权、准备派发中的再次时钟／租约检查及领取续租保持。 不延长 5
  秒策略，不改变恢复扫描间隔、槽位、公平权重或 manifest，也不引入跨阶段租约缓存。

最终定向回归 311 项及独立 MySQL 11 项分别通过；50 次自动 Worker 调用成功。 DISPATCH_LOAD SQL 6→5、PREPARE_DISPATCH
34→32，HTTP 受理保持 76；耗时与验证边界见性能记录。 验证和本机基线见 [性能记录](TOOL_PERFORMANCE.md)
§10。使用原有表列和索引，无需数据库脚本、新依赖或配置； 重新构建并重启后端后生效，本节为 P05 完成时的记录，后续 P06 见
§25。本轮未重启业务应用。

## 25. P06 期限评估与数据库时间精度校准（2026-10-19）

- 根据 P05 阶段记录评估总期限：进入适配器的最小剩余时间为 3978ms，尚无依据修改文本工具 v1 的 5 秒策略。 保留固定契约、绑定、manifest
  及默认 10 秒短工具阈值；不在原版本原地改 timeout，也不把请求收紧误当成产品分类变化。
- 发现通用 `SystemMapper.databaseTime` 的 `CURRENT_TIMESTAMP` 在 MySQL 上只有整秒精度，可能使 Attempt.createdAt 的
  策略保护期限比逻辑 deadline 提前接近一秒，并降低租约截止判断精度。改为 `CURRENT_TIMESTAMP(3)`，每次仍访问数据库；
  不缓存时间、不新增 SQL、不延长已保存的 Invocation deadline，也不回填旧 Attempt 时间。
- 预览 timeoutMillis 是产品／绑定／服务器／请求求交后的相对总上限，实际 deadline 还受本次入口剩余时间约束。
  页面改为“调用总上限”，明确受理、排队、执行前校验与执行共用期限；预览不创建调用，不为工具额外分配一段执行时间。
- 过期原调用仍按当前资格用原键／原正文确认原回执，查询使用当前新请求的查询期限；不增加 Invocation、Attempt 或 DISPATCH， 不重置原
  deadline／acceptedAt。原调用到期且未派发时按原队列／执行规则收尾，不因查询或确认重新执行。
- 日志统计脚本增加阶段起始剩余时间、起止最小剩余时间、缺测数及非正记录数；缺失值不当作零。 毫秒观测用于诊断，非正记录不代替持久化终态或完整期限证明。

最终后端定向 371 项、独立 MySQL 14 项、前端相关 29 项及统计脚本 10 项分别通过。 修正后 50 次自动 Worker 调用成功，进入适配器最少剩余
4305ms，网关相对原逻辑期限的整秒截断偏差消除。 实测、校准前后对比与验证见 [性能记录](TOOL_PERFORMANCE.md)
§11。无需数据库脚本、新配置或工具重新发布／绑定； 更新后端构建并重启，前端更新期限说明。本轮不连接业务库或重启业务应用，P07
待实施。

## 26. 业务路径超时证据与开发期 v1 策略调整（2026-10-19）

业务日志确认原 v1 的 5 秒总预算在受理结束时只剩 540ms，Worker 完成首次读取时已经过期 132ms； P06
独立测试库的余量不能代表这条业务路径。按当前开发阶段、没有已用版本兼容负担的约定，源码只保留 v1， 将总预算改为 30 秒，移除临时
v2；工具／能力／适配器引用、Schema、授权和计数语义不变。 默认阈值下新 v1 为 TOOL_LONG，未调整容量或期限求交逻辑。

同版本固定契约的目录校验仍保留。已登记旧 5 秒定义的开发库需统一重建相应目录、发布、绑定等测试数据，并更新清单及固定池配置；
应用不会原地覆盖旧契约或延长已有 Invocation。现有数据库和本机配置未修改，未重启应用。
证据、新清单、开发库处理步骤与验证边界见 [本次超时与开发期调整](TOOL_TIMEOUT_INCIDENT.md)。

## 27. S3-01：审批等待状态与决定事务（2026-10-19）

完成内部审批受理、决定和到期恢复，不开放公开审批入口或业务写工具。详细契约、授权责任及部署步骤见
[审批事务说明](TOOL_APPROVAL.md)；S2 HTTP／宿主执行授权／网关仍要求 NONE。

- `Invocation.State.WAITING_APPROVAL` 为非终态，不能直接进入 RUNNING／SUCCEEDED；通用状态查询与事件沿用原 DTO，
  前端枚举、进行中判断和状态文案同步。本批没有审批表单或批准／拒绝按钮。
- `ToolAdmissionStore.awaitApproval` 在原物理受理事务写固定快照、Invocation、审批及首事件；等待不写 DISPATCH， 不创建
  Attempt／租约／预算或执行队列预留。原 NONE 入口不能接受带审批策略的快照，且无审批路径不依赖新表。
- `MybatisToolApprovalStore` 以 Invocation → approval 当前行锁仲裁决定、取消与到期；
  必须验证同一租户／空间、本人／固定独立审批人、当前宿主资格、审批摘要、版本、PENDING 和数据库时钟窗口。 独立审批不能本人批准，批准者不会替换原
  request、scopes 或 deadline；固定名单不能代替当前成员／操作授权。
- 批准、QUEUED、事件、唯一工作项、分类与容量预留原子提交；队列从获批开始计时，原总期限不变。 拒绝、到期及未执行取消走 Attempt
  前完成，不虚构 ToolResult。通用取消同时关闭 PENDING；Attempt 创建前再次核验批准与固定摘要。
- 同键重放重新授权并返回原持久化回执；相反决定、摘要／版本不符、到期决定拒绝。任一写入失败整体回滚， 不留下孤立决定、队列、控制标记或事件，回滚不唤醒
  Worker。
- 无审批执行快照保持 v1，审批策略写 v2 并严格匹配版本；新增 tool-approval／tool-approval-receipt v1。
  使用稳定集合顺序生成审批摘要，不以反序列化后的集合编码顺序判断原始字节损坏。
- 有界到期扫描逐条独立事务，多实例竞争只完成一次；异常记录保留并告警，单条失败不阻断同批后续记录。 自动恢复默认关闭，需新增
  `arte.ai-new-tool-approval.enabled` 与既有 worker-enabled 同时开启，串行扫描不堆积定时任务。

验证结果：后端定向覆盖 **20 个测试类、299 项**，最终各类报告均为零失败／错误／跳过。 其中新增 **22 项审批 JDBC 集成测试、2
项恢复生命周期测试**；旧工厂白名单数量断言随新增 Mapper 同步后， 审批、工厂及装配 **35 项关联复跑全部通过**
。覆盖等待零执行资源、原子批准／拒绝、当前授权及重放撤权、 Scope／本人隔离、相反决定和取消竞争、跨实例到期／回执恢复、窗口消耗、写入失败回滚、DDL
一致与 v1／v2 兼容。 现有 S2 受理、派发、调度、查询、取消、恢复与通用执行／数据契约均纳入定向范围。

前端 TOOL 调用、SSE、恢复及旧聊天会话 **4 个文件、120 项通过**，包含新增等待审批不读终态结果的回归。 全量 `npm run tsc`
未通过，报告 106 处错误，分布在 `src/app.tsx`、文章组件、图标等未修改文件；本批修改文件无类型错误报告。
没有据此前端定向通过宣称全项目类型检查通过，也没有扩展本批去修复这些文件。

复现后端定向范围：JDK 21，仓库根目录执行（离线依赖须已齐备）：

```shell
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=ToolApproval*Test,ToolAdmission*Test,ToolInvocation*Test,ToolDispatch*Test,ToolExecution*Test,ToolScheduling*Test,ToolSubmission*Test,MybatisExecutionPersistenceTest,ExecutionSqlSessionFactoryTest,DataContractsTest,InvocationApiContractsTest,ToolDefinitionContractsTest,DispatchBillingContractTest,ResolvedBindingCodecTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

新增 `scripts/arte-ai-tool-approval-migration.sql`；全量 DDL 同步，增量与全量不能重复执行。 本轮未连接／修改业务数据库、调整业务配置或重启应用；未执行生产
MySQL、多节点、性能压测或浏览器视觉验收。 开放审批受理前须统一升级所有共享库消费者，不能单独回退到不识别 WAITING_APPROVAL／快照
v2 的程序。 S3-02 继续完成公开确认／审批、宿主当前审批授权及获批工具执行接线；写工具仍按后续批次实施。

## 28. S3-02：本人确认、独立审批与公开操作（2026-10-20）

已完成公开审批和当前平台资格，复用现有稳定身份、RBAC、空间治理、可靠受理及
Worker，不新增身份／工作空间或另建执行系统。业务写工具及未知业务结果核对仍属后续阶段。契约、配置、数据库与兼容步骤集中在 [审批说明](TOOL_APPROVAL.md)
，请求字段见 [HTTP API](TOOL_HTTP_API.md)。

- 服务端 `ToolApprovalPolicy` 计算本人确认窗口／完整 15 分钟独立审批窗口，固定至少 30 秒执行余量和 Scope
  内可信稳定主体。预览摘要覆盖人工政策；受理固定绝对时间，同键并发使用首个可靠 Plan 重放，不重置窗口。缺可信能力、合法审批人或足够期限时明确拒绝，不降级。
- `ToolApprovalAccess` 由 arte-app 显式适配，使用当前账户／稳定身份、空间成员及 AI 使用资格。本人确认复核 invoke 与工具
  requiredScopes；独立审批须为固定其他主体且持显式当前 aiTool:approve，admin 不绕过。事务按主体顺序获取既有治理门锁，再进入租户／空间锁；回执重放同样重新授权。
- 新增 getApproval、listPendingApprovals、decideApproval 三个白名单入口；不接收 owner、actor、scopes
  或审批名单。审批版本采用十进制字符串；详情精确保留 JSON 数值，列表仅返回摘要。批准 202 与工具执行成功分开。
- audience 参与者索引随等待受理同事务写入，决定／取消／到期同步关闭 pending；读取再验权威记录与当前权限，保留历史。待办按
  actor／Scope 和稳定键分页，撤权过滤后允许空页加下一游标，避免扫描完成历史。
- 获批调用保留原主体、scopes 和 deadline，经原分类队列及 Worker 当前授权执行；网关在适配器前再次核验 APPROVED、固定内容、RUNNING
  Attempt、fence 和存活租约。缺审批 Store 时人工模式拒绝；无审批旧路径保留。
- 工具中心新增“确认与审批”，调用面板展示人工等待、固定参数／配置、版本、风险、身份及服务端校准倒计时。窗口到零隐藏新决定操作。sessionStorage
  在发送前保存完整决定与键；超时／刷新只能确认原命令，存储异常不发新命令，账户／空间切换丢弃迟到响应。批准文案明确原调用仍待排队／执行。

正式 `text.countCodePoints@v1` 的 30
秒总期限保持不变；它不能容纳人工窗口加执行余量。绑定／请求不能延长工具契约，本批没有新增长期限正式工具。完整审批到实际执行的验证使用明确长期限的测试只读契约，正式宿主当前权限另由实际平台持久化测试验证。不能将这些测试声称为正式文本工具已启用人工模式。

验证：后端 **20 个测试类、292 项**定向覆盖，按最新各类报告均无失败／错误／跳过；包含新增 **7 项公开审批与执行链路测试**
、扩展正式平台权限／名单／成员回归、原 S3-01 决定／恢复及 S2
网关、受理、派发、查询、取消、容量和宿主执行回归。并发重放、独立审批禁本人、撤权、公开请求白名单、分页、关闭待办与历史读取均覆盖。此前
RBAC SQL 对齐测试将后续菜单段误纳比较，已修正边界并验证新增 approve 定义，未改原菜单。

前端 **10 个测试文件、60 项**通过，包含新增 **7 项决定组件测试**：精确文本／HTML
字面值、超时刷新保留原命令／键、成功与执行分离、损坏／不可写存储阻止发送、禁本人独立审批、到期隐藏新决定及跨 Scope 迟到响应。全量
tsc 仍有既有 106 处诊断，位于未修改文件；本批修改文件无诊断，不代表全项目前端构建通过。尝试本机浏览器夹具被浏览器以
ERR_BLOCKED_BY_CLIENT 拦截，未完成截图验收，不以组件测试代替真实视觉验收。

新增 audience 建表／旧记录回填和 approve 操作增量脚本，全量 DDL／RBAC 基线同步。
**未执行业务库脚本、分配用户权限、修改业务配置或重启应用**；MySQL 回填脚本尚未实库验证，H2 不替代
MySQL、多节点及性能验收。部署前按审批说明统一升级节点、安装新表／回填旧记录并核对参与者索引，再配置具备足够期限的已发布工具与可信名单。

### 28.1 开发库旧同名审批表检查（2026-10-20）

只读核查确认开发库的 arte_ai_tool_approval 为参考项目旧结构（request_id／call_id／status），并非 S3-01 的
owner_key／invocation_key／state／snapshot 结构；旧表有 2 行，已安装的 audience 有 0 行。回填 SQL 中的源字段与当前 Mapper／DDL
一致，不能通过替换列名迁移旧批准事实。

新增只读 approval-schema-check-mysql.sql，回填及两份增量脚本注明旧表冲突前置条件；审批部署说明增加保留旧表归档、显式新建
S3-01、已建 audience 不重复建表、新表为空无需回填的处理步骤。检查脚本已在配置开发库只读验证；没有执行归档、建表、回填或修改配置。其他环境须独立检查结构和备份，不能套用本机行数或直接假定同名表兼容。

## 29. S3-03：一个真实业务写工具（2026-10-20）

实现 `workspace.updateDetails@v1`，更新当前工作空间名称及说明，复用现有 WorkspaceService.rename；领域授权、版本、命令回执、审计与公共
Outbox 均使用原公共基础。详细输入／输出、事务、清单及配置见 [业务写工具说明](TOOL_WRITE.md)。本批不实现 S3-04／05
业务结果核对，写工具默认关闭，生产开放仍须完成核对闭环。

- 新写契约使用 RESOURCE_WRITE、DOMAIN_OPERATION_KEY、最低本人确认、最多一次 Attempt，固定目标为当前 Scope，expectedVersion
  用字符串保持精度。没有增加业务写 HTTP 或绕开可靠受理的同步执行入口。
- ToolAdmissionAccess 增加默认拒绝的独立写许可，原只读宿主不能自动批准写入；完整契约免费白名单与 WORKSPACE_UPDATE
  分别验证，批准者不替换原调用者。普通 AI 调用权限、admin 菜单标记均不代替领域写权限。
- 适配器在同一个物理领域事务内锁原 Invocation／批准／Attempt，复核
  worker、fence、发送身份、实际存活租约、原期限及耐久取消事实；再核验公共当前资格和领域授权，持有目录／绑定治理锁至提交。业务结果与原
  Invocation 派生的领域操作键防重回执一致提交；提交前校验成功信封字节并再次检查租约／期限。
- 通用网关、派发及已保存结果恢复按固定风险校验副作用：写入成功为 CONFIRMED，只读不冒充业务写入；异常保守
  UNKNOWN，无自动重发。领域提交后结果丢失不会伪称失败或创建下一次写尝试。
- 写执行快照单独写 tool-execution-snapshot v3，读端严格匹配副作用与版本；原只读 v1／v2 保持原义，篡改版本号不能降级。没有新增业务表、DDL、回填或
  RBAC 操作定义；部署前必须统一升级共享库消费者。
- `arte.ai-new-tool-write.enabled` 默认 false；开启后加入独立来源与适配器，完整工具清单改变，须统一更新同构池配置。关闭时文本
  v1 的契约、算法和清单摘要保持不变。声明免费不意味着不需要 AI／领域授权或人工决定。
- 页面显示业务写入风险、当前目标 Scope、精确预期版本及完整参数，费用文案不再将所有工具称为只读；版本冲突说明本次未修改，查询原执行不会重发。

验证：最新定向报告共 **22 类、316 项后端测试**，无失败／错误／跳过，覆盖原只读、审批、受理、调度、派发、恢复、数据契约与正式平台权限。新增
**11 项正式业务写集成测试**验证：默认关闭；MVC
预览／等待／批准／实际领域提交；同键重放仅一次变更；旧只读宿主拒绝写入；版本冲突不覆盖并发编辑；重复适配进入复用领域回执；领域提交前故障整体回滚；领域执行后租约失效回滚；提交后结果丢失保留
UNKNOWN 且不重发；撤权；伪造直接适配调用；越界参数；结果容量不足不留下领域写入。以上使用隔离 H2、真实
JDBC／MyBatis、正式宿主和真实领域命令，故障包装仅注入明确失败点。

前端 **10 文件、62 项通过**，新增写风险／版本展示和已知版本冲突不重发测试。全量 tsc 仍有未修改文件中的既有 106
处诊断，本批前端文件无诊断；未作真实浏览器视觉验收，不声称前端全量构建或生产验收通过。

本批未连接或修改开发／业务库、未执行 SQL、未开启业务配置、未发布工具／建绑、未改名真实空间或重启应用。没有执行
MySQL、多节点或生产性能验收；此前旧同名审批表的环境问题仍按 §28.1 独立处理，不用新写工具绕开正确安装 S3-01／02 的前置条件。

## 30. S3-04：自动业务结果核对（2026-10-20）

新增 `ToolOutcomeQueryAdapter`、显式受信注册、独立可靠工作项及 `ToolOutcomeWorker`。
`LocalWorkspaceOutcomeQuery` 按 S3-03 原主体、Scope、领域命令类型、操作键和参数摘要读取不可变回执， 验证领域结果及版本后重建
ToolResult；查不到只表示 UNKNOWN，不调用 rename／invoke。 历史 v1 契约、快照及清单不变；专用历史收尾注册比对完整
ToolRevision，不改已发布的 outcomeQuerySupported。

原 UNKNOWN 与核对登记同事务。首次启用按主键每轮最多 32 条回填历史调用，耐久游标与工作项一致提交， 不延长首次 UNKNOWN
窗口、不重置额度。查询最多六次／30 分钟，每次 10 秒；独立 30 秒租约， 沿用原 Attempt 的 RECONCILE
fencing，不创建新尝试、预算或写权限。失租重领复用原次数及查询截止。 复用 OUTCOME_CHECK
本地保留槽、集群／空间／提供方数据库许可，短事务提交结果、原终态、证据和事件。 无能力或超限进入 NEEDS_REVIEW；SQL
故障回滚并保留未知事实。人工证据、责任方及页面仍留在 S3-05。

新增三张表，完整基线与 [增量脚本](scripts/arte-ai-tool-outcome-migration.sql) 同步， 不在运行时建表、不执行开发库
SQL、不修改业务配置。默认关闭，部署步骤及验证边界见 [自动核对说明](TOOL_OUTCOME.md)。

本轮定向及回归累计 312 项通过、1 项跳过：arte-ai 277 项通过，既有 Redis 通知装配用例因未提供隔离 Redis 跳过； arte-app 35
项通过（含 S3-04 的 11 项集成测试）。范围包括审批、免费派发、分类调度、通用完成／恢复、生成重建核对及现有宿主写工具。
取消分支以执行期间已存在的耐久取消意图验证；UNKNOWN 后停止仍保持既有 ALREADY_TERMINAL 语义，不新增取消资格。
证据审计写入约束故障验证结果／终态／事件整体回滚；故障是隔离测试主动注入，不是开发库故障。
MySQL、双节点和性能验收未执行；前端未改动，本轮未执行前端构建或浏览器验收。
