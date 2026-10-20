# AI TOOL S3-04：自动业务结果核对

更新：2026-10-20。实现独立本地免费 TOOL 的 UNKNOWN 自动核对，首个正式查询适配为
`workspace.updateDetails@v1` 的原领域命令回执。不重新执行业务写入，不调用模型，不预留或结算费用。 S3-05
已补 [人工领域证据核对、责任池和页面](TOOL_RECONCILIATION.md)。写工具仍默认关闭，生产开放仍需真实环境及性能验收。

## 查询什么

`ToolOutcomeQueryAdapter` 与 `ToolGateway.invoke` 分离。`LocalWorkspaceOutcomeQuery` 只读取原
`arte_platform_command`：命令身份固定为原 subject、tenant／workspace、`workspace:rename` 和
`CanonicalJson.key("tool-workspace-details-v1", invocationId)`；不产生新的操作键、Attempt 或改名命令。 同时核验原参数摘要、
`workspace:view:v1`、APPLIED、目标 Scope、名称／说明、expectedVersion + 1 和回执版本，再由原回执重建原 ToolResult
与证据摘要。查询当前空间名称不能替代原操作回执；空间后来再次改名也不改变原操作事实。

存在匹配回执可确认成功；查不到回执只返回 STILL_UNKNOWN。当前领域回执没有独立的可靠失败／未执行证明，因此本适配器不凭空返回
CONFIRMED_FAILED 或 PROVEN_NOT_EXECUTED。通用受信提交端口支持这两个结论，并验证 KNOWN、副作用及结果形状。

原 v1 发布的 `outcomeQuerySupported=false` 保持不变，清单摘要及固定快照不修改。后台通过独立受信
`ToolOutcomeQueryRegistry` 明确登记历史 v1 的收尾资格，并比对完整不可变 ToolRevision、空配置、无 budgetRef／parent。
这是专门审核的历史收尾适配，不因工具名、风险声明、远端 TASK_QUERY 或某个布尔值就信任任意查询实现；其他工具需要另行实现和验收。

## 工作项、期限与并发

原调用提交 UNKNOWN 时，同一个物理事务登记 `arte_ai_tool_outcome_check`，失败则整个 UNKNOWN 提交回滚。 首次启用时，每轮扫描最多
32 条历史 Invocation，游标及登记同事务写入 `arte_ai_tool_outcome_scan`；扫描完成后停止全库扫描， 新 UNKNOWN
由原子登记覆盖。只登记原独立免费 TOOL；不支持查询的调用进入 NEEDS_REVIEW，不能默默丢失未知事实。 历史登记以原 UNKNOWN
Attempt 的 updatedAt 为窗口起点，不以启用、刷新或重启时间重新计时；已有任务不覆盖。

| 约束     | 本批实现                                                                              |
|----------|---------------------------------------------------------------------------------------|
| 检查间隔 | 首次 5 秒；以后 30 秒、2 分钟、5 分钟、10 分钟、10 分钟，均受总窗口限制               |
| 总额度   | 最多 6 个逻辑查询、首次 UNKNOWN 起 30 分钟；额度／下一时间持久化                      |
| 单次查询 | 最长 10 秒；JDBC 短事务限制 10 秒，读取前后校验原固定材料和有效查询期限               |
| 租约     | 30 秒；工作项版本、fence 与原 Attempt 的 RECONCILE fencing 联合仲裁                   |
| 崩溃重领 | 不增加该次逻辑配额、不延长原查询截止；已过查询截止则记中断并按原次数退避              |
| 状态     | CHECKING → RESOLVED；不支持、超额／超期或证据异常 → NEEDS_REVIEW；原调用仍 UNKNOWN    |
| 容量     | 独占既有 OUTCOME_CHECK 本地保留槽，与既有调度共用集群／空间／提供方许可和数据库容量锁 |

核对沿用原 Attempt 的操作身份，仅更新 RECONCILE 租约、版本与 fencing，不新建执行尝试。 RECONCILE 不能 markDispatch；原 Worker
的租约／版本已失效，不能与核对结论竞争覆盖。UNKNOWN 仍禁止业务重派。 查询原操作使用自己的短期限，原执行 deadline
到期不恢复执行资格，也不禁止必要收尾。 退避到期只是允许领取；容量不足、节点停机或数据库不可用不会保证按秒准时完成。

