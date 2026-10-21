# S4-05：工具聊天受理、循环与恢复

> 当前界面已由 S4-06 接入，显式后续动作、新增 API 及验证边界见 [ChatNew 工具聊天说明](TOOL_CHAT_FRONTEND.md)。下文按原批次记录后端能力。

日期：2026-10-20。实现工具聊天后端闭环，沿用现有身份、空间、会话、统一 Invocation Worker、审批、业务资源授权及账户账本。
独立开放开关默认关闭；页面、工具聊天重新生成意图与交互验收属于 S4-06。本批不连接配置业务库或真实模型供应商，不执行
DDL、发布工具、创建业务绑定或修改部署配置。

## 1. 受理与执行链路

`ToolChatService` 只接收单条用户文本和选择意图。服务端准备当前会话历史、准确模型绑定、允许工具集合及预算计划， 以同一事务提交
Run、类型化 Turn、会话占用、预算行、首事件和工作项，提交后返回 `AcceptedExecution.Kind.RUN`。 客户端不能提交中间
ASSISTANT／TOOL 消息、Schema、权限、执行身份或子调用。

处理顺序：

1. Run Worker 领取数据库工作项，读取原检查点；先处理已提交停止请求及原总期限。
2. READY_MODEL 固定该轮实际 ContextSnapshot 和稳定子身份，下一次领取通过原统一受理创建模型 Invocation。
3. GenerationDispatcher 从原 Run 加载固定 ToolSetSnapshot，调用模型网关的工具协议重载；参数增量仍只是过程事件。
4. 模型 Invocation 完整成功后，从原结果存储校验结果类型、引用、准确工具版本、完整性、callId、输入 Schema 和剩余限制。
5. 最终纯文本回答原子投影为 Run 结果；工具提议则先固定工具步骤，再受理准确子调用。模型提议本身不执行工具。
6. 原 TOOL 受理、宿主免费／资源资格、审批、派发和实际业务边界继续生效，子结果由原 Invocation／Attempt 保存。
7. 全组工具成功后，从原完整模型提议及原工具结果组装配对消息，固定下一轮输入。既有轮次历史只保留最终用户／助手文本。

所有真实外发仍由统一 Invocation Worker 完成，Run Worker 不占着租约等待模型或业务调用。 每个子上下文指向原 Run、共享
trace，沿用固定账户映射，权限和期限只收紧。子受理事务及原子派发均锁原根复核阶段、准确步骤和停止门闩。 工具子快照额外核验原
Run 的固定绑定、输入 Schema 与收紧策略，不能降低审批级别或扩大次数／容量。

当前每个逻辑模型步骤 `maxAttempts=1`；安全失败后不由 Run 分配新键重做同一轮。 工具 Attempt
上限继续取原产品／绑定／服务器政策的交集。模型轮次、累计工具计划数与 Attempt、Token、费用分别计算。

## 2. 输入容量与结果回传

工具轮次使用 `tool-chat-utf8-estimate-v1`。估算包含完整消息、工具描述、固定输入 Schema、工具参数和结果，
并加逐消息／工具协议余量；超出服务器输入字节数、消息结构上限或固定输入 Token 容量时明确停止，不截断未配对过程。 报价继续使用固定
ContextBudget.maxInputTokens 与 maxOutputTokens；实际费用只取原可信用量／结算链路。 此估算没有冒充供应商 tokenizer，也不构成真实模型
Token 用量的测量保证。

工具结果必须来自准确子 Invocation 的完整成功结果，匹配 callId、原固定执行快照及输出 Schema。 本批仅将已校验的结构化业务输出作为
`role=tool` 数据回传，不发送内部错误、配置、凭据、来源或产物地址。 仅产物而没有可回传结构化输出的结果明确报
`TOOL_RESULT_NOT_MODEL_READABLE`，不猜测下载地址或重新执行工具。 工具描述／输出不改变允许集合、授权、账户或后端控制命令。

只读提议按 Run 并发上限占名额；等待审批也占名额。只要一组包含写提议，整组串行，实际写入继续经过原领域版本和操作键。
已知工具失败、审批拒绝／过期、预算不足或轮次耗尽，当前采用停止整轮的保守策略，不再请求模型生成替代操作。

## 3. 审批、UNKNOWN 与重启

