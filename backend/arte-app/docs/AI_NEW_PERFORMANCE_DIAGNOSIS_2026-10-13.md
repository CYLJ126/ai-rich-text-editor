# 新 AI 页面性能排查（2026-10-13）

主要耗时集中在数据库授权读取链：读接口使用写门控、同一事务重复取得治理锁、一个 HTTP
请求多次重新加载完整控制面。前端串行加载和请求扇出进一步放大这些开销。另有已确认的结算表列名不匹配，使成功调用的预算保持
RESERVED，SSE 持续到连接期限。

结算问题修复进展：2026-10-13 21:31 已完成列名迁移，21:32:26 Worker 自动结算成功；目标预留已 SETTLED，账户 held=0，7
个成功调用均已结算，迁移后没有新增 insertSettlement
1054。详见[修复验证记录](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/docs/AI_NEW_SETTLEMENT_REPAIR_2026-10-13.md)。

只读授权与写门控重构进展：已实现一次订阅内的轻量授权快照和非锁定读取，并消除写门控批次内重复治理查询。查询次数与安全边界验证详见[重构记录](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/docs/AI_NEW_READ_AUTHORIZATION_2026-10-13.md)
。实际接口耗时需重启后复测。

依据：`backend/log` 日志、当前工作区源码，以及使用项目现有连接配置执行的只读 SELECT
1、表结构和预算状态查询。这里不把用户给出的接口时间当作服务端阶段计时；当前日志未包含对应 17.45 秒提交的完整 CHAT_ACCEPTED
链路，也未给所有接口提供统一开始／结束日志。

**量化证据**

- 对 debug.log 的一次快照，统计到 1,716 条 MyBatis `Preparing`，其中 1,145 条包含 FOR UPDATE。这不是全部 JDBC SQL
  数，也不是某一个请求的 SQL 数。
- 按数据库线程匹配 `selectIdentityByName` 到 `budgets` 读取完成，得到 65 个完整授权／配置读取循环。每个循环都有 24 条可见
  SQL；每次同一组账号、身份、租户、空间及成员锁取得两遍。循环耗时中位数 1,881 ms，最大 2,894 ms；结束位置在预算读取返回，尚未计入随后的事务提交。
- 日志中普通查询多数约 39 ms；账号锁 SELECT 的最大值 1,777 ms。其他锁定 SELECT 的匹配样本多数约 39
  ms。因此账号热点锁等待是有直接证据的瓶颈，不能把所有慢请求解释成单条业务 SQL 执行慢。
- 同一个连接串行执行 SELECT 1，两轮各 20 次：中位数分别 34.3 ms、43.9 ms。这个数字包含通信和数据库处理，并非纯网络
  RTT；即便没有复杂查询，24 次串行读取也约需 0.82～1.05 秒，未计隐藏 JDBC、事务及锁等待。

**接口对应关系**

以下“完整读取次数”是按当前源码推导的普通成功路径次数，不是通过统一 HTTP trace 实测的次数。每次完整读取包含上述 24 条可见
SQL，且另外还有 JdbcPrincipalIdentityStore 的 JDBC 读取。

| 接口                     |  用户观测 |                      完整授权／配置读取 | 主要关联问题                         |
|--------------------------|----------:|----------------------------------------:|--------------------------------------|
| turnsForChat             |  17.45 秒 |        空历史新提交约 13 次；有历史增加 | 1、2、3、4；历史逐轮读取另增耗时     |
| discoverChatOptions      |  10.83 秒 |                                    5 次 | 1、2、3、4、6                        |
| getBudget                |  12.82 秒 |                                    4 次 | 1、2、3、4、6                        |
| getConversation          |   5.15 秒 |                                    2 次 | 1、2、3、4、5                        |
| listConversations        |   3.64 秒 |                                    2 次 | 1、2、3、4                           |
| queryTurnsOfConversation |      4 秒 |                                    2 次 | 1、2、3、4、5                        |
| getInvocationStatus      |  10.73 秒 | 2 次，加调用／Attempt／Reservation 读取 | 1、2、3、4、5、7                     |
| getInvocationResult      |   4.92 秒 |                  2 次，加调用和结果读取 | 1、2、3、4、5                        |
| watchInvocation          | 约 1 分钟 |      建连和流订阅多次读取，随后周期重查 | 1、3、7、8；需区分建连与连接持续时长 |

