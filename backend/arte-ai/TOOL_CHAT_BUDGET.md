# S4-04：Run 总预算与子账户选择

> S5-03：新增默认关闭的受管 MCP 只读工具，沿用原管理／调用／Run
> 链路；当前协议、会话、结果子集与部署要求见 [MCP 说明](TOOL_MCP.md)。

> S5-02 已扩展收费工具映射／TOOL
> Allocation／多币种界面与全局费率阻断，当前规则及版本要求见 [工具计费说明](TOOL_BILLING.md)。

> 当前界面已由 S4-06 接入，显式后续动作、新增 API 及验证边界见 [ChatNew 工具聊天说明](TOOL_CHAT_FRONTEND.md)。下文按原批次记录后端能力。

> S4-05 已将此预算基础接入真实循环和停止门闩，见 [执行说明](TOOL_CHAT_EXECUTION.md)。完整最终回答不因费用超额而丢弃，异常费用与
> blocked 完整保留；下文保留 S4-04 批次范围。

日期：2026-10-20。本批完成预算基础及其在通用原子派发／结算中的约束，没有开放工具聊天提交或自动执行。Run 循环、取消传播和子调用受理留
S4-05，页面留 S4-06。

## 1. 两层额度，沿用一份账户扣费

普通聊天继续通过既有 Account／Reservation／Settlement 管理模型费用。工具聊天复用同一账本，在其上增加 Run
的附加上限，不另建余额或重复扣一份费用。

每个币种分别记录 L（Run 限额）、C（已确认费用）、H（未收敛预留）。新收费 Attempt 的报价为 q，只有 `C + H + q <= L`
且实际账户可用余额足够时才能派发。每个收费 Attempt 都有独立 Allocation，与原 Reservation 对应；模型重试不复用旧 Attempt
的费用占用。

例如 Run 限额 1，首轮报价 0.7 即使尚无账单，也令 H=0.7；下一轮再申请 0.7 会被拒绝。费用未知时不能只看
C=0，也不能因为根结束、预留到期或用户取消而释放 H。

已知账单结算时，Run 与账户在同一事务移除原 quote 并增加实际费用。PENDING_RECONCILIATION 保留原 H，实际费用不重复计入 C。既有
Reservation／settlementKey 防重继续有效，第二个最终结算不能覆盖第一份账单。

币种上限最多 8 个，分别显示、分别约束，不做汇率转换或跨币种求和。S5-02 增加显式收费工具；原有内置工具和 S5-01
健康查询继续免费。收费工具币种与模型币种可不同；额外币种的额度不能补足另一币种不足。

S5-01 HTTP 工具沿用这套免费选择及次数／并发／期限限制，不继承模型账户。模型读取工具描述和返回状态所产生的 Token
费用仍由模型子调用承担，详见 [HTTP 工具说明](TOOL_HTTP_REMOTE.md)。

## 2. 固定计划与子上下文

`ToolChatBudgetPlan` 是固定材料，包含有序币种上限及“固定 binding／capability →
计费模式／账户／费率版本／价格”的映射。每个绑定固定一个账户，不在运行中静默换账户；模型账户无需与根相同，根 budgetRef 仍为
null。

模型使用 CHARGE，包括金额恰为零的情况。固定 `ModelPrice` 保存每百万输入／输出 Token 单价，沿用现有 18 位 HALF_UP
计价。派发报价必须等于该模型步骤 ContextBudget 的最大输入 Token 与固定 maxOutputTokens
算出的上界，不能由调用方传零或较小估算绕过预留。真实账单仍由既有可信用量／证据结算，固定报价不冒充实际费用。S4-05 组装每轮
ContextBudget 时须计入工具描述／Schema 和工具结果，不能只校验用户文本后把该预算当作完整模型请求的上界。

未声明价格且经过免费白名单审核的本地工具和 S5-01 健康查询必须显式 FREE，选择中没有账户、费率或价格；既不继承模型账户，也不读写费用预留、Allocation
或
Settlement。仍检查 Run 活动阶段、计划子身份、权限、期限和取消／派发资格。S5-02 收费工具按固定 perCall 上界预留，映射由
toolBudgetRefs 提供；尚不支持每账户独立的 Run 子上限；当前实际账户本身的限额始终生效。

`ToolChatBudgetSelector.prepare` 在受理前检查当前 INVOKE、模型／连接可用性、grant 的绑定及预算允许集合、配置与真实账户
owner／rate／currency、本地工具固定解析，生成预算计划。预算计划的构造与读取本身不授予执行资格；Run 受理回调及后续子调用仍要在各自边界复核当前权限。

`child(runId, childId, scopes, requestedDeadline, currentContext)` 只接受已固定且当前阶段关联的子身份。每次重新授权，并验证当前账户／价格／绑定仍符合原映射，再构造新上下文：

- 保持原 owner、root trace、release，parentExecutionId 指向原 Run。
- scopes 必须同时是原 Run 和当前授权的子集，并满足 INVOKE 及工具必要资源 scopes。
- deadline 取请求、原 Run、当前可信上下文中最早值，不能刷新总期限。
- 子幂等键由固定子身份稳定派生；预算引用只取固定映射，免费工具明确为 null。

