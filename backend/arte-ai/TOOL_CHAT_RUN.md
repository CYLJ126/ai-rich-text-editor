# S4-02：ToolChatRun 存储与推进基础

> S4-05 已在独立默认关闭开关下完成真实受理、循环、审批／停止及 UNKNOWN 恢复，见 [执行说明](TOOL_CHAT_EXECUTION.md)。下文保留
> S4-02 批次边界。

> 后续 S4-03 已完成聊天关联受理、类型化 Turn、Run 事件／Outbox、最终回答投影和只读查询，见 [兼容说明](TOOL_CHAT_HISTORY.md)
> 。本文其余部分保留 S4-02 批次边界。

> S4-04 为固定输入增加预算计划 v2，并将额度占用与原账户派发／结算同库提交，见 [预算说明](TOOL_CHAT_BUDGET.md)。

日期：2026-10-20。已完成内部存储基础，不开放工具聊天，不运行模型或工具循环。 本批验收：检查点、步骤映射及工作项耐久；重复推进不创建重复逻辑子操作。

## 1. 已实现材料

| 类型                     | 职责                                                                                                |
|--------------------------|-----------------------------------------------------------------------------------------------------|
| `ToolChatRunSnapshot`    | 固定根 ExecutionContext、实际用户／历史 ContextSnapshot、模型绑定、生成选项、允许工具集合及执行上限 |
| `ToolChatRun`            | owner、状态、版本、轮次／阶段／当前子操作检查点、累计计划数和最终结果／错误引用                     |
| `ToolChatStep`           | 固定每个 MODEL／TOOL 步骤的输入、子执行 ID 和独立 operationKey                                      |
| `ToolChatCommands.Lease` | 数据库租约、fence、领取时已看到的信号版本；不替代用户授权                                           |
| `ToolChatRunStore`       | 受理、内部读取、步骤查询、领取／续租、条件推进及唤醒                                                |

Run 根使用独立 executionId，parentExecutionId 和 budgetRef 均为空，不借用 Invocation 表，也不继承单一模型预算账户。 模型选择必须声明
`TEXT_INPUT`、`STREAMING`、`TOOL_CALLS`；根 owner、工具集合 owner 和权限上限一致。 初始上下文、绑定、实际工具版本／配置／策略／Schema
均固定，恢复不重新解析当前配置替换原材料。 实际子调用还须重新检查当前授权、启停、审批、资源资格及所选账户；持有快照或租约不授予这些资格。

根总期限固定，受接收时刻、相对 timeout 和初始 ContextSnapshot 有效期共同限制；恢复和续租不能延长。 结构上限：timeout 1 秒—1
小时，模型计划 1—32 轮，工具计划 1—100 次，并发上限声明 1—16。 本批计数是已固定的逻辑计划数，不是实际调用／尝试／Token
或费用；并发执行控制和真实用量由后续编排完成。 单次推进批次累计消息／参数字符不超过 1,000,000，快照沿用 codec 4,000,000
字符上限，并额外限制单条编码记录不超过 8 MiB。

## 2. 幂等与原子推进

受理按 `(owner, idempotencyKey)` 门控。相同固定输入返回原 Run 身份、原期限和当前状态；不同用户输入、模型、工具集合或限制返回冲突。
新的临时 executionId、trace、授权 grant 引用、接收时刻、上下文快照 ID 不改变业务意图；原实际材料通过载荷摘要保护。
模型特性、工具标签／权限／网络范围等集合使用显式排序投影，避免不同 JVM 的 Set 遍历顺序改变语义摘要。
重放重新检查原快照的当前资格，不能用新准备的较宽权限覆盖原上限。

`advance` 在同一物理 DataSource／事务中完成：

1. 锁定原 Run，按 owner 隔离读取，重新执行当前授权回调。
2. 检查命令防重；相同命令键和语义返回原不可变回执，异义冲突。
3. 检查有效租约、fence、expectedVersion、阶段、轮次和原总期限。
4. 固定新步骤、更新检查点和累计计划数、保存下一次唤醒及命令回执，全部提交或全部回滚。

Schema 预检和受理大快照编码在有界 JDBC Scheduler 上、进入事务前完成；事务中不调用模型／工具。
推进只允许准备模型→等待模型→等待工具→下一轮准备等显式阶段，以及审批等待、待核对和终态；不能跳轮或把 TOOL 子操作当成 MODEL
恢复。 新增步骤的固定版本、参数 Schema 和容量受原工具集合约束，不能按全局工具名回退。 PENDING_RECONCILIATION 必须保存
UNKNOWN 错误事实；不得将未知业务结果写成已知失败。

推进回执是原提交事实，可能早于当前 Run 版本。恢复者遇到重放后应重新读取当前检查点，再决定下一动作，不能按旧回执覆盖新状态。

## 3. 稳定子操作映射

子执行 ID 由 Run ID、roundIndex、MODEL／TOOL 及 modelCallId 按独立 v1 长度前缀摘要派生，operationKey 再用独立命名空间派生。
MODEL 每轮唯一；TOOL 的 callId 在 Run 内唯一。相同命令、不同领取次数和服务重建都复用同一映射。
重复自然身份、更换参数或绕过轮次控制会被拒绝，不能用新的临时子 ID 绕开防重。

