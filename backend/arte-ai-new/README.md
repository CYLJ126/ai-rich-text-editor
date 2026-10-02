# arte-ai-new

本模块使用 base 的 [核心值契约](../arte-base/CORE_VALUE_CONTRACTS.md) 和
[最小执行支撑](../arte-base/MINIMUM_EXECUTION_SUPPORT.md)，不复用 core 的 ThreadLocal 上下文或旧异常体系。

[最小模型调用](MINIMUM_MODEL_CALL.md) 已实现固定能力／连接／绑定解析、类型化模型网关和单次执行协调。 app
提供兼容聊天协议、受控连接、现有身份与外发同意、原子预算预留、执行与可重放事件存储，并提供独立新 HTTP 路径。 新 AI 生产代码仍只依赖
base。[最小聊天数据模型](MINIMUM_CHAT_MODEL.md) 已补齐会话、提交及上下文快照的值契约和独立表；聊天服务、其他网关及编排尚未实现。

依据 [ARTE 顶层需求及设计](../../ARTE顶层需求及设计.md) 的「顶层接口 §2」及「设计 §7」声明新 AI 平台契约。

顶层设计映射为以下 Java 类型；已实现的操作范围以最小模型调用说明为准。

| Java 类型          | 使用原则                                                                     |
|--------------------|------------------------------------------------------------------------------|
| `interface`        | 可替换的网关、运行时、策略、基础设施或领域扩展端口                           |
| 普通 `class`       | 平台拥有的应用服务与流程协调；通过组合、依赖和委托协作                       |
| `record`           | 请求、结果、引用、配置及记录的值快照，不直接充当可变持久化实体               |
| `enum`             | 本模块拥有的固定状态或种类；跨领域可扩展标识保留字符串或版本引用             |
| `sealed interface` | 已知且互斥的数据变体，不作为所有能力的执行父接口                             |
| `abstract class`   | 仅在明确存在共享状态、模板流程或公共行为时引入；当前阶段没有该依据，暂不声明 |

`api` 放调用入口，`spi` 放提供者端口，`model` 放数据与状态。服务类及行为端口的操作签名留待下一步设计；不添加无行为的 Impl 或
Abstract 占位类。

已实现的模型调用和最小聊天 record 包含字段校验及集合防御性复制；其他顶层声明仍需细化。序列化由 app 适配层负责。涉及生命周期的
record 是某时点的快照，不充当可变 ORM 实体；聊天的状态推进、持久化适配和事务流程留待下一阶段。

Maven 直接依赖仅为 `arte-base`，不依赖旧 `arte-ai`、`arte-core` 或文章模块。源码使用标准 `src/main/java` 目录。保持
`com.arte.ai` 根包，新契约归入 `api`／`spi`／`model`；未来替换旧模块时再迁移应用接线。

