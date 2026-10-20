# AI TOOL：本人确认与独立审批（S3-01／S3-02）

更新：2026-10-20。已完成同库等待／决定事务、公开审批 API、当前宿主资格、待办与决定页面，沿用现有身份、RBAC、空间及执行链路。S3-02
提供本地免费只读人工控制；S3-03 已扩展一个默认关闭的受控领域写工具，S3-04 已实现 [原领域回执自动核对](TOOL_OUTCOME.md)
，人工证据／核对界面留在 S3-05，见 [业务写工具说明](TOOL_WRITE.md)。

正式 `text.countCodePoints@v1` 仍为 30 秒总期限，不能容纳人工窗口与至少 30 秒执行余量，选择人工模式会明确拒绝。绑定或请求不能延长工具契约。S3-02
的长期限只读测试不代表正式文本工具已开放人工模式；S3-03 新增独立的工作空间写工具契约，在隔离库验证真实领域提交，仍默认关闭。业务配置未修改。

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
秒有效窗口、独立审批需明确容纳 15 分钟窗口等产品选择规则， 已由 S3-02 可信政策／预览入口实现；不改变现有文本工具的 30
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
- Attempt 创建前重新读取 APPROVED 和固定快照／摘要；材料损坏或变化拒绝。批准者不改原 request／scopes， Worker
  以原发起者重新授权，网关在进入适配器前重验当前 RUNNING Attempt、fence、存活租约和 APPROVED；审批资格不能作为执行资格。

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
   Worker 或恢复只停止自动扫描，不取消已有调用。 手动 `expire`
   使用相同事务且可跨实例重复调用。开关同时控制审批入口注册及人工受理政策，不授予任何用户权限。关闭后公开审批入口不可用，已有等待事实保留；关闭空间
   AI 后，原发起者仍可按当前读取／控制资格查看和取消历史，不能据此继续执行。
5. 原 NONE 调用在未安装审批表时仍可按 S2 受理／重放，不因本批自动访问新表。缺审批表的等待受理明确失败并回滚全部写入。

本次只提供代码、SQL 与测试，未执行任何业务数据库 DDL、修改业务配置、重启应用或连接外部模型。 后端验收使用隔离 H2 的真实
JDBC／MyBatis 事务及虚拟时钟；不代表已通过生产 MySQL、多节点部署或性能压测。
验证清单及结果见 [实施记录](TOOL_IMPLEMENTATION.md) §27—28。

## S3-02 公开入口与当前资格

- `getApproval`：以审批编号或原调用编号二选一定位，先按当前 actor／Scope 查参与者索引，再验证当前资格；编号不授予读取权。
- `listPendingApprovals`：参与者索引分页，默认 10、最大 20，稳定键游标。逐条复核当前资格；过滤撤权记录后允许空页携带
  nextCursor，客户端继续扫描。列表不返回参数，详情返回固定工具／配置版本、原文 JSON 参数与配置、风险、原发起主体、固定审批主体、实际到期和原总期限。
- `decideApproval`：只接受 Scope、approvalId、字符串 expectedVersion、subjectDigest、APPROVE／REJECT；必须携带
  Idempotency-Key。不接受 owner、actor、审批人名单、scopes 或 ExecutionContext。HTTP 202 只代表决定事实，批准后查询原调用结果。

本人确认需要原发起者当前 `aiTool:invoke`、AI INVOKE 和工具 requiredScopes；读取仅需当前 `aiTool:list`、AI
READ。独立审批人必须在固定稳定主体集合中、与原调用同一有效 Scope、仍为有效账户／空间成员，且具有当前 `aiTool:approve` 及 AI
使用资格。独立审批不允许本人，admin 不绕过显式 approve 操作。DATABASE 模式关闭空间 AI
后，独立审批人不再有审批读取／决定资格，原发起者按原规则保留历史读取与取消资格。

可信配置在受理时固定名单，随后新增配置主体不进入旧记录，移除固定主体后其决定资格失效。应用层及决定事务都重验当前资格；同键重放也不跳过。事务按稳定主体排序获取治理门锁，再获取租户／空间锁，与现有公共治理顺序一致。批准不替换原发起者或扩充原
scopes。

```properties
# 以下是部署示例，不是已修改的业务配置。沿用已有 AI、TOOL、执行及 Worker 开关。
arte.ai-new-tool-approval.enabled=true
arte.ai-new-tool-approval.approvers[0].tenant-id=tenant-example
arte.ai-new-tool-approval.approvers[0].workspace-id=workspace-example
arte.ai-new-tool-approval.approvers[0].subject-ids[0]=stable-subject-id
```

每个 Scope 最多 32 个稳定主体，最多 256 个 Scope，不建新身份体系。本人确认不需要独立名单；独立模式必须有其他合法主体，并逐个验证当前资格，缺失或无法验证时拒绝。独立审批需完整
15 分钟窗口与至少 30 秒余量；本人确认窗口为 min (2 分钟, 原期限减 30 秒)
，必须为正。预览摘要覆盖模式、排序后的固定主体与窗口政策，绝对时间由可靠受理固定；同键并发重放使用获胜记录的原 Plan，不重置窗口。

