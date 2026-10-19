# AI TOOL S3-01：审批等待状态与决定事务

本批完成可信后端事务基础，沿用 `com.arte.ainew`、同一物理数据源、通用 Invocation／事件／Outbox 和现有分类调度。 不开放审批
HTTP、审批界面、平台审批人授权或业务写工具。原 S2 免费只读公开入口、宿主执行授权和网关仍要求 `approvalMode=NONE`。
因此本批完成后，现有文本工具不会自动进入审批，批准事实也不会绕过原网关限制执行工具。

## 内部调用与授权责任

| 契约                                                              | 行为                                  | 必须由可信调用方提供的事实                                                                 |
|-------------------------------------------------------------------|---------------------------------------|--------------------------------------------------------------------------------------------|
| `ToolAdmissionStore.awaitApproval(snapshot, plan, authorization)` | 保存固定执行材料、审批和等待状态      | 服务端已校验的 Schema／绑定／风险及审批政策；事务内当前使用、资源及该审批模式资格          |
| `ToolApprovalStore.find(owner, invocationId)`                     | 内部定位审批；跨归属返回空            | 对外展示前另行验证当前读取资格；此端口不是 HTTP 权限边界                                   |
| `ToolApprovalStore.decide(command, actor, authorization)`         | 同库提交批准／拒绝与队列／完成        | actor 来自当前可信身份；回调在物理事务内复核当前身份、空间成员、操作及资源权限，重放亦执行 |
| `ToolApprovalStore.expire(limit)`                                 | 每条独立事务处理到期等待，批量 1～256 | 系统恢复端口，不能绑定用户请求体或公开为用户命令                                           |

`owner` 永远是原调用的租户、空间、稳定主体。决定者必须属于同一租户／空间；本人确认只允许原主体；
独立审批只允许政策固定的其他稳定主体，不允许本人。固定集合只能限制资格，不能证明审批人当前仍是成员或拥有操作权限。
宿主当前成员／审批操作能力缺失时，后续入口必须拒绝独立审批，不能传空回调或降级为本人确认。

审批 `Plan` 包含模式、稳定审批主体集合、固定 expiresAt 和 executionMargin；不能直接绑定 HTTP。 事务层要求模式匹配固定执行策略、独立审批人不含本人，以及
`receivedAt < expiresAt <= originalDeadline - executionMargin`，余量至少 30 秒。 本人确认默认 2 分钟总期限得到 90
秒有效窗口、独立审批需明确容纳 15 分钟窗口等产品选择规则， 由 S3-02 可信政策／预览入口实现；本批不改变现有文本工具的 30
秒总期限或服务器上限。

## 状态和决定

```text
ACCEPTED → WAITING_APPROVAL → QUEUED → 原执行链路
                        ↘ FAILED / TIMED_OUT / CANCELLED
PENDING → APPROVED / REJECTED / EXPIRED / CANCELLED
```

新等待调用的 Invocation.version 为 1，审批 version 为 0；批准／拒绝后分别增加为 2 和 1。
`ToolApprovalCommand.expectedVersion` 比较审批版本，不是 Invocation 版本；Invocation 在同一事务当前行锁下核验
WAITING_APPROVAL 和无 Attempt。 命令还携带稳定审批 ID、原执行 ID、subjectDigest、APPROVE／REJECT 和幂等键。actor 不取自命令正文。

审批摘要覆盖原 Invocation／owner、固定 requestDigest、原 deadline、风险和人工政策；requestDigest 已覆盖参数、 工具／绑定／Schema
版本、配置、目标及 scopes 上限。集合以稳定顺序计算摘要；读取重验原始载荷字节摘要及索引投影。 不保存 Secret 值，不让脱敏展示替代真实参数摘要。

- 等待事务一次提交受理索引、Invocation、执行快照、PENDING、ACCEPTED／WAITING_APPROVAL 事件和 EVENT Outbox。 没有
  DISPATCH、Attempt、Worker 租约、执行队列预留或预算预留。