人工政策沿用原 ToolApproval。Run 展示 WAITING_APPROVAL，等待原子调用的权威状态；批准不创建第二份工具调用，
也不替换原参数、账户、配置或领域操作键。已批准原调用可在 Run 展示审批等待时派发，仍须通过原批准／Attempt 校验。

任一模型或工具为 UNKNOWN，Run 进入 PENDING_RECONCILIATION，暂停其他新子受理及外发，不把未知结果回传为普通错误。 工具 UNKNOWN
接入原 S3 自动／人工核对；工作空间领域回执查询支持固定 Run 子身份，但不增加业务重发。 可信核对后读取原
Invocation，在原总期限内恢复；过期后仅收尾，取消／根结束都不释放未知费用 H。

稳定子身份、原始步骤输入、子受理幂等键及检查点是恢复依据：计划提交后、子受理后或结果提交后的崩溃均重读原身份。
重复通知／领取不重复逻辑操作。失租不能提交新检查点；短暂等待使用 park 释放租约，不增加 Run 状态版本或制造空进展事件。
SQL／连接等基础设施错误保留工作项，租约过期后重领；存储缺失或损坏不伪装业务失败或伪造结果。 旧无预算计划的未完成 Run 以
`RUN_BUDGET_PLAN_REQUIRED` 收尾，不猜测账户或费用上限，不创建新子调用。

## 4. 停止与锁顺序

停止请求先在原 Run 命令表保存独立命名空间的命令回执和唯一停止门闩，并唤醒工作项；不需要新增表或字段。
同一事务锁根，阻止停止之后的新子受理／派发。`cancellationRequested` 可在根版本尚未变化时查询到。 接口 202
只说明停止请求已保存，不证明正在执行的业务已停止。

Worker 在根事务之外逐个保存子取消请求；所有已受理子调用的停止请求落库后，再提交 Run CANCELLED／TIMED_OUT 或失败收尾并释放会话。
崩溃后重放原子停止键。根事务不持根锁等待子 Invocation 锁；已经完成或 UNKNOWN 的子事实完整保留。
取消可能与已获派发许可的在途调用交错，业务是否生效仍查原子结果及核对证据。

Run 授权先取得宿主公共资格锁，再持根锁复核；子受理使用公共资格→子→根，派发沿用子→Outbox→根→预算／账户。
授权回调可在同一事务执行两次，须为幂等资格检查，不能执行业务操作。期限／撤权后的基础设施收尾只保存停止及控制终态，不获得新调用资格。

预算 blocked 阻止新外发，包括免费工具；已经完整成功的最终模型回答仍可提交，异常费用与 blocked 保留，不能因费用超额丢弃现成回答。

## 5. HTTP

所有路径沿用 `/arte` 部署前缀、POST、真实登录、`ResultContext` 与 `Idempotency-Key`；请求字段白名单校验。

### submitToolChat

`POST /arte/ai-new/tool-chat/submitToolChat`，成功 HTTP 202：

```json
{
  "scope": {"tenantId": "<当前租户>", "workspaceId": "<当前空间>"},
  "conversationId": "<已有活动会话>",
  "expectedVersion": 0,
  "text": "统计这段文字的字符数",
  "binding": {"type": "binding", "id": "<模型绑定>", "version": "<准确版本>"},
  "capability": {"type": "capability", "id": "<生成能力>", "version": "<准确版本>"},
  "modelBudgetRef": "<当前授权的既有账户>",
  "maxInputTokens": 12000,
  "generationOptions": {"maxOutputTokens": 1024, "temperature": null, "topP": null, "stopSequences": []},
  "toolBindingIds": ["<个人工具绑定 ID>"],
  "timeoutSeconds": 120,
  "maxModelRounds": 8,
  "maxToolCalls": 16,
  "maxConcurrentTools": 2,
  "budgetLimits": [{"currency": "CNY", "amount": "1.00"}]
}
```

`data={executionId,kind:"RUN",acceptedAt}`，executionId 为 Run ID。 同键同意图返回原
Run，重新核验原材料的当前资格；输入、会话／版本、模型、工具选择、生成／结构上限或预算意图改变返回幂等冲突。 并发受理冲突后重新定位原
Run，不能用新临时身份另开一次执行。重放复用原历史和期限，不重新加载后来追加的会话内容。

