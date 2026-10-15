# 新版 AI 页面加载性能排查与 Elasticsearch 适用范围

日期：2026-10-15。范围：当前工作区 `/AI/ChatNew` 的空间初始化、会话目录／详情、消息历史、配置与相关操作。

本次为源码审查与仓库内已有原始测量核对，没有重新登录、采集当前页面瀑布或执行实时数据库压测，没有修改业务代码。以下代码路径可以确认，但各项在用户当前环境中的耗时占比仍须复测；不能把既有局部测量视为本次整页实测。

## 判断

Elasticsearch 适合新增的名称／正文全文检索，可以在数据量较大时分担搜索压力；目前没有证据表明它是解决空间、普通会话列表、最新消息窗口或写操作慢的优先方案。

代码中更直接的成本来自前端串行依赖、页面进入时执行 AI 准备事务、每个接口的当前授权／控制配置读取，以及历史的先首页再末页、COUNT／OFFSET
和会话行锁。将消息复制到 ES 不会消除这些成本。

## 现有测量能证明什么

来源：[HTTP](measurements/2026-10-15-directory/http.json)、[阶段指标](measurements/2026-10-15-directory/http-phases.json)、[目录 SQL](measurements/2026-10-15-directory/mysql-existing-data.json)、[测量范围](measurements/2026-10-15-directory/README.md)。

这批测量是同日此前保存的记录，不是本次复测。HTTP 样本为 1 个置顶＋3 个普通会话，30 次串行和 30 次最多 8 并发，包含前端代理，未调用模型。

| 项目             |   串行 P95 | 8 并发 P95 |      请求 SQL 数 |
|------------------|-----------:|-----------:|-----------------:|
| 目录首页窗口     | 1562.185ms | 3323.600ms |               16 |
| 目录续页         | 1226.175ms | 3138.463ms |               13 |
| 首页初始授权阶段 |  840.559ms | 1932.139ms | 包含在完整请求中 |

当时首页的目录自身为 4 条 SQL、续页为 1 条 SQL，其余请求 SQL 包含身份、授权和基础读取等。目录单条 SQL 的既有 P95 约 33ms，命中
`idx_ai_conversation_list`；不是整套目录读取的总时间。应用阶段计时可能重叠，不应相加；不同指标的 P95 也不能直接相减来计算收益。

这说明小列表也出现秒级响应，优先排查往返、授权和调度比直接替换目录存储更有依据。并发下连接获取也有变化，不能仅凭总时间认定全部是数据库执行或全部是连接池问题。

## 具体代码发现

### 1. 当前空间就绪被完整空间目录查询阻挡

[useWorkspace.ts](../../../frontend/src/features/workspace/useWorkspace.ts:258) 当前顺序为：

1. `getWorkspacePreference`。
2. `browseAccessibleWorkspaces`。
3. 解析本地／偏好中的目标 scope，`getWorkspace`，失败时再验证默认空间。
4. `accept(view)` 发布当前空间，随后异步读取创建／改名权限。

目标空间解析不依赖目录响应，但当前目录查询被放在前面；目录缓慢或失败会拖延当前空间初始化。

建议：偏好读取成功后，当前空间验证与目录加载独立推进；选中空间通过权威验证即可发布，不让完整下拉目录成为聊天前置条件。目录失败独立展示错误。保留首次默认空间初始化、失效空间回退、账号切换和迟到响应保护。

同文件 `checkPermissions` 对创建空间、改名空间分别发两次请求（约 190～205 行），可以评估使用已经存在的
`batchCheckWorkspaceAccess`，减少 HTTP 和重复身份／事实读取。它们目前异步执行，不应误称为当前空间发布的串行阻塞。

### 2. 空间 AI 准备是聊天挂载前的另一个串行阶段

[WorkspaceAiPreparation.tsx](../../../frontend/src/features/ainew/WorkspaceAiPreparation.tsx:34) 先调用
`ensureWorkspaceAi`，状态为 ready 才挂载聊天子树；它并非每次都创建数据，但每次进入这个组件都要执行准备请求。

