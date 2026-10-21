# S5-02：工具按次计费与预算

日期：2026-10-21。本批在现有 Account／Reservation／Settlement 上实现工具费用，不建立第二套余额。 独立调用及 Run
工具子调用均可使用；默认关闭。没有新增真实收费服务、Tavily 适配器或 MCP，也没有把健康查询改成收费服务。

## 1. 可执行边界

`ToolDefinition.price` 缺省为空，表示沿用受信免费合同；非空为 `ToolPrice(rate, perCall)`：固定费率引用、币种和每次调用的费用上界。
`NewAiProperties.Rate.toolPerCall` 必须与其完全相同，且不能同时有模型 Token 单价；模型绑定不能引用工具费率。 报价为零仍为
CHARGE，必须选择账户并建立预留，不能通过省略 budgetRef 变成免费。

本批收费合同限 READ_ONLY、approvalMode=NONE、maxAttempts=1，原有单次结果／空特性限制继续有效。 工具必须显式登记为
`PaidToolAdapter`，提供完整且相等的固定版本和 `requireCharge` 当前资源检查，宿主在受理事务和发送边界再次授权。
收费不是任意工具的通行证：现有 LOCAL／受控 HTTP 的目标、网络和实际实现检查仍适用，未实现协议仍拒绝。 适配器只能从可核验的服务端响应／账单解析
`ToolCharge`；客户端参数、模型文本、业务成功、HTTP 200、报价均不能替代真实费用证据。 无法约束最大费用或无法确定归属的服务保持不可发布、不可执行。

当前内置 `text.countCodePoints`、领域文本工具和 `arte.http.health` 继续 FREE；新增收费适配器仅在测试中用于验证全链路。
真实供应商接入须另行实现请求、认证、出口、账单来源和异常语义，并发布新的固定合同。POST、Bearer、异步服务和 MCP 没有在本批开放。

## 2. 账户、授权与空间配置

沿用现有身份、RBAC、空间 AI enabled 和当前 grant。工具额外需要当前 `aiTool:invoke`
与合同资源权限；预算账户须已初始化，owner、rate、currency、limit 与当前批准配置一致。
没有账户不自动建账；只读查询不写余额。模型与工具可有不同账户，不能把模型账户直接填给工具或让模型参数指定 budgetRef。
同一固定工具费率可在授权允许时共用一个账户；不同费率使用各自账户。工具定义和费率版本不可原地改价，价格变化属于不兼容升级。

平台目录允许追加新的固定费率，保留全部旧版本，不能重定义或删除旧费率。管理员通过已有配置发布 API 登记 `toolPerCall`
，再批准默认模板／空间工具预算；本批没有单独的费率创建表单。 空间 AI
设置显示已批准工具预算，可维护额度；改选模型会保留工具预算。只启用工具的空间可以没有模型绑定，但收费工具仍须有批准且初始化的账户。
提高批准额度继续使用原平台管理员规则；预算使用资格不由管理权限替代。

独立调用先 POST `/ai-new/tool/availableToolBudgets`，请求 `{scope,bindingId}`，返回 `{price,accounts}`；账户字段沿用
BudgetAccountResponse。 查询复核当前 READ／INVOKE、绑定归属与执行器，且只列出当前已授权、已初始化、费率相符的账户。免费返回
price=null、空 accounts。 收费 `previewInvocation`／`invokeTool` 的 input 增加 `budgetRef`，预览返回
`billingMode=CHARGE,budgetRef,price,costDescription`。 确认摘要固定账户、价格、版本、参数及相对限制。更换账户／价格需重新预览，重放不能偷偷换账户。

## 3. 派发与收尾

受理不扣费。Worker 以固定 perCall 作为 q，在原短事务中校验报价、当前资格、费率阻断和账户余额，再创建 Attempt、Reservation
和发送标记。 未预留成功不进入适配器；默认单次尝试，恢复和重领不重新发送工具。

可信 `ToolCharge(amount,evidenceRef,kind)` 与业务结果分开耐久保存，同一 Attempt
的证据不可覆盖。先保存费用，再裁剪／验证结果信封；因此结果超限或结果持久化故障不会抹去已保存的账单。 终态提交后结算、再
ACK；恢复读取原账单结算，不再次 invoke。禁用新收费调用不卸载收尾服务，原工作仍可结算。