**1. 读接口使用排他写锁，形成同一账号的串行队列（P1，已确认）**

证据：21:05:30.138 的日志记录 `SELECT id FROM arte_rbac_user WHERE id=? FOR UPDATE` 耗时 1,777 ms；其他多条同样 SQL 耗时
1,000～1,689
ms。[error.log](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/error.log:7)。[DatabaseAiControlService](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java:174)
的授权／配置读取调用
`WorkspaceWriteGuard.inCurrentTransaction`；[PlatformAiControlMapper.xml](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/main/resources/com/arte/app/platform/persistence/mapper/PlatformAiControlMapper.xml:10)
的配置、预算查询也固定带 FOR UPDATE。

原因：会话、预算、状态、SSE 等并发读取都先锁账号和同一空间，在返回配置前一直持有锁。前端把请求并行发送，数据库仍将它们在热点锁处串行化；租户／空间锁还可能使同空间不同账号互相等待。

改法：分离只读授权快照和写事务门控。展示型读取使用一次短生命周期的一致快照／批量事实查询；配置修改、预算写入、受理提交及派发的关键副作用保留最终资格检查与需要的锁。不能仅机械删除所有
FOR UPDATE：要明确撤权语义，并通过权限版本／epoch 和关键写事务的最终检查保证授权边界。

**2. 同一授权事务中，治理锁取得两遍（P1，已确认）**

证据：[DefaultWorkspaceWriteGuard](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-core/src/main/java/com/arte/core/application/access/DefaultWorkspaceWriteGuard.java:57)
先执行 `locks.ranges`，随后 `requireInCurrentTransaction`
；后者进入 [WorkspaceAccessSupport.snapshotCurrent](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-core/src/main/java/com/arte/core/application/workspace/WorkspaceAccessSupport.java:194)
，再次取得主体、租户、空间、成员锁。[WorkspaceGovernanceLocks.subject](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-core/src/main/java/com/arte/core/application/workspace/WorkspaceGovernanceLocks.java:70)
记录已持有状态后仍执行 `preferences.lockSubject`，没有跳过 SQL。65 个读取循环均重复同样的锁序列。

原因：锁状态用于校验顺序，却没有把第一次锁定读返回的可信事实传给后续授权。MyBatis 一级缓存又配置成
STATEMENT，因此这些重复调用确实再次访问数据库。

改法：写门控首次锁定读取时构造事务内可信事实，后续动作判定复用；同事务修改相关事实时失效或重读。保留全范围排序、回滚传播和权限校验，不能跨事务缓存授权结果。可先为单次门控增加
SQL 次数断言，锁定主体／父范围不要重复发 SQL。

**3. 一个 HTTP 请求反复执行完整授权和配置加载（P1，已确认）**

证据：[ExecutionContextFactory](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/context/ExecutionContextFactory.java:131)
首次解析数据库授权；[AdmissionAuthorization.require](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/auth/AdmissionAuthorization.java:105)
在 Service 入口又调用同一 resolver。预算服务依次
require、grant、configuration.budget：[BudgetAccountQueryService](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/control/BudgetAccountQueryService.java:32)
。发现配置又执行
require、configuration、grant、discoverBindings：[ChatConfigurationQueryService](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/control/ChatConfigurationQueryService.java:33)
。数据库 resolver 本身走 authorizedConfiguration，并读取发布配置、空间配置和所有主体预算，即使只是查询会话状态。

原因：上次修改减少了 FixedControlCatalog 单次校验内部的回查，但 HTTP
工厂、业务服务、预算与发现流程仍各自完整读取。空历史聊天还多次解析绑定，并在聊天服务、历史加载、上下文组装、协调器中再次复核。

改法：为一次读取操作传递明确的 AuthorizedSnapshot，统一包含
scope、subject、permissions、配置版本、binding、预算定义；展示读在同一操作中复用，关键提交／派发处再做最终复核。resolve
拆成轻量资格解析和按需配置解析，普通会话／状态读取不必加载全部模型目录及预算。discover 一份快照完成过滤；getBudget
一份快照完成资格和预算定义校验，再读账本。

**4. 数据库单次往返已有几十毫秒，串行 SQL 把固定开销放大（P1/P2，已测量）**

证据：只读 SELECT 1 两轮各 20 次中位数 34.3／43.9 ms；日志多数点查约 39
ms。[debug.log](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/debug.log:1328) 开始的一次授权循环包含 24 条可见
SQL。JdbcPrincipalIdentityStore 的 `find` 和 `matchesAccount` 还有未纳入 Preparing 统计的 JDBC 读取。