[DatabaseAiControlService.ensure](../src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java:499)
包括当前事务权限检查、发布配置读取、空间配置检查、预算准备检查和最终 view。`view`（约 526 行）调用 `workspaces.lock`
，也会检查管理权限。这是初始化／治理事务路径，不是单纯读取一个准备完成标志。

建议：区分已准备空间的轻量只读检查与真正需要初始化的事务路径，准备响应尽量复用已取得的可信事实。不能直接跳过首次预算／配置准备或用浏览器标志替代权限。

### 3. 跨接口仍有多次完整授权，ES 无法替代

[DatabaseAiControlService.readSnapshot](../src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java:174)
会解析当前身份、批量核验空间动作、读取空间 AI 配置；发现模型时还需要预算定义和发布目录。

[WorkspaceAccessFactsStoreServiceImpl.read](../src/main/java/com/arte/app/service/platform/WorkspaceAccessFactsStoreServiceImpl.java:56)
虽然已按集合批量读取，但租户、租户成员、租户政策、空间、空间成员、空间政策仍分为多次 SQL 调用。跨数据库网络往返时，这些小查询也可能累积出明显成本。

已有优化应保留：[ExecutionContextFactory.readCurrent](../../arte-ai/src/main/java/com/arte/ainew/context/ExecutionContextFactory.java:92)
与 `ReadOperationSnapshot` 已让同一次只读操作复用可信快照；不能再描述为一个读接口内部所有层都重新授权。

建议：测量后合并单个 scope 的事实读取；为首屏设计有界聚合读取，复用同一请求中的可信事实。聚合仍须分别检查会话读取、执行读取和配置发现所需动作，不能因合并响应扩大权限。写入和派发仍复核当前授权。

### 4. 长会话先取历史首页，再取末页

[useChatSession.readHistory](../../../frontend/src/features/ainew/useChatSession.ts:718) 首次复用会话详情，然后：

- `queryTurnsOfConversation(page=1, size=10)` 取得 total。
- 超过一页时计算末页，再查询末页。
- 根据本页选中／最新调用 ID，补取状态和结果。

因此长会话初次打开至少多一次历史 HTTP；后续刷新还可能先重新读取会话版本。

建议：提供直接读取最新 N 轮的接口，以 `(sequence_no, turnId)`
或已有唯一轮次序号作为边界，返回继续读取较早消息的游标。在服务端以一致读取返回所需版本、最新轮次和必要回复，避免客户端为定位末页先请求首页。是否合并详情与回复需保持响应体和读取量有界。

### 5. 历史分页包含 COUNT、OFFSET 和会话 FOR UPDATE

[MybatisExecutionPersistence.listTurns](../../arte-ai/src/main/java/com/arte/ainew/persistence/mybatis/MybatisExecutionPersistence.java:378)
读取会话 `FOR UPDATE`，校验版本，再 COUNT 和页码读取。

[AdmissionMapper.xml](../../arte-ai/src/main/resources/ainew/persistence/AdmissionMapper.xml:70) 使用
`ORDER BY sequence_no ASC LIMIT ... OFFSET ...`。长历史查询末页会跳过前面的记录；当前会话锁也可能与同会话受理／更新竞争。

这能确认存在规模成本和锁竞争可能，不能证明用户这一次卡顿已经发生了锁等待。

建议：优先新增最新窗口／向前游标，评估在短只读事务的一致快照中校验版本并读取历史。必须先用并发用例证明读取与受理的一致性，再决定是否缩小锁范围；不能直接删除现有锁和版本检查。

### 6. 普通目录已是轻量索引查询，不读取全部消息

[ConversationMetadataPersistence.window](../../arte-ai/src/main/java/com/arte/ainew/persistence/mybatis/ConversationMetadataPersistence.java:243)
通过 owner、pinned 和活动时间边界读取投影，两区首屏均读取 total。续页使用 size+1 判断 hasMore。