不使用 `ExecutionContext.child()` 跨账户，不接受模型参数覆盖 budgetRef。选择器不创建
Invocation／Attempt，不替代工具资源授权、审批或外发校验。独立 TOOL 的 parentExecutionId 限制没有放宽。

## 3. 原子派发、结算与异常

新派发顺序为：子 Invocation → 已领取 Outbox → Run 当前资格行 → Run 各币种预算行（排序）→ 账户 → Reservation；Attempt
的既有检查沿用原流程。核验当前阶段／子身份、owner、trace、固定输入／生成参数、scope／期限上限，以及固定报价／账户／费率后，才占用费用并标记
MAY_HAVE_EXECUTED。

Root 资格行的当前版本须与权威快照一致，整个准备事务持锁，阻止根终态与新外发交错。Run
总预算与账户预留、Allocation、Attempt、派发标记及既有事件一起提交或回滚。Run 额度或账户不足时沿用
`PreparedDispatch.ready=false`／既有 INSUFFICIENT_BUDGET，保留未发送的 Attempt；既不产生预留，也不授予发送许可。

父执行非空的子调用必须走 `prepareDispatch`，不能拆用 createAttempt、reserve、markDispatch 绕过附加预算。当前仅支持
ToolChatRun 父类型；其他父类型需要自己的受信协议，不能自动取得旧调用的豁免。

结算顺序为：子 Invocation → Run 各币种预算 → 账户 → Reservation／Allocation。无需取得新派发资格或锁根状态，因此根已结束或原期限已过时，仍可凭可信费用证据收尾。Run
检查点／取消处理不得持根锁后等待子 Invocation 锁。

Allocation 唯一键为 Run＋子 Invocation＋attemptNumber＋chargeKind，并对 Attempt、Reservation
分别唯一；与原预留的身份、rate、quote、状态和版本对应。结算与防重共用现有事务，不使用两次异步 Store 调用模拟原子性。

若账单超过 quote，或真实 `C + H > L`，完整记录费用并将 Run 预算 blocked 锁存为 true。即使之后有剩余额度，也不自动恢复派发；任一币种异常会阻止该
Run 新外发，包括免费工具。这里的异常不会丢弃已完成回答或伪造仍在额度内。问题供应商／适配的全局停用由现有配置治理和运营处理，本批不自动修改平台或供应商配置。S4-05
编排须将 blocked 作为不可继续状态停止新增子调用，不能通过换键重试规避阻断。

普通无 parent 调用走原分支，新增 Run 预算组件立即返回，不为纯文本聊天或独立免费工具增加 Run SQL。Run
派发／子账户选择使用步骤主键读取单个固定输入，避免扫描全部历史上下文。

## 4. 查询与版本兼容

新增 POST `/arte/ai-new/tool-chat/getRunBudget`，请求沿用 `{scope:{tenantId,workspaceId},runId}`。与其他 Run 查询相同，复核当前
`ai:read`、`ai:conversation`、会话／Turn 类型引用及归档资格；不依赖 ES，也不暴露 owner、账户或价格配置。

`data` 是按币种排序的数组，每条包含 `runId, currency, limit, charged, held, available, blocked, version`。金额及 available
全为十进制字符串，available 可以为负。每个币种有独立预算版本，预算结算不冒充 Run 检查点推进，也不推进 Run
事件游标。后续界面应主动读取该预算权威，不只根据根状态版本判断费用是否收敛。

带预算计划的 `tool-chat-run-snapshot` 使用 v2，币种额度和映射参与受理幂等摘要。v1 保留原编码及摘要，读取不猜测账户／限额；没有计划的旧
Run 返回空预算列表，区别于“已知费用为零”。其材料可以读取，不能派发新的 Run 子调用。预算行缺失或与固定上限不符时明确报存储异常，不显示伪造零额度。

新增内部 v1 根 `tool-chat-budget` 与 `tool-chat-budget-allocation`。v1 Run 快照不允许夹带 budgetPlan，v2
必须携带非空计划，版本必须为整数，不能改版本号降级。ToolChatRun 状态、子 Invocation／Attempt、原账户账本格式未改。

## 5. 数据库及验证

新装使用 [完整基线](scripts/arte-ai-new-ddl-mysql.sql)。已完成 S4-03
的环境停写、备份后显式执行 [S4-04 增量](scripts/arte-ai-tool-chat-budget-migration.sql)，新增 Run 预算与 Allocation
两张表；金额在带摘要的不可变类型快照中精确保存，不使用浮点数或数据库隐式舍入。

不为旧 Run 自动填额度／账户；旧无预算 Run 不能用新派发入口继续外发。全体节点协调升级后再写 v2，不能混跑仍不理解预算计划的旧节点。运行时不执行
DDL。本批未连接或部署配置业务库，未重启应用。

验证命令、数量与范围见 [实施记录 §35](TOOL_IMPLEMENTATION.md#35-s4-04run-总预算与子账户选择2026-10-20)。测试使用临时
H2、MockMvc、固定本地响应；不代表真实供应商、生产 MySQL、多节点或高负载验收。后续仍需 S4-05 的真实受理／循环／审批／取消集成及
S4-06 的界面。