原因：即使各条 SQL 都命中索引，大量串行请求也先累积通信开销，再叠加账号锁等待。24 × 34～44 ms 已接近 1 秒；2／4／5／13
次完整读取自然达到数秒到十几秒。现有证据不能把这些时间全部归因于网络，但表明仅优化某条 SQL 算法不足以解决问题。

改法：优先减少往返次数，批量读取公共授权事实、合并账号与身份读取、按需查询；再比较应用与数据库同网络部署下的 SELECT 1 和接口
p95。已发布不可变目录可按明确版本缓存，发布 head／成员权限仍检查当前状态；账户金额和权限决定不做无版本的长时间缓存。

**5. 历史页面存在串行瀑布和逐条状态／结果读取，并等全部完成才展示（P1，代码已确认）**

证据：[useChatSession.readHistory](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useChatSession.ts:667)
顺序执行 getConversation → queryTurns → 必要时再次查询最后一页 → Promise.all 逐个
readInvocation；[readInvocation](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useChatSession.ts:362)
又顺序执行 status → result；`setTurns` 在 Promise.all 之后（第 715 行）。每页 10 轮，每轮可能检查选中和最新两个
invocation。用户给出的 status 10.73 秒与 result 4.92 秒，在需要结果的路径上约为 15.65
秒，且位于会话与轮次读取之后；这只是顺序成本示意，实际并发、缓存及锁等待会改变总耗时。

原因：聊天气泡的基础历史也被最慢的状态／结果请求阻挡；增加并发数量会进一步争抢第 1 点的数据库锁。

改法：轮次页到达即渲染问题和占位回答，逐个补全结果；增加按会话页面批量返回状态／结果摘要的读取接口，一次授权覆盖同一 owner
的有界批次。提供最近轮次／倒序游标接口，避免先读第一页才能定位最后一页；仍返回会话版本并保留冲突处理，不能去掉版本校验。对未完成读取限并发，已确定终态结果按版本复用。

**6. 同一页面的多个组件重复取详情、预算和目录（P2，代码已确认，增量需按请求 trace 测量）**

证据：[useConversations.select](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useConversations.ts:103)
取详情，随后聊天历史又取同一详情。配置表单的 [useSelectedBudget](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useConfigurationSelection.ts:81)
与聊天 session
的 [loadBudget](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useChatSession.ts:630)
各自请求相同预算。ChatConfigurationForm 按 `selectedId`
重建：[index.tsx](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/pages/AI/ChatNew/index.tsx:314)，目录通常按
workspace 而非 conversation 变化。置顶记录 effect 又对全部 pins 逐个取详情，最多 100 条（第 102
行）。这些触发取决于页面配置、会话切换和置顶数量，不能认定当前每个请求都发生两遍。

原因：各 hook 仅在自身实例内去重，缺少同账号、scope、资源维度的共享进行中请求；部分工作区数据随会话切换重新加载。

改法：页面级共享请求协调与按 userId／tenantId／workspaceId／budgetRef／conversationId
的去重。目录提升到工作区层；相同预算请求合并，预算变更通知时失效并重查；详情从已取得的元数据开始，版本冲突后重读。置顶优先复用当前列表，缺失项批量查询并限制并发。共享请求要保留独立取消和切换
scope 后丢弃旧结果的行为。

**7. SSE 的总时长与响应延迟需区分；内部重复授权和前端等待回调仍会拖慢首段（P1，已确认）**

证据：[info.log](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:102) 记录 SSE_READY=4,506
ms、SSE_FIRST_TEXT_ENQUEUED=11,926 ms、SSE_END_ON_COMPLETE=60,007 ms（第 126 行）。这是已有调用的事件重放首段，不是供应商首次生成
Token
的时间。[NewAiInvocationController](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/web/controller/NewAiInvocationController.java:145)
明确设置最长 60 秒连接。事件 watch 和文字 watch 各有一条每 10
秒的重新授权流：[DefaultExecutionEventService](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/execution/DefaultExecutionEventService.java:132)
和第 219 行；事件补读之前还授权。前端 TERMINAL 回调 `await refresh(id)`（useChatSession 第 549 行），该刷新包含慢状态／结果／预算／历史读取；SSE
解析按顺序等待回调完成。