| 类型                                                                                               | Java 声明   | 包（com.arte.ai 下） | 职责                       |
|----------------------------------------------------------------------------------------------------|-------------|----------------------|----------------------------|
| [ConversationService](src/main/java/com/arte/ai/api/conversation/ConversationService.java)         | `class`     | `api.conversation`   | 会话与历史管理             |
| [ChatService](src/main/java/com/arte/ai/api/conversation/ChatService.java)                         | `class`     | `api.conversation`   | 聊天场景入口               |
| [AiActionService](src/main/java/com/arte/ai/api/action/AiActionService.java)                       | `class`     | `api.action`         | 独立 AI 动作入口           |
| [ContextService](src/main/java/com/arte/ai/api/context/ContextService.java)                        | `class`     | `api.context`        | 上下文组织                 |
| [MemoryService](src/main/java/com/arte/ai/api/memory/MemoryService.java)                           | `class`     | `api.memory`         | 允许保留的记忆管理         |
| [CapabilityCatalog](src/main/java/com/arte/ai/api/control/CapabilityCatalog.java)                  | `class`     | `api.control`        | 能力契约目录               |
| [ConnectionManager](src/main/java/com/arte/ai/api/control/ConnectionManager.java)                  | `class`     | `api.control`        | 连接配置管理               |
| [BindingManager](src/main/java/com/arte/ai/api/control/BindingManager.java)                        | `class`     | `api.control`        | 能力使用绑定               |
| [AssistantManager](src/main/java/com/arte/ai/api/control/AssistantManager.java)                    | `class`     | `api.control`        | 助手与聊天偏好管理         |
| [SkillRegistry](src/main/java/com/arte/ai/api/control/SkillRegistry.java)                          | `class`     | `api.control`        | 受信 Skill 注册            |
| [DefinitionRegistry](src/main/java/com/arte/ai/api/control/DefinitionRegistry.java)                | `class`     | `api.control`        | 动作、策略与工作流定义管理 |
| [ReleaseManager](src/main/java/com/arte/ai/api/control/ReleaseManager.java)                        | `class`     | `api.control`        | 配置发布管理               |
| [InvocationCoordinator](src/main/java/com/arte/ai/api/execution/InvocationCoordinator.java)        | `class`     | `api.execution`      | 单次能力执行协调           |
| [ModelGateway](src/main/java/com/arte/ai/api/gateway/ModelGateway.java)                            | `interface` | `api.gateway`        | 文本与多模态模型生成       |
| [EmbeddingGateway](src/main/java/com/arte/ai/api/gateway/EmbeddingGateway.java)                    | `interface` | `api.gateway`        | 向量生成                   |
| [MediaGateway](src/main/java/com/arte/ai/api/gateway/MediaGateway.java)                            | `interface` | `api.gateway`        | 媒体生成与任务交互         |
| [ToolGateway](src/main/java/com/arte/ai/api/gateway/ToolGateway.java)                              | `interface` | `api.gateway`        | 受控工具执行               |
| [ApplicationGateway](src/main/java/com/arte/ai/api/gateway/ApplicationGateway.java)                | `interface` | `api.gateway`        | 远程应用与 Agent 调用      |
| [WorkflowRuntime](src/main/java/com/arte/ai/api/runtime/WorkflowRuntime.java)                      | `interface` | `api.runtime`        | 预定义流程运行             |
| [AgentRuntime](src/main/java/com/arte/ai/api/runtime/AgentRuntime.java)                            | `interface` | `api.runtime`        | 受限目标任务运行           |
| [ExecutionControl](src/main/java/com/arte/ai/api/execution/ExecutionControl.java)                  | `class`     | `api.execution`      | 执行查询与控制             |
| [ExecutionEventService](src/main/java/com/arte/ai/api/execution/ExecutionEventService.java)        | `class`     | `api.execution`      | 执行输出订阅与恢复         |
| [ExecutionStore](src/main/java/com/arte/ai/spi/store/ExecutionStore.java)                          | `interface` | `spi.store`          | AI 调用与尝试存储          |
| [ExecutionEventStore](src/main/java/com/arte/ai/spi/store/ExecutionEventStore.java)                | `interface` | `spi.store`          | 耐久输出与事件存储         |
| [BudgetService](src/main/java/com/arte/ai/api/execution/BudgetService.java)                        | `class`     | `api.execution`      | AI 预算与费用账本          |
| [EvaluationService](src/main/java/com/arte/ai/api/evaluation/EvaluationService.java)               | `class`     | `api.evaluation`     | AI 场景质量评估            |
| [ModelRoutingStrategy](src/main/java/com/arte/ai/spi/strategy/ModelRoutingStrategy.java)           | `interface` | `spi.strategy`       | 模型路由策略               |
| [ContextSelectionStrategy](src/main/java/com/arte/ai/spi/strategy/ContextSelectionStrategy.java)   | `interface` | `spi.strategy`       | 上下文选择策略             |
| [RetryStrategy](src/main/java/com/arte/ai/spi/strategy/RetryStrategy.java)                         | `interface` | `spi.strategy`       | 重试决策策略               |
| [AgentDecisionStrategy](src/main/java/com/arte/ai/spi/strategy/AgentDecisionStrategy.java)         | `interface` | `spi.strategy`       | Agent 决策策略             |
| [ResultCompositionStrategy](src/main/java/com/arte/ai/spi/strategy/ResultCompositionStrategy.java) | `interface` | `spi.strategy`       | 结果组合策略               |
| [ProviderAdapter](src/main/java/com/arte/ai/spi/adapter/ProviderAdapter.java)                      | `interface` | `spi.adapter`        | 供应商能力适配             |
| [ProtocolAdapter](src/main/java/com/arte/ai/spi/adapter/ProtocolAdapter.java)                      | `interface` | `spi.adapter`        | 外部协议适配               |
| [ConnectionRuntime](src/main/java/com/arte/ai/spi/adapter/ConnectionRuntime.java)                  | `interface` | `spi.adapter`        | 连接运行支撑               |
| [ResourceContextAdapter](src/main/java/com/arte/ai/spi/business/ResourceContextAdapter.java)       | `interface` | `spi.business`       | 可选业务资料提供           |
| [BusinessToolAdapter](src/main/java/com/arte/ai/spi/business/BusinessToolAdapter.java)             | `interface` | `spi.business`       | 可选业务工具适配           |