## S3-02 页面与决定恢复

工具中心新增“确认与审批”，调用面板在 WAITING_APPROVAL 展示同一决定组件。实际参数与配置以文本呈现，不执行 HTML、不经 JS
Number 转换；决定前再次核对固定内容。倒计时以服务端时间校准，最终到期以数据库事务为准，到零隐藏新决定按钮。决定成功提示与执行成功分开。

发送前先将完整原命令及原幂等键写入按账户／Scope／审批编号隔离的
sessionStorage。超时／刷新后只允许“继续确认原决定”，不自动发起相反决定；存储损坏或保存失败时不发送新命令。切换账户／空间、卸载及重新读取取消旧请求，迟到响应不能覆盖新上下文。sessionStorage
只覆盖当前浏览器会话，清除会话后仍可通过审批编号查询权威状态。

## S3-02 数据库与权限增量

### 先区分旧版同名审批表与 S3-01 审批表

部署前执行只读 `scripts/arte-ai-tool-approval-schema-check-mysql.sql`。`arte_ai_tool_approval` 表名存在不代表已经安装
S3-01；audience 表有自己的 Invocation 外键，也不依赖旧审批表列，因此单独建 audience 成功不能证明前置条件成立。

| 结构               | 识别字段                                                                                                | audience-backfill 是否适用                             |
|--------------------|---------------------------------------------------------------------------------------------------------|--------------------------------------------------------|
| 参考项目旧版审批表 | id、request_id、call_id、status、display_arguments、DATETIME expires_at                                 | 不适用；这是旧审批，不具有新 Invocation 的固定执行材料 |
| S3-01 同库审批事实 | invocation_key、owner_key、approval_key、state、version_no、BIGINT expires_at、payload_digest、snapshot | 适用于已有此结构且需补参与者索引的记录                 |

发现旧同名表时，不能把 status 简单换成 state，也不能补几个空列继续回填。旧表缺少固定 owner、Invocation、完整参数／配置／授权上限及可信审批
Plan，不应将旧批准赋予新调用执行权。

仅供已停止旧消费者并备份的开发环境：先核对引用旧表的外键、程序及归档目标不存在，再保留旧数据并释放原表名：

```sql
RENAME TABLE arte_ai_tool_approval TO arte_ai_tool_approval_legacy_before_s3;
```

随后显式执行 `scripts/arte-ai-tool-approval-migration.sql` 创建新的 S3-01
表。上述重命名不会迁移旧记录；归档中的旧待审批应按旧系统规则处理，新功能重新预览和可靠受理。不得在仍需同时运行旧消费者的环境直接重命名；该环境需要另外设计表名隔离方案。

audience 若已按 S3-02 安装，不重复执行建表脚本；核对其结构及是否存在孤立行。新 S3-01 表为空时无需执行
audience-backfill，旧归档记录不在回填范围。存在真实 S3-01 记录时才运行回填，随后核对原 owner 及固定审批主体索引。

2026-10-20 对项目配置的开发库仅作只读核查：原 approval 为旧版结构、共有 2 行；audience 已有三列结构、0 行，未查到指向旧
approval 的外键。此为当时环境观测，执行任何 DDL 前仍须复核。此次没有修改业务数据库；新只读检查脚本已对该库验证，归档／新建／回填均未执行。

### 标准安装与回填

全新库的 `arte-ai-new-ddl-mysql.sql` 已含审批及 audience 表。已有 S2 库先执行 S3-01 的 approval-migration，再执行 S3-02 的
audience-migration；已有 S3-01 库从以下步骤开始：

1. 停写、备份并统一升级读写节点／消费者，执行 `scripts/arte-ai-tool-approval-audience-migration.sql`。
2. 有符合上述新结构的 S3-01 审批记录时执行 `scripts/arte-ai-tool-approval-audience-backfill-mysql.sql`（MySQL
   8.0+），按快照固定主体回填；核对每条审批至少含原 owner，以及固定独立审批主体对应索引。脚本不授予权限。全新空库不需要回填。
3. 执行 arte-app 的 `scripts/arte-ai-tool-approval-rbac-dml-mysql.sql`，只增加 aiTool:approve
   操作定义；现有管理机制向实际审核者明确分配此操作，不自动授予角色或用户。
4. 配置足够长且经过发布校验的只读工具契约、服务器总期限及可信名单，再开启人工模式。仅安装表或增加审批操作不等于可使用人工模式。

`arte_ai_tool_approval_audience` 只作参与者定位投影，主键为 actor_key／invocation_key，含 pending
及其复合索引。等待事务原子写入原主体和固定审批主体；决定、取消及到期原子清除
pending，历史行保留。查询同时验证权威审批状态、固定材料和当前权限，不把该索引视为授权。避免待办请求扫描已结束的历史记录。

本批没有执行业务库 DDL／回填、权限分配或业务配置修改；MySQL 回填脚本未在真实 MySQL 执行。H2 事务回归不能替代 MySQL
部署、多节点及性能验收。