原因：DevTools 的一分钟显示连接生命周期；但 4.5 秒建连和 11.9 秒首个重放文字确实慢。两条授权定时流重复读取同一个主体，增加共享数据库压力；慢
HTTP 刷新还阻塞后续帧处理和耐久游标推进。

改法：分别监控 HTTP 建连／首帧／首文字／连接结束。每个连接共享一个受限的重新授权协调器，同时保留周期撤权检查与事件归属验证。SSE
消费先处理文本及有界事件通知，再合并后台 HTTP 刷新；保持重复事件处理幂等、结果获取失败的重试与耐久游标恢复语义，不能简单提前推进游标而丢失刷新任务。

**8. 结算表结构不匹配，使 RESERVED 永远等不到结算通知（P0，数据库已核实）**

证据：[debug.log](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/debug.log:4980) 的
BudgetMapper.insertSettlement 报 sqlState=42S22、vendorCode=1054，写入列包含 workspace_snapshot。只读 SHOW COLUMNS 确认实际
arte_ai_settlement 仍是 snapshot，没有 workspace_snapshot。日志中同一已 SUCCEEDED 的调用在 21:04、21:06、21:08
及之后持续重试；只读核查其预算预留状态为
RESERVED。[DefaultExecutionEventService.drain](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/execution/DefaultExecutionEventService.java:279)
只有预算 outcomeAvailable 后才结束；前端也继续观察 RESERVED。

原因：生成结果已成功，结算事务回滚；Worker 等 Outbox 租约到期后重新领取，形成持续重试。它主要解释预算无法落定、SSE
到期及后续反复观察，也消耗后台数据库资源；不是所有慢查询的唯一原因。

改法：发布与表结构对齐，已有受限迁移 [arte-ai-new-settlement-snapshot-migration.sql](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/scripts/arte-ai-new-settlement-snapshot-migration.sql:1)
。确认旧列存在、新列不存在且旧版本进程已退出后重命名；恢复幂等结算并验证 RESERVED → SETTLED／RELEASED 和
BUDGET_CHANGED／Outbox 确认。增加启动 schema 兼容性检查，避免应用可启动但 Worker 持续失败。

**9. 授权、SSE、Worker、业务查询共享 4 个数据库线程，锁等待会扩大排队（P2，放大机制已确认，排队时间未直接测量）**

证据：实际 profile 配置 `arte.ai-new.persistence.threads=4`、
`queued-tasks=256`；[NewAiAdmissionConfiguration](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/config/NewAiAdmissionConfiguration.java:69)
建立同一 newAiPersistenceScheduler，数据库授权、执行存储和配置读取都使用它。日志同时出现 arte-ainew-db 线程等待同一账号锁。Druid
max-active=10，另有平台线程和后台任务使用相同数据源。

原因：阻塞 JDBC 持有线程；线程被账号锁等待占据时，排队的普通查询还未开始执行就已等待。现有日志没有 scheduler
排队和连接池借用耗时，不能给出排队占比或断言连接池耗尽。

改法：先解决热点锁和重复 SQL；补充 scheduler 队列深度／排队时间、连接获取时间和活跃连接数，再决定隔离交互查询与
Worker、限制前端批量读取并发，以及协调各池容量。单纯把 4 调大可能只增加同一锁上的竞争。

**实施顺序与验收**

1. 对齐结算表，确认日志不再持续出现 insertSettlement 1054，已成功调用的预算能完成结算。
2. 重构只读授权与写门控，消除事务内重复治理读取；随后将读接口的一次操作合并成一次轻量授权快照。
3. 合并前端相同资源请求、历史渐进展示和批量读取，统一 SSE 授权与通知刷新协调。
4. 用同账号单请求和多请求并发两组测量 HTTP TTFB／总耗时、SQL 数／执行总时间、锁等待、线程排队、连接获取。后续指标必须从 MVC
   请求开始覆盖 ExecutionContextFactory 的初次授权，并将 traceId 传到数据库调度线程；当前会话 Service 的 elapsedMs
   不含之前的工厂授权，因此不能直接等同浏览器接口耗时。

优先验证 SQL 与锁数量确实下降，并保留撤权、跨 scope 不可见、幂等、预算账本和会话版本冲突测试。接口改善目标可设为同网络环境展示读
p95 小于 1 秒、SSE 建连 p95 小于 1 秒，属于验收目标而非当前实测结果。