五类 Gateway 并列，分别使用各自的类型化请求和结果，不引入统一的 `execute(Object)`。业务调用经 `InvocationCoordinator`
统一治理；上下文组织不派发调用，模型提出工具请求后由运行时经协调入口执行。

业务资料与工具通过可选 `ResourceContextAdapter`／`BusinessToolAdapter` 接入，业务成果应用使用 base
的领域变更处理端口。未注册提供者的能力不暴露为可用；AI 核心以用户消息与历史即可独立运行。

类型声明不表示已实现全部阶段能力。按设计 §2.8
的职责划分，最小非流式模型链已打通；当前已补齐聊天数据模型，下一阶段组合会话、聊天和上下文。流式输出与恢复／对账、助手、动作、记忆及其他网关逐步补充，Workflow／Agent
按需实现。产品 P0／P1／P2 分期仍以需求文档为准。

app 并列依赖新旧 AI 模块，通过显式开关启用新入口，旧 AI 依赖和调用保留。原有 `main/resources/application-ai.yml`
暂保留为迁移参考，不属于 Maven
标准资源目录，也不参与新模块构建；供应商和基础设施配置在选择实现后整理。

## 数据与状态声明

依据顶层接口 §2.2、设计 §3 及附录 B，使用以下 Java 类型。服务端构建的执行上下文不接受客户端自报主体；公共层不拥有 AI 状态，AI
不拥有公共 Job 和目标领域的正式保存状态。