MODEL 步骤固定该轮实际 ContextSnapshot；TOOL 步骤固定原 ToolCall，执行绑定和策略仍取同一 Run 快照中的准确条目。 步骤列表与
Run 累计计划数核对，并逐条验证载荷摘要、归属及稳定身份。

**步骤计划存在不代表子 Invocation 已受理。** S4-05 应使用此处保存的 childExecutionId／operationKey，在原统一受理系统中可靠创建子
Invocation， 固定 parentExecutionId=runId、共享 trace、收紧权限和期限。受理不明时查询原身份，不能再分配新的逻辑操作键。
子结果／审批／核对事实以原执行存储为准；本批不复制其权威状态，不验证来源结果的业务正确性。 后续 Coordinator
必须核验模型提议与子结果的权威来源、完整性、配对和结果引用，再提交推进命令；存储阶段检查不能代替这项校验。

## 4. 工作项、租约与唤醒

每个 Run 有一个独立耐久工作项，与根受理同事务创建；它用于唤醒 Run，不代替原 Invocation DISPATCH Outbox。 领取最多 32
个候选，在短事务中按主键持锁并重新检查到期／租约；每次领取递增 fence。数据库 `CURRENT_TIMESTAMP(3)` 仲裁期限及租约， 不使用
Worker 本地时钟争夺所有权。租约长度为 1 毫秒—1 分钟；旧或过期持有者不能续租或提交新检查点。
授权后返回原已提交回执不需要旧租约继续有效，因为重放不产生写入或新的子操作。

推进事务释放租约并设置下一次唤醒；不指定 nextWakeAt 表示等待外部事实，但最迟在原总期限唤醒，避免永久遗留。
`wake` 仅更新原工作项的信号版本及到期时间，不创建步骤或子调用。领取后到达的信号使推进后工作项立即可领取，避免 park 覆盖新信号。
重复通知只会产生额外可合并的唤醒，不重复业务操作。终态禁用工作项，后续 wake 不重开 Run。 期限已过不能再计划模型／工具，仅允许超时／取消收尾；这些
Run 控制事实不会改写子 Invocation 中保留的 UNKNOWN 业务／费用事实。

目前没有自动领取循环、Redis 提示、Run 事件游标或通用 Outbox 新类型。S4-03 接入 Turn／事件时必须保持原子提交， 不能串联两次异步
Store 调用来模拟 Run 与 Turn 的原子受理；S4-05 再启用明确门控的 Run Worker。 S4-03 还须把会话／Turn
目标纳入聊天提交的幂等意图与原子受理，防止同一客户端键跨会话附着原 Run；本批内部根尚未绑定会话。

## 5. 授权与装配边界

持久化复用 `arte.ai-new.data-source-bean`、`newAiPersistenceScheduler`、独立 MyBatis 工厂及白名单 `ExecutionRecordCodec`。
`NewAiToolChatConfiguration` 仅在 `arte.ai-new.enabled=true` 且 `arte.ai-new-tool.enabled=true` 时装配，不执行
SQL、不建表、不启动线程循环或调用外部系统。 缺少真实数据源或依赖时失败，不注册空实现。

`accept` 和 `advance` 的授权回调不可为空，在真实写事务中执行，重放也执行。宿主后续必须用当前 RBAC／空间／AI 资格及治理锁实现回调，
不能传空操作充当已授权入口。内部 `find`／`snapshot`／`steps` 不授予用户读取权；领取和 wake 仅供受信基础设施使用。 本批没有公共
Run API、登录身份捕获、默认允许的宿主授权或生产开放开关。

快照新增 v1 根别名 `tool-chat-run-input`、`tool-chat-run`、`tool-chat-step`、`tool-chat-run-receipt`， 仅登记 MODEL／TOOL
两种步骤输入别名。拒绝未知／非整数版本；原 Invocation、Turn 和工具执行快照版本不变。

## 6. 数据库部署与验证

新增四张表：`arte_ai_tool_chat_run`、`arte_ai_tool_chat_run_work`、`arte_ai_tool_chat_run_step`、
`arte_ai_tool_chat_run_command`。

- 新环境使用 [完整基线](scripts/arte-ai-new-ddl-mysql.sql)，不再执行同一批增量。
- 已完成 S3 的环境停写、备份后显式执行 [S4-02 增量](scripts/arte-ai-tool-chat-run-migration.sql)。脚本不自动补表、不掩盖重复执行或同名结构冲突。
- 本轮只在隔离测试库验证，没有连接／修改配置业务库，没有执行该环境的 DDL、数据回填、配置变更或应用重启。

验证覆盖并发受理／推进唯一、跨租约命令重放、重新建 Store 恢复、精确小数、失租拒写、唤醒与 park 竞争、期限、审批／UNKNOWN 等待、
轮次与容量上限、授权撤销、跨范围读取、载荷损坏及两个写入故障点的整体回滚；独立 JVM 验证摘要稳定性。新装基线与增量内容及升级路径同时核对。
本批及相邻回归命令、结果与未运行项见 [实施记录 §33](TOOL_IMPLEMENTATION.md#33-s4-02toolchatrun-存储与推进基础2026-10-20)。

后续仍分别实施 S4-03 Turn／Outbox／历史兼容、S4-04 Run 总预算与子账户、S4-05 真实循环／审批／取消／恢复、S4-06 前端。
当前存储测试不代表已完成自动工具聊天、实际供应商验收、MySQL 多节点或生产性能验收。