- 批准先核验当前资格、原摘要、审批版本、PENDING、窗口及固定绑定治理，再原子保存 APPROVED、QUEUED、状态事件、 唯一 `DISPATCH/0`
  、队列容量及分类。队列满、节点不就绪或写入失败整体回滚，保留 PENDING，可在原窗口内重试。
- 新队列的 queuedAt／queueDeadline 从批准排队时计算，仍受原逻辑 deadline 限制；审批等待不消耗 S2 队列超时。
- 拒绝使用通用 Attempt 前完成路径保存 FAILED／`TOOL_APPROVAL_REJECTED`，没有工具结果或虚构 Attempt。
- 原通用取消同步关闭待审批记录为 CANCELLED；已有批准事实保留，后续取消排队调用仍按原通用控制执行。
- 到期保存 EXPIRED 与 TIMED_OUT／`TOOL_APPROVAL_EXPIRED`。决定、取消和到期以 Invocation → approval 顺序取锁，最多一个待审批决定生效。
- 幂等键作用于该 Invocation 的审批决定；摘要包含决定、版本、主体和审批摘要。同键同命令返回原回执；
  同键异义、不同键的相反决定、过期／旧版本命令拒绝。重放先重新验证当前资格，不延长期限或重复排队。
- Attempt 创建前重新读取 APPROVED 和固定快照／摘要；材料损坏或变化拒绝。批准者不改原 request／scopes，
  后续公开审批执行接线仍须以原发起者重新授权，不能把审批资格作为执行资格。

决定、终态、事件和 Outbox 的写入都在同一 DataSource 的物理事务内。事务回滚不唤醒 Worker；提交后提示失败仍由既有 Outbox 扫描恢复。
数据库／材料损坏通过错误传播；到期批次中单条错误保留原事实并记录稳定键告警，继续处理其他记录，不伪造修复或 ACK。

## 部署与恢复

1. 全新库使用 `scripts/arte-ai-new-ddl-mysql.sql`；已有 S2 库在停写、备份并确认目标表不存在后， 显式执行一次
   `scripts/arte-ai-tool-approval-migration.sql`。两者审批表结构相同，不要对同一库重复执行两份。
2. 新表 `arte_ai_tool_approval` 一次调用一行，带唯一审批 ID 和 `(state, expires_at, invocation_key)` 到期索引。 决定幂等回执复用通用
   `arte_ai_operation`，审批快照使用 `tool-approval v1` 和 `tool-approval-receipt v1`。
3. 无审批执行快照保持 `tool-execution-snapshot v1`；带审批政策写 v2，禁止仅改 schemaVersion 降级。 旧
   Invocation／事件保持原编码结构，新状态无法由旧消费者识别；开放任何审批受理前须统一升级共享库所有读写节点和消费者。 不能只依赖
   S2 工具 manifest 判断旧节点是否支持审批状态，也不能直接回滚到不支持该状态的程序。
4. 审批事务 Store 随现有 TOOL 执行装配，但不启动 IO。自动到期恢复需同时开启既有执行 Worker 和新增开关：

   ```properties
   arte.ai-new-tool-approval.enabled=true
   arte.ai-new-tool-approval.poll-interval=1s
   arte.ai-new-tool-approval.batch-size=64
   ```

   新开关默认 false；pollInterval 允许 100ms～1min，batchSize 允许 1～256。 串行恢复每批完成后延迟再扫描；单批故障不会停掉后续扫描。关闭
   Worker 或恢复只停止自动扫描，不取消已有调用。 手动 `expire` 使用相同事务且可跨实例重复调用。开关不授权任何用户，也不开放审批受理
   HTTP。
5. 原 NONE 调用在未安装审批表时仍可按 S2 受理／重放，不因本批自动访问新表。缺审批表的等待受理明确失败并回滚全部写入。

本次只提供代码、SQL 与测试，未执行任何业务数据库 DDL、修改业务配置、重启应用或连接外部模型。 后端验收使用隔离 H2 的真实
JDBC／MyBatis 事务及虚拟时钟；不代表已通过生产 MySQL、多节点部署或性能压测。
验证清单及结果见 [实施记录 §27](TOOL_IMPLEMENTATION.md#27-s3-01审批等待状态与决定事务2026-10-19)。