| 费用证据                         | 账本处理                          | 业务含义             |
|----------------------------------|-----------------------------------|----------------------|
| 尚未发送／网关证明没有进入适配器 | RELEASED，实际零                  | 不伪造业务成功       |
| 可信 PROVIDER_BILL               | SETTLED，记真实金额并释放原预留   | 成功或失败均可收费   |
| 没有账单，包括超时、取消、失租   | PENDING_RECONCILIATION，保留 held | 不推断零费用或未执行 |

NO_INVOCATION 仅用于受信网关的未进入适配器证据，零金额与原发送／安全失败事实一起校验。
费用已进入待核对后，沿用原预算管理员核对接口确认费用／零费用，不自动重发原调用；晚到费用证据不能覆盖管理员已确认的最终结算。
费用确认不解决 TOOL 的 UNKNOWN 业务状态；原业务结果核对／人工复核也不释放费用预留。无查询适配器的 UNKNOWN 进入
NEEDS_REVIEW。

真实费用超过 q 时仍完整入账，并对该固定 rate 锁存全局阻断；该费率所有新收费派发被拒绝，包含其他账户和其他 Run。
已有在途调用仍可产生费用、结算与核对。阻断不自动复位，本批无重置 API：运营先停用问题发布／适配，审核修复后用新工具／费率版本发布，并处理旧在途项和账单。
该保护在发现上界失效后阻止后续请求，不承诺撤销已经发出的供应商计费。

## 4. 工具聊天

提交增加 `toolBudgetRefs:{bindingId:budgetRef}`，仅允许已选中的收费绑定；免费绑定不能带账户。 模型继续
modelBudgetRef，每个子上下文从已固定映射取得账户，Run 根仍没有单一预算账户。 收费工具 Selection 保存 ToolPrice，MODEL 使用
ModelPrice；TOOL Allocation 每 Attempt 唯一。

每种币种单独满足 `C + H + q <= L`，同时检查实际账户可用余额。工具 q=perCall，模型 q=最大输入／输出 Token 的固定计价上界。 Run
与账户预留／结算共用事务；同币种的模型与工具共同计入该 Run 限额，不重复扣费；跨币种不换算、不求和。 费用未知保留
H；根结束、取消和预留到期均不释放未知费用。真实费用超额同时保留现有 Run blocked，并触发工具固定费率阻断。
界面要求显式选账户，新增工具币种时添加默认 0 的单独上限，用户自行调整后提交；查询失败不显示免费。

## 5. 升级与开放

1. 所有目录读者、HTTP 节点和 Worker 升级到支持新编码的版本；保留原分类部署 manifest 的精确合同一致性要求。
2. 已有数据库人工执行 [billing migration](scripts/arte-ai-tool-billing-migration.sql) 一次；新库采用更新后的总
   DDL。脚本只加费用证据／费率阻断表，不改旧余额。
3. 登记和验收真实 PaidToolAdapter、固定工具／费率版本、可信账单与当前资源授权；批准空间账户并初始化，发布及绑定。
4. 在已有 TOOL／Worker 开关和完整清单满足后，显式设置 `arte.ai-new-tool-billing.enabled=true`；profile 默认与示例均
   false。开启并不自动登记工具或放宽网络。
5. 在隔离环境验收不足额、撤权、并发、超时未知费用、崩溃恢复、对账、报价超额阻断和原免费／模型路径后开放。

新编码：tool-definition-revision／tool-binding-resolution v2、收费 tool-execution-snapshot v6、含收费工具的
tool-chat-run-snapshot v3、TOOL allocation v2、含费用的 invocation-result v2，以及新 tool-charge v1。
免费原编码和摘要保持原义，新材料拒绝降级重标；不能在旧消费者仍运行时发布收费合同。关闭开关也不代表可以回退旧二进制。

本批验证使用临时 H2、MockMvc、固定收费适配器和回环 HTTP，不连接真实供应商或业务数据库。生产 MySQL
锁行为、多节点、性能和真实浏览器／供应商验收仍须执行。