`timeoutSeconds` 为 1—3600，且不得超过既有服务器 maximumTimeout；模型轮次 1—32、工具调用 1—100、并发 1—16 且不超过工具总数。
工具选择最多 128 项；最多 8 个独立币种，金额为非负精确十进制字符串，不接受负数或科学计数法。实际工具／模型及预算资格仍由当前服务端检查。

### cancelRun 与查询

`POST /arte/ai-new/tool-chat/cancelRun`，正文 `{scope,runId}`，HTTP 202 返回安全 Run 状态及 `cancellationRequested`。
READ／CONVERSATION 上下文只用于当前会话归属检查，真正停止另外经过宿主本人 `aiTool:invoke`／空间控制资格及事务内复核。 关闭空间
AI 新调用资格后，只要上述 HTTP／执行功能仍部署启用，仍可按当前本人控制资格停止原 Run。

继续使用 `getRunStatus`、`getRunBudget`、`getRunResult`、`replayRunEvents`。状态增加 `cancellationRequested`；
预算版本独立于根状态，根结束仍可有 H／后续可信结算。审批仍使用既有审批 API，操作目标为检查点中的真实工具子 Invocation ID。
停止门闩可以先于根版本变化，客户端不能只按根 version 丢弃 cancellationRequested 的更新。

RUN_EVENT 有独立发布器、本机有界提示及 ACK；提示丢失和跨节点读取通过耐久游标补读。 本批不增加 Redis Run 广播或公开 Run
SSE；前端接线前可按 Run 状态、事件及预算接口轮询，不能把 Run ID 交给 Invocation SSE。

## 6. 配置、编码与部署

本机开发配置通过 `backend/profile/common.properties`／`app.properties` 经 Maven 过滤进入模块运行时资源
`ainew/config/application.properties`。工具聊天的五项属性已接入该映射，共享默认关闭。 本机 `app.properties`
显式覆盖后须重新构建资源并重启后端，仅刷新前端不会生效；不要修改生成的 target/classes 文件。

```properties
# 以下为显式启用示例；共享默认仍关闭，未代为启用本机业务环境。
arte.ai-new-tool-chat.enabled=true
arte.ai-new-tool-chat.worker-enabled=true
arte.ai-new-tool-chat.poll-interval=1s
arte.ai-new-tool-chat.lease=45s
arte.ai-new-tool-chat.batch-size=8
```

同时要求原 `ai-new.enabled`、`ai-new-tool.enabled`、`ai-new-execution.enabled`；自动 Run Worker／事件发布还要求原
execution.worker-enabled。 仅启用提交节点可以关闭两个 Worker 开关，由其他已升级节点执行。装配不执行
SQL，缺少真实模型派发器、工具准入、预算或宿主授权时失败。 人工工具还须部署／开启原审批及其过期恢复；业务写入、分类调度、真实发现清单、自动／人工核对沿用各自原开关和部署前置条件。

无新增 DDL。已有库须具备 S4-02、03、04 增量及此前 TOOL／审批／核对的表结构，不能重复执行完整基线。 Run 子工具的
`tool-execution-snapshot` 使用 v4，显式允许经耐久 Run 步骤验证的父身份及模型 callId；旧独立工具 v1／v2／v3 不变，降级版本解码被拒绝。
Invocation／Attempt、Run、Run 输入 v2 和账户账本版本不变；新子模型受理使用独立语义摘要，普通文本摘要保持原义。
所有可能领取子调用／审批／核对／Run 工作项的节点须先协调升级，再开放提交和 Worker，不能让旧节点读取 v4。

## 7. 验证边界

定向验证覆盖完整闭环及最终历史、同键并发、两个崩溃窗口、已受理子调用的停止门闩、UNKNOWN 暂停与原工具核对后恢复、
审批批准／拒绝、失租、并发名额、轮次／预算／期限、费用超额保留最终回答、HTTP 白名单、停止可见性、v4 防降级和默认关闭的装配／通知发布。
实际命令、结果及扩大回归发现的非本批问题见 [实施记录 §36](TOOL_IMPLEMENTATION.md#36-s4-05工具聊天受理循环与恢复2026-10-20)。
H2、MockMvc 和本地固定模型／领域响应不能证明真实供应商、生产 MySQL、多节点故障切换或高负载指标。 页面、工具聊天重新生成、真实浏览器交互验收仍属于
S4-06；本批不声明生产开放或性能验收通过。