| 类型                                                                                             | Java 声明          | 包（com.arte.ai 下） | 职责                                                                       |
|--------------------------------------------------------------------------------------------------|--------------------|----------------------|----------------------------------------------------------------------------|
| [CapabilityKind](src/main/java/com/arte/ai/model/capability/CapabilityKind.java)                 | `enum`             | `model.capability`   | 并列的类型化原子能力；协议、Skill、Workflow 和 Agent 不属于能力种类。      |
| [SideEffectKind](src/main/java/com/arte/ai/model/capability/SideEffectKind.java)                 | `enum`             | `model.capability`   | 声明的能力副作用等级；未知等级按受限操作处理。                             |
| [DefinitionStatus](src/main/java/com/arte/ai/model/definition/DefinitionStatus.java)             | `enum`             | `model.definition`   | 定义与发布状态；修改已发布契约需要新版本。                                 |
| [DefinitionRef](src/main/java/com/arte/ai/model/definition/DefinitionRef.java)                   | `record`           | `model.definition`   | 版本化配置引用；定义类型保持可扩展，不以 Java 继承树混合不同语义。         |
| [CapabilityDescriptor](src/main/java/com/arte/ai/model/capability/CapabilityDescriptor.java)     | `record`           | `model.capability`   | 操作的类型化契约及能力特性；发现不等于获准使用。                           |
| [CapabilityDefinition](src/main/java/com/arte/ai/model/definition/CapabilityDefinition.java)     | `record`           | `model.definition`   | 能力定义的版本快照，区别于连接及主体使用绑定。                             |
| [ConnectionDefinition](src/main/java/com/arte/ai/model/definition/ConnectionDefinition.java)     | `record`           | `model.definition`   | 连接配置的版本快照；地址仅属于受控配置，调用请求不接受任意远端地址。       |
| [BindingDefinition](src/main/java/com/arte/ai/model/definition/BindingDefinition.java)           | `record`           | `model.definition`   | 限定租户、空间、主体及允许操作的能力使用配置；不替代资源授权。             |
| [ChatProfile](src/main/java/com/arte/ai/model/definition/ChatProfile.java)                       | `record`           | `model.definition`   | 可复用聊天配置；默认资料仍需逐次授权。                                     |
| [AssistantDefinition](src/main/java/com/arte/ai/model/definition/AssistantDefinition.java)       | `record`           | `model.definition`   | 助手复用定义的版本快照；实际执行记录解析后的发布版本。                     |
| [AiActionDefinition](src/main/java/com/arte/ai/model/definition/AiActionDefinition.java)         | `record`           | `model.definition`   | 通用动作定义；场景及输出契约可扩展，文章操作由组合模块注册。               |
| [SkillDefinition](src/main/java/com/arte/ai/model/definition/SkillDefinition.java)               | `record`           | `model.definition`   | 受信指令与资源包定义；资源引用不意味着允许执行脚本。                       |
| [StrategyDefinition](src/main/java/com/arte/ai/model/definition/StrategyDefinition.java)         | `record`           | `model.definition`   | 可插拔策略定义；策略类别和配置 Schema 分别声明。                           |
| [WorkflowDefinition](src/main/java/com/arte/ai/model/definition/WorkflowDefinition.java)         | `record`           | `model.definition`   | 预定义流程的版本快照；流程结构单独引用，步骤及循环规则后续细化。           |
| [ReleaseManifest](src/main/java/com/arte/ai/model/definition/ReleaseManifest.java)               | `record`           | `model.definition`   | 固定版本依赖及校验结果的发布清单；不保证供应商模型行为不变。               |
| [MessageRole](src/main/java/com/arte/ai/model/message/MessageRole.java)                          | `enum`             | `model.message`      | 消息在模型交互中的角色；角色不改变资料信任或用户授权。                     |
| [ContentPart](src/main/java/com/arte/ai/model/message/ContentPart.java)                          | `sealed interface` | `model.message`      | 消息内容的已知变体；文本与产物引用分别表达，新增模态时显式扩展。           |
| [TextPart](src/main/java/com/arte/ai/model/message/TextPart.java)                                | `record`           | `model.message`      | 文本内容部件；来源由上下文片段关联。                                       |
| [ArtifactPart](src/main/java/com/arte/ai/model/message/ArtifactPart.java)                        | `record`           | `model.message`      | 图片、音频、视频等产物内容部件；可发送模态由能力契约校验。                 |
| [Message](src/main/java/com/arte/ai/model/message/Message.java)                                  | `record`           | `model.message`      | 类型化消息快照，不依赖供应商 SDK。                                         |
| [ContextRequest](src/main/java/com/arte/ai/model/context/ContextRequest.java)                    | `record`           | `model.context`      | 显式资料、消息、历史及记忆的选择请求；不默认读取全库或全部历史。           |
| [ContextFragment](src/main/java/com/arte/ai/model/context/ContextFragment.java)                  | `record`           | `model.context`      | 实际使用的内容片段、来源、引用标识及裁剪说明；派生摘要不得冒充全文。       |
| [ContextSnapshot](src/main/java/com/arte/ai/model/context/ContextSnapshot.java)                  | `record`           | `model.context`      | 执行时固定的上下文值快照，不拥有来源领域的正式内容。                       |
| [ExecutionStatus](src/main/java/com/arte/ai/model/execution/ExecutionStatus.java)                | `enum`             | `model.execution`    | AI Invocation 和 Attempt 的执行状态；调用成功不表示业务应用完成。          |
| [RunStatus](src/main/java/com/arte/ai/model/execution/RunStatus.java)                            | `enum`             | `model.execution`    | 多步编排 Run 状态；由选定运行时管理，独立于公共 Job 状态。                 |
| [ExecutionOptions](src/main/java/com/arte/ai/model/execution/ExecutionOptions.java)              | `record`           | `model.execution`    | 通用单次执行选项；能力专有参数保留在各自请求中。                           |
| [InvocationRequest&lt;I&gt;](src/main/java/com/arte/ai/model/execution/InvocationRequest.java)   | `record`           | `model.execution`    | 类型化单次调用信封；I 保留能力输入类型，不携带明文凭据或任意远端地址。     |
| [Invocation&lt;I&gt;](src/main/java/com/arte/ai/model/execution/Invocation.java)                 | `record`           | `model.execution`    | 逻辑能力调用的记录快照；与尝试、工作项及多步运行分开。                     |
| [Attempt](src/main/java/com/arte/ai/model/execution/Attempt.java)                                | `record`           | `model.execution`    | 一次调用尝试的记录快照；重新生成产生新的尝试，结果未知须核对副作用。       |
| [Run](src/main/java/com/arte/ai/model/execution/Run.java)                                        | `record`           | `model.execution`    | 多步任务的查询快照；引擎历史存在时不以此快照另行推进状态。                 |
| [ControlAction](src/main/java/com/arte/ai/model/execution/ControlAction.java)                    | `enum`             | `model.execution`    | 声明支持的任务控制动作；支持取消不意味着支持暂停或继续。                   |
| [Conversation](src/main/java/com/arte/ai/model/conversation/Conversation.java)                   | `record`           | `model.conversation` | 会话的只读值快照；资料关联与资料权限分开。                                 |
| [Turn](src/main/java/com/arte/ai/model/conversation/Turn.java)                                   | `record`           | `model.conversation` | 一次提交的用户输入、上下文及执行关联；重新生成保留独立记录。               |
| [AiActionExecution](src/main/java/com/arte/ai/model/action/AiActionExecution.java)               | `record`           | `model.action`       | 独立动作的执行记录快照；可关联追问，业务采纳状态由应用记录引用。           |
| [ModelOptions](src/main/java/com/arte/ai/model/generation/ModelOptions.java)                     | `record`           | `model.generation`   | 模型生成的基础选项；供应商扩展及边界值后续按能力 Schema 细化。             |
| [GenerationRequest](src/main/java/com/arte/ai/model/generation/GenerationRequest.java)           | `record`           | `model.generation`   | 文本或多模态生成请求；工具描述仅限本次允许范围。                           |
| [StructuredValue](src/main/java/com/arte/ai/model/tool/StructuredValue.java)                     | `record`           | `model.tool`         | 带 Schema 引用的结构化值；JSON 在执行边界校验，不作为无约束参数通道。      |
| [ToolCall](src/main/java/com/arte/ai/model/tool/ToolCall.java)                                   | `record`           | `model.tool`         | 模型提出的工具请求；此记录不代表授权或工具已执行。                         |
| [ModelResult](src/main/java/com/arte/ai/model/generation/ModelResult.java)                       | `record`           | `model.generation`   | 模型生成结果与工具请求；业务工具由运行时统一治理后执行。                   |
| [EmbeddingRequest](src/main/java/com/arte/ai/model/embedding/EmbeddingRequest.java)              | `record`           | `model.embedding`    | 批量向量输入；向量模型由外层能力与绑定确定。                               |
| [EmbeddingResult](src/main/java/com/arte/ai/model/embedding/EmbeddingResult.java)                | `record`           | `model.embedding`    | 向量、维度及模型版本；不同向量空间不得混用。                               |
| [MediaRequest](src/main/java/com/arte/ai/model/media/MediaRequest.java)                          | `record`           | `model.media`        | 媒体生成输入；可接受模态及格式由媒体能力契约声明。                         |
| [MediaSubmission](src/main/java/com/arte/ai/model/media/MediaSubmission.java)                    | `sealed interface` | `model.media`        | 媒体提交的两种结果：已完成产物或已受理远端任务；不以空结果区分同步与异步。 |
| [MediaResult](src/main/java/com/arte/ai/model/media/MediaResult.java)                            | `record`           | `model.media`        | 已完成的媒体产物及用量；产物存在不表示已应用到业务资源。                   |
| [MediaTask](src/main/java/com/arte/ai/model/media/MediaTask.java)                                | `record`           | `model.media`        | 已受理的异步媒体任务，保留远端身份与控制能力。                             |
| [ToolInvocation](src/main/java/com/arte/ai/model/tool/ToolInvocation.java)                       | `record`           | `model.tool`         | 准备执行的工具调用；绑定、参数、授权及副作用仍需执行边界校验。             |
| [ToolResult](src/main/java/com/arte/ai/model/tool/ToolResult.java)                               | `record`           | `model.tool`         | 工具的结构化结果、来源及产物；工具成功与领域保存的含义由工具契约明确。     |
| [RemoteTaskStatus](src/main/java/com/arte/ai/model/remote/RemoteTaskStatus.java)                 | `enum`             | `model.remote`       | 远端任务状态，与本地调用及工作项分别管理。                                 |
| [RemoteTaskRef](src/main/java/com/arte/ai/model/remote/RemoteTaskRef.java)                       | `record`           | `model.remote`       | 连接内远端任务身份、所属范围及声明的控制能力；取消请求与实际终态分开。     |
| [RemoteSessionRef](src/main/java/com/arte/ai/model/remote/RemoteSessionRef.java)                 | `record`           | `model.remote`       | 按连接、租户及主体隔离的远端会话引用。                                     |
| [RemoteApplicationRequest](src/main/java/com/arte/ai/model/remote/RemoteApplicationRequest.java) | `record`           | `model.remote`       | 远程应用输入及可选的继续会话引用，不复用模型生成请求。                     |
| [RemoteApplicationResult](src/main/java/com/arte/ai/model/remote/RemoteApplicationResult.java)   | `record`           | `model.remote`       | 远程应用结果、会话和任务引用；保留远端生命周期及可观测边界。               |
| [Usage](src/main/java/com/arte/ai/model/budget/Usage.java)                                       | `record`           | `model.budget`       | 供应商报告的用量；缺失值表示未知，不能记为零或视作最终费用。               |
| [BudgetStatus](src/main/java/com/arte/ai/model/budget/BudgetStatus.java)                         | `enum`             | `model.budget`       | AI 预算预留与结算状态；未知费用保持待对账。                                |
| [BudgetReservation](src/main/java/com/arte/ai/model/budget/BudgetReservation.java)               | `record`           | `model.budget`       | 预算原子预留的账本快照，独立于快速准入。                                   |

`InvocationRequest<I>` 保留五类能力的不同输入类型。`ContentPart` 明确文本／产物内容变体；`MediaSubmission`
明确已完成结果／远端异步任务变体。结构化工具及应用输入使用带 Schema 的 `StructuredValue`，不采用 `Object` 或任意参数 Map。
`Conversation`、`Invocation`、`Attempt`、`Run` 等 record 表示记录快照。最小模型执行的状态推进及预算账本事务已实现；聊天条件更新和事务流程尚未实现。

最小聊天新增 `ConversationStatus`、`TurnKind`、`TurnStatus` 枚举，以及 `ContextHistoryRef`、`ContextBudget`
record；字段语义、表映射、幂等与并发约束见 [最小聊天数据模型](MINIMUM_CHAT_MODEL.md)。
