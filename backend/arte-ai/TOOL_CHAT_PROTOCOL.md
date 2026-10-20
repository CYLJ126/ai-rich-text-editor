# S4-01：允许工具集合与模型协议

日期：2026-10-20。已实现内部集合准备及单次模型协议；工具聊天产品入口仍待后续批次。
需求基线为 [AI TOOL §10](../../ARTE%20AI%20TOOL%20需求及设计.md#10-聊天助手与工作流中的工具使用)。

## 1. 固定允许集合

`ToolSetService.prepare(session, scope, selections)` 接收已在请求线程捕获的 `ToolManagementAccess.Session`、范围和有序绑定选择。
每次订阅重新执行 LIST 授权，核对主体与范围、工具必需 scopes，经原 `ToolBindingService.resolve` 检查当前发布／绑定／本地实现可用性，
取得内容寻址的固定绑定，再从 `ToolCatalogStore` 加载固定输入 Schema，校验摘要及 ARTE Schema 子集。

返回的 `ToolSetSnapshot` 固定 owner 及每项的模型别名、绑定解析、输入 Schema 和进一步收紧的策略。
绑定解析包含实际工具／提供者／适配器版本、配置、配置摘要及外发交集；后续发布、配置变化不会改写已有快照。
集合准备允许保存原有固定绑定解析工件，但不创建 Invocation、Attempt、Run、预算或派发消息。

- 集合包含 1—128 项；拒绝重复绑定，以及同一工具 ID 的多个绑定／版本，避免一次生成中同一产品具有不明确的配置含义。
- 按选择顺序使用 `tool_0` 至 `tool_127`。别名只用于本轮协议映射，不是权限身份；固定后不能重排。 不将含点号的产品 ID
  直接作为供应商函数名，也不进行可能冲突的字符串替换。
- 选择策略与绑定策略求交集：期限／次数／容量取小、退避取大、审批取强。冲突直接拒绝，不静默扩大策略。
- 元数据准备使用现有有界元数据 Scheduler；整次准备的期限复用 `arte.ai-new-tool.discovery.source-timeout`（默认 10
  秒），不为每个绑定重置。
- LIST 资格用于选择元数据，不是免费资格或业务资源执行许可。真正的工具子调用仍须重新准入、检查当前资源权限、发布／绑定状态及审批。

本批没有集合查询 HTTP、助手 CRUD 或默认工具集合。客户端不能提交 `ToolSetSnapshot`、Schema 原文、运行身份或自报权限。

## 2. 内部单轮调用边界

新增 `ModelGateway.generate(call, toolSet)` 和生成适配器的显式快照重载，保留原纯文本方法。 受信组装方使用
`toolSet.descriptions()` 构造 `GenerationRequest.tools`，携带同一快照调用网关。 生成请求继续只保存工具／Schema
引用、描述和副作用声明；实际配置和 Secret 引用不进入供应商请求。 模型结果中的 `ToolCall.tool` 是准确的产品版本；后续编排必须用同一快照的
`tool(ref)` 取得唯一绑定和策略，不能重新解析当前绑定来替换原材料。

网关仍检查模型绑定、当前授权、预算配置兼容性、期限、取消及连接生命周期，不重试、不执行业务工具。
`FixedControlCatalog` 与 DeepSeek 适配器支持显式 `TOOL_CALLS` 能力；工具请求必须设置正数步骤／并发约束。 单轮提议数量还受
`ExecutionOptions.maxToolSteps` 限制，该字段在此仅限制模型提议，不能代替后续 Run 的累计配额。

适配器要求快照 owner 同时匹配原请求和运行上下文、描述列表与固定集合完全一致、当前运行 scopes 覆盖工具声明、策略没有放宽。
未声明 `TOOL_CALLS`、缺少快照、版本／Schema／描述／顺序不匹配或快照跨范围时拒绝；不查询全局同名工具补齐。
没有专门实现工具协议的其他适配器／网关默认拒绝该重载。

S4-02 已将固定集合、初始输入及每轮步骤计划纳入 [Run 存储基础](TOOL_CHAT_RUN.md)，支持持久化恢复；实际子生成受理／Worker
传递及当前重新授权仍须后续接线。 当前 `GenerationDispatcher` 仍调用纯文本重载；本批未接线工具生成
Worker，也未将快照加入既有 codec 或 Outbox 根类型。 不能仅给配置添加 `TOOL_CALLS` 就开放自动工具聊天。

## 3. DeepSeek 请求及消息

按 [DeepSeek 官方 Tool Calls 指南](https://api-docs.deepseek.com/guides/tool_calls/) 和
[Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/) 映射：

| 平台材料           | 供应商字段／规则                                                                                                                    |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------|
| 固定集合           | `tools[].type=function`；`function.name` 使用固定别名                                                                               |
| 描述与输入 Schema  | `function.description`、标准 JSON `parameters`；移除 ARTE 自定义根 `$schema`                                                        |
| ARTE 对象默认约束  | 对 object 或未声明 type 的 Schema 节点补齐 `additionalProperties=false`，递归处理 properties／items；不改写 enum／const／default 值 |
| ASSISTANT 工具提议 | `tool_calls[].id/type/function.name/function.arguments`；空文本用 null，参数按标准 JSON 编码                                        |
| TOOL 结果          | `role=tool`、`tool_call_id`、文本内容                                                                                               |

继续禁用 thinking；不启用 DeepSeek Beta strict，不请求当前未支持的推理内容或多模态输出。 Schema 描述不携带
Callback；参数声明和消息映射只使用本轮固定材料，不执行 SQL 或业务调用。

历史中的工具提议必须属于该集合的准确版本，参数也须符合固定输入 Schema。 callId 在本次请求历史中唯一；每组提议必须在下一条非
TOOL 消息或请求结束前收到完整、唯一的结果，结果可按不同顺序返回。 孤立／重复／缺失 TOOL 结果、穿插未完成的提议、历史外工具均在发送前拒绝。
本协议校验不能证明历史来源可信；后续 Run 必须从原模型输出与权威工具结果组装消息，不能接收客户端伪造的中间过程。
跨轮历史是否保留工具过程继续遵循需求 §10.3，本批不改变 `ChatHistoryLoader`。

## 4. 流式聚合与完整性

每次订阅创建独立聚合状态，按 tool call index 合并参数片段，允许多个调用交错到达。id／name 首次提供后必须保持一致； type 必须声明
function，最终索引连续，callId 不得与本轮其他调用或已输入的历史调用重复。
`ToolCallDelta` 仅是过程数据，不是执行命令。

只有同时满足以下条件才返回 `complete=true` 的 `ModelResult`：

1. 流收到 `[DONE]`，finish reason 为 `tool_calls`，且确有工具提议；非流完整响应使用其明确响应边界。
2. 所有调用身份和类型完整、名称均属于固定集合，参数是完整且唯一的 JSON object。
3. 参数通过严格解析和固定输入 Schema／容量校验；任意一个调用失败，整组不返回完整工具提议。

严格 JSON 解析拒绝重复键、尾随文档、非 object 参数、深度／节点／数字超限，保留十进制精度。 参数聚合同时受单工具 `maxInputBytes`
（最高 1,000,000 字节）与本次生成累计输出容量限制；文本、参数及调用身份共同计入累计字节／字符。 结构解析深度上限 24、节点上限
8192；Schema 校验沿用已有更细约束。JSON 编码大小和原始片段大小均须符合有效策略。

`stop` 且有工具片段、`length`／过滤／未知结束且有工具片段、缺少结束、参数中断、finish 后继续输出都不产出完整调用。
失败可保留已接收的文本及已知 usage，但 `partialResult` 永不包含工具提议，complete 必须为 false。
正常完整的工具提议只是一次模型请求的结果，不代表工具已执行或整轮聊天已完成。

主要错误包括 `TOOL_SET_REQUIRED`、`TOOL_SET_MISMATCH`、`INVALID_TOOL_SET`、`INVALID_TOOL_MESSAGE_PAIRING`、
`INVALID_TOOL_HISTORY`、
`UNKNOWN_MODEL_TOOL`、`DUPLICATE_TOOL_CALL_ID`、`TOOL_CALL_ID_CHANGED`、`TOOL_CALL_NAME_CHANGED`、`INVALID_TOOL_CALL_INDEX`、
`TOOL_CALL_LIMIT_EXCEEDED`、`TOOL_ARGUMENT_LIMIT_EXCEEDED`、`INVALID_TOOL_ARGUMENTS`、`INCOMPLETE_TOOL_CALLS`。 现有
`OUTPUT_LIMIT_EXCEEDED`、`PROVIDER_STREAM_INTERRUPTED`、`INVALID_PROVIDER_COMPLETION` 等失败分类继续使用。
日志只记录原有执行标识、错误码、容量及 usage 状态，不记录参数、配置、模型正文或凭据。

## 5. 验证及后续

定向测试覆盖固定版本与配置不漂移、当前授权撤销、跨范围／重复／停用绑定、Schema 外发语义、工具消息配对、交错片段、精确数字、 非法
JSON、未知名称、重复身份、超限、流中断、取消、完整及非流响应。HTTP 用例经过真实临时本地服务器、网关、受控连接与 SSE codec，
验证缺少集合时零请求，以及工具响应与原纯文本响应均正常。

本批不提供 Run、自动工具派发、父子 Invocation 受理、审批编排、预算累计、上下文 Token 估算、Turn／历史／搜索投影扩展或前端入口。
这些仍按 S4 后续批次逐项完成。本批无 DDL、无业务配置变更、无业务数据库或真实模型调用，也不构成生产负载／真实供应商验收。
实际运行命令及测试结果见 [实施记录 §32](TOOL_IMPLEMENTATION.md#32-s4-01允许工具集合与模型协议2026-10-20)。