建议：保留已有游标和 100 条窗口；评估首页是否必须取得普通会话精确总数，减少仅为展示而执行的 COUNT。改计数契约需同步 UI
和测试，不改变服务端置顶名额校验。

### 7. 回复批量读取和前端在途合并已经存在

[invocation.ts](../../../frontend/src/services/arte-ai/invocation.ts:74) 将同一轮单条读取组成最多 20
条的批次；[DefaultExecutionEventService.snapshots](../../arte-ai/src/main/java/com/arte/ainew/application/execution/DefaultExecutionEventService.java:129)
批量读取执行、预算状态及结果，结果持久化适配器也实现批量查找。

[request.ts](../../../frontend/src/services/arte-ai/request.ts:145) 只合并相同凭据和完整参数下正在进行的 AI
读取。不同接口和不同分页请求仍独立，不能指望它消除页面的所有授权往返。

切换会话时 `BusinessChatPanel` 按会话 ID 重新挂载，Hook
内终态结果缓存也重建。可评估账号／scope／会话／版本隔离的有界展示缓存，但展示前仍应验证当前访问资格，不能用缓存授权绕过撤权或复用旧执行状态解锁发送。

## Elasticsearch 能改善的范围

| 场景                                     | 判断                                                                                       |
|------------------------------------------|--------------------------------------------------------------------------------------------|
| 名称与消息正文全文搜索、相关性与命中片段 | 适合；建立异步搜索索引                                                                     |
| 当前空间恢复／默认空间初始化             | 优先优化请求依赖与初始化事务；ES 不能替代                                                  |
| 可访问空间列表                           | 目前已有预算式游标与批量授权；若未来规模证明候选检索昂贵，可用 ES 提供候选，但仍需权威授权 |
| 普通会话列表／置顶读取                   | 先优化授权、计数和读取往返，暂不迁 ES                                                      |
| 最近消息时间线与较早历史                 | MySQL 最新窗口、游标及批量读取更直接                                                       |
| 新建、改名、置顶、预算和发送             | 仍依赖事务、锁、版本与幂等；ES 搜索副本不能加快这些提交                                    |

ES 写入后的搜索可见性是近实时，异步同步会再增加延迟；为每个业务写入等待索引刷新可能延长响应，频繁强制刷新也增加资源消耗。索引任务不应阻塞聊天受理与正文显示。

官方资料：[Near real-time search](https://www.elastic.co/docs/manage-data/data-store/near-real-time-search)、[refresh 参数与成本](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/refresh-parameter)。

## 建议实施与验收顺序

1. 先补当前浏览器整页瀑布／性能记录，将页面首次请求、空间可用、目录首屏、用户问题显示、回答显示、允许发送分别计时；后端按
   traceId 记录授权、SQL、连接、调度、事务／锁、序列化及响应体。区分冷／热页面、长／短会话和 DATABASE／FIXED 配置。
2. 解耦当前空间与完整目录，合并管理权限读取，优化已经准备空间的检查路径；保留恢复、初始化和权限边界。
3. 新增最新历史窗口／较早消息游标，减少首页到末页的往返和 OFFSET；用并发受理／刷新／停止／重新生成用例验证版本及读取一致性。
4. 按实测收敛单 scope 授权 SQL 和首屏聚合，评估省去非必要 COUNT。SQL 合并要同时检查计划和数据放大，不能仅以条数减少判成功。
5. 将全文搜索作为独立功能建设 ES 索引，测索引滞后、回填、权限过滤与搜索耗时；另测索引任务是否影响现有聊天／目录性能。

每步均在同环境、同数据量、相同权限与并发下比较 P50／P95、HTTP／SQL 次数、字节数、排队与锁等待；不预估未经测量的提速倍数，不用既有单
SQL 基线替代整页体验。