查询超时或暂时异常按 STILL_UNKNOWN 退避；无效回执／结果进入 NEEDS_REVIEW。 提交 SQL 失败回滚，保留工作项以待租约恢复，不能把存储故障记成业务失败。
30 分钟／次数耗尽不会自动转成 FAILED、UNRESOLVABLE 或释放费用。

## 受限系统资格与提交

查询资格绑定数据库中的原 UNKNOWN、owner、Invocation 版本、唯一原 Attempt、固定快照及批准材料、操作键、工作项租约和容量许可。
不使用原用户的当前 invoke 或资源写权限读取不可变回执。因此撤销用户权限、关闭空间 AI 或写工具入口后，后台仍可收尾。
查询没有公开用户入口，不恢复原主体的调用权；状态／结果的用户读取继续经过现有当前授权。关闭整个执行基础或本核对开关会停止后台，
只是暂停自动收尾，不删除工作项；停用产品入口时应保持基础 Worker 与核对开关运行。

确认事务重新锁定并复核原 UNKNOWN、版本／fencing、Scope、Attempt、操作键、固定材料及证据，验证结果 Schema、实际副作用与原输出字节上限。
结果字节以独立不可变结果槽保存，保留之前的 UNKNOWN 结果。证据审计 `arte_ai_tool_outcome_evidence`、原 Invocation／Attempt
终态、 结果引用和终态事件在同一事务提交，容量许可随事务释放。过期 Worker、跨主体证据、重复或相反完成不能覆盖已确认事实。

免费调用没有 reservation，本批不新增预算账户或金额。业务核对和现有模型费用核对保持独立；后续收费查询必须另行获得可信计价／限额资格，
不能直接复用本地免费查询端口放开外部请求。

## 安装与启用

本批未执行开发库 SQL、修改运行配置、调用真实改名或重启应用。已有 S3-03 库，先停止相关消费者及新受理，备份并检查三张目标表不存在，
再显式执行 [增量 SQL](scripts/arte-ai-tool-outcome-migration.sql)。全新库使用完整 `arte-ai-new-ddl-mysql.sql`，不重复执行增量。
不使用 IF NOT EXISTS 掩盖漂移，不复用参考项目旧核对表。原 S3-01／02 审批及 audience 结构仍须正确安装。

统一升级共享库所有节点；新代码可在开关关闭时运行且不访问三张新表。要捕获全部新的 UNKNOWN 并使用历史扫描， 不要混用仍可能提交
UNKNOWN 却未安装原子登记的旧节点。已登记任务／游标不能在回滚时直接删表。

```properties
arte.ai-new-tool-outcome.enabled=true
# 可选，默认值；严格绑定，不接受未知字段。
arte.ai-new-tool-outcome.poll-interval=1s
arte.ai-new-tool-outcome.batch-size=16
```

默认 enabled=false。需要既有 AI／TOOL／执行开关、分类调度和正确同构工具清单；自动轮询还要求
`arte.ai-new-execution.worker-enabled=true`。本批不修改写工具契约或清单，也不自动发布、建绑、授予 RBAC。 poll-interval 范围
100ms～1min，batch-size 范围 1～64；实际单轮并发仍受本地 OUTCOME_CHECK 保留槽限制。

## 验证边界

隔离 H2 集成覆盖真实领域提交后结果丢失、原回执恢复、缺失回执六次退出、两 Worker 竞争、旧租约拒绝、跨主体证据拒绝、 撤权／AI
关闭后的系统收尾、用户结果读取约束、历史登记不续期、期限耗尽、无效领域回执转待复核、证据落库失败全事务回滚、已有取消意图配合未执行证明收敛，以及默认关闭时无新增表依赖。
本轮 S3-04 集成测试 11 项及 DDL 增量测试 1 项通过，相关回归累计 312 项通过、1 项隔离 Redis 用例跳过。 SQL 与主流程仍须在目标
MySQL 环境验证，不以 H2 结果代替 MySQL 锁、双节点、故障切换或生产性能验收。

S3-05 部署还需安装人工责任／命令审计增量和显式核对权限；仅安装上述 S3-04
三表不足以运行新版本完整核对后台，见 [人工核对部署](TOOL_RECONCILIATION.md#部署)。
