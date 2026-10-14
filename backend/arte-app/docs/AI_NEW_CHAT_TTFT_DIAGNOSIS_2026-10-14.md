# 本次聊天首字延迟分析与优化方案（2026-10-14）

## 结论

用户提供的“李世民的一生”请求已经在运行日志中匹配。15.73 秒对应提交接口返回 HTTP 202；这条接口返回受理凭证，并不直接返回模型文字。后端收到模型第一段文字是在
HTTP 请求开始约 32.678 秒后，实时文字进入 SSE 队列约为 32.679 秒。

首字前的主要成本在应用提交、数据库鉴权与配置读取、派发准备。模型请求真正发出后，到应用收到第一段文字只有
389ms。应首先缩短内部关键路径，而不是先更换模型、增加输出批处理频率或扩大连接池。

本报告仅分析代码和已有日志，没有修改业务代码、重启服务或发起新的付费模型调用。没有本次浏览器点击与首次绘制的测量记录，因此上述
32.679 秒是“服务端 HTTP 入口至 SSE 文字入队”，不是已测得的浏览器上屏时间。

## 1. 请求定位与时间线

- conversationId：`05c88d24-c03c-431e-895d-e7642890609b`
- invocationId：`777d2e13-bffb-474f-aba7-2240d1a49acd`
- 提交 traceId：`64f4d1dc6fe244219f8af6480a4aed34`
- SSE traceId：`c6219cba1a8c4c60ab897e814e2ec387`
- 日期：2026-10-14；时间按本地日志原样列出。

| 阶段                         | 时刻         | 距前一阶段 | 距 HTTP 入口 |
|------------------------------|--------------|-----------:|-------------:|
| HTTP 请求开始                | 15:56:46.644 |          — |            0 |
| 返回 202                     | 15:57:02.358 |    15.714s |      15.714s |
| DISPATCH_START               | 15:57:04.177 |     1.819s |      17.533s |
| GATEWAY_SUBSCRIBED           | 15:57:13.129 |     8.952s |      26.485s |
| PROVIDER_REQUEST_SENT        | 15:57:18.933 |     5.804s |      32.289s |
| FIRST_TEXT_DELTA             | 15:57:19.322 |     0.389s |      32.678s |
| SSE_FIRST_LIVE_TEXT_ENQUEUED | 15:57:19.323 |     0.001s |      32.679s |

依据：[提交指标](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:10194)、[派发开始](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:10200)、[网关订阅](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:10208)、[请求发送](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:10211)、[首文字及实时 SSE](/Users/cylj126/Workspace/my_project/arte_dev/backend/log/info.log:10214)。

`InvocationTiming.elapsedMs` 的计时原点在不同组件中不同。上表使用日志绝对时间对齐；不能用 PROVIDER 的 elapsedMs 减去
DISPATCH 的 elapsedMs。

另外，SSE_READY 在 15:57:04.011，SSE 请求自己的建连准备耗时 1501ms，HTTP 首次写入为
1647.835ms。它与派发准备并行，不能再加到上表总耗时上。持久化首批文字在 15:57:20.364 提交，耐久文字在 15:57:22.531 入 SSE
队列，比实时预览晚 3.208 秒。全连接 SQL 指标覆盖约 32.86 秒的 SSE 生命周期，也不能当成首文字前的 SQL 成本。

## 2. 提交接口：重复完整鉴权和配置读取是最高优先级

本次 `AI_HTTP_METRICS`：

| 指标                        |              数值 | 解释                                                       |
|-----------------------------|------------------:|------------------------------------------------------------|
| mvcTotalMs                  |       15713.052ms | 与用户看到的 15.73s 对应                                   |
| serverFirstWriteMs          |       15712.825ms | 几乎直到受理结束才写响应                                   |
| sqlCount                    |               321 | 实际 JDBC 执行次数，包含直接 JDBC                          |
| sqlExecuteMs                |       12032.173ms | 累计驱动调用时间，约为 HTTP 总耗时的 76.6%                 |
| forUpdateExecuteMs          |        4393.176ms | 含 FOR UPDATE 的 SQL 执行累计，不是精确锁等待              |
| initialAuthorizationMs      |         868.532ms | 初次授权本身已经接近 1 秒                                  |
| connectionCount             |                37 | 连接获取次数，不代表打开了 37 条新物理连接或恰好 37 个事务 |
| connectionAcquireMs         |           0.118ms | 本次没有连接池获取瓶颈的证据                               |
| persistence 排队累计 / 最大 | 1.911ms / 0.590ms | 本次提交的线程排队不是主因                                 |

SQL 时间、授权时间与锁语句时间相互包含，不应相加。SQL 时间包含传输和数据库等待，不等于数据库 CPU 时间。`lockWaitMs=null`
、MySQL 连接 ID 列表为空，目前不能将 4.393 秒宣称为锁争用时间。

按本次提交 trace 提取 MyBatis Preparing 日志，平台查询中：

- 按用户 ID 读取账户 27 次，按名称读取账户 13 次。
- 工作空间普通读取 10 次。
- 账户、身份映射、租户、空间、成员、策略、AI 空间配置等多个当前锁定读取各 10 次。
- 主体预算定义、发布 head、发布 revision 各 6 次。
- 可见平台 SQL 共 199 次，很多点查约 35–39ms；新 AI mapper 完成日志另有 25 条。MyBatis 日志并未覆盖全部直接 JDBC，不能将这个子集当成
  321 次的完整分类。

代码中的放大链：

1. Controller 创建授权上下文后解析 binding。
2. ChatService 再 requireWrite、查 conversation、resolve binding。
3. TextContextService 又 require、resolve binding；HistoryLoader 再查 conversation。当前空历史也出现两次 getConversation。
4. AdmissionCoordinator 再分别 requireWrite (INVOKE)、requireWrite (CONVERSATION)、validate、resolve binding、读取配置。
5. 数据库配置来源的 configuration/grant 会走 currentSnapshot，重新进行完整身份解析和事务门控。

关键位置：[Controller](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/web/controller/NewAiChatController.java:78)、[ChatService](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/entry/DefaultChatService.java:68)、[FixedControlCatalog](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/control/FixedControlCatalog.java:94)、[数据库 currentSnapshot](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java:279)。

建议改法：

- 新增明确的“提交操作准备结果”，携带
  owner、已验证身份、所需权限、配置版本、binding、connection、rate、预算定义、conversation/version。Controller、Service、Context、Coordinator
  复用同一份准备结果完成纯校验与组装。
- 对 INVOKE、CONVERSATION、READ 一次检查整组权限，不按 scope 各跑一遍完整身份与空间查询。
- 对身份链合并账户与映射核验；保留账户状态、创建时间、映射退休和主体匹配规则。
- 不可变发布 revision 按版本缓存解析后的目录；当前发布 head、空间开关、权限与预算金额按边界核验，不能使用跨请求的过期允许决定。
- 最终 accept 在一个短事务内复核当前资格、conversation 版本及幂等键，并写入 snapshot、Turn、Invocation 和 dispatch
  outbox；将最终权限检查与实际提交边界协调起来。
- 复用准备阶段 conversation 数据，最终事务继续做版本检查；不能用 version=0 的前端参数直接推断历史为空而跳过可信查询。

已有 `ReadOperationSnapshot` 是展示读取快照，代码明确拒绝它授权写操作。应新建提交/派发的明确契约，而不是强行用现有只读快照绕过
requireWrite。治理事实已经在单个授权批次中复用；不能简单延长缓存到可能修改权限的整个事务回调。

## 3. Worker 到模型请求发送：还存在约 14.8 秒内部开销

派发准备各阶段的绝对时间差：

| 阶段                                       |   耗时 |
|--------------------------------------------|-------:|
| DISPATCH_START → DISPATCH_LOADED           |  350ms |
| DISPATCH_LOADED → PREFLIGHT_READY          | 3320ms |
| PREFLIGHT_READY → ATTEMPT_CREATED          | 2567ms |
| ATTEMPT_CREATED → BUDGET_RESERVED          | 1596ms |
| BUDGET_RESERVED → DISPATCH_MARKED          | 1110ms |
| DISPATCH_MARKED → GATEWAY_SUBSCRIBED       |    9ms |
| GATEWAY_SUBSCRIBED → PROVIDER_REQUEST_SENT | 5804ms |

当前 Gateway 会重新 validate、resolve binding、resolve connection；HttpConnectionRuntime.acquire 再 resolve
connection；WebClient 发出请求前的 filter 又 resolve connection，然后解析凭据。每次配置解析可能触发整套数据库权限/配置查询。

建议：

- 派发开始一次准备一致的 binding、connection、rate、snapshot 和当前授权结果；网关与连接运行时接受同一份准备结果，避免每层自己重新走数据库。
- 实际发出请求之前保留一次轻量的当前资格、连接启用状态、配置版本和凭据核验。发生变更应拒绝/重新准备，不能把首次提交的允许决定长期沿用到
  Worker。
- 将 createAttempt、预算预留、dispatch 标记中可原子合并的操作收敛到短事务/专用持久化命令，减少先读取 Invocation 和
  Attempt、再在事务内重新读取的重复往返。
- 保留预算原子扣占、lease/fencing、幂等、取消和未知执行状态语义。任何外部 HTTP 发送都必须在事务提交后执行，不能把数据库锁保持到整个模型流结束。
- 增加 CATALOG_READY、CONNECTION_READY、CREDENTIAL_READY、DNS、连接池等待、TCP/TLS、请求写出阶段指标。5804ms
  目前不能进一步精确拆成数据库、DNS、TLS的独立占比；不能全部归因于供应商推理。

位置：[GenerationDispatcher](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/execution/GenerationDispatcher.java:165)、[DefaultModelGateway](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/gateway/DefaultModelGateway.java:64)、[HttpConnectionRuntime](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/infrastructure/http/HttpConnectionRuntime.java:161)。

## 4. Outbox 调度：即时唤醒，并持续补充空闲槽位

本次 202 返回到 DISPATCH_START 为 1.819 秒，包含轮询、claim、读取等，不是精确的轮询等待时间。当前 worker 的 pollInterval 为 1
秒。

更大的并发问题是 `pollOnce()` 等本批次所有 process 完成才返回，外层随后等待 pollInterval
再领取下一批。一个槽位提前空闲，也要等该批最慢任务结束才能补充新任务；此问题由代码确认，本次单调用不能证明已经发生它。

建议：

- accept 事务提交后发出有界唤醒提示；Worker 始终通过耐久 outbox claim 获取执行权。提示可跨实例传播；丢失提示时由低频轮询恢复。
- 固定数量执行槽位，有空闲槽位立即补领任务；不让一整批任务的最慢调用控制下一次取件。
- 领取量不超过立即可用的槽位；不要提前领取大量任务后让租约在本地队列中消耗。
- 保留至少一次消费、租约、fencing 和模型发送幂等约束，不通过 Controller 任意 subscribe 模型来绕过 durable outbox。
- 100ms 轮询可以作为临时措施，但需测量数据库轮询负载；仅改这个配置不会消除另外二十多秒。

位置：[InvocationDispatchWorker](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/application/execution/InvocationDispatchWorker.java:55)。

MySQL 的 SKIP LOCKED 适合队列领取时避开已锁行；是否采用必须结合当前领取事务、MySQL
版本和顺序语义检查，不用于替代普通业务一致性校验。参见 [MySQL locking reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)。

## 5. 数据库固定往返成本和治理锁粒度

已有同配置的只读基线：20 次 `SELECT 1`，中位 34.040ms，P95
34.688ms。来源：[jdbc-roundtrip.json](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app/docs/measurements/2026-10-14/jdbc-roundtrip.json)
。这不是本次请求的新测量，也不能单凭它判断网络是唯一原因。

若仍有 30 次串行往返，按此基线仅 SQL 就约 1.02 秒，尚未加上模型与浏览器。因此一秒目标需要同时减少往返，并检查应用/数据库部署距离、代理路径和驱动成本；不能只优化索引。

- 比较同区/同网络部署下的数据库 RTT，尽量将热路径往返压到低毫秒级。
- 合并同 scope 的身份、空间和权限事实查询，按需读取目录；新慢查询再使用执行计划验证索引问题。
- 当前租户父行使用排他治理锁，是潜在租户级热点。先合并重复门控并缩短持锁事务；再评估专用资格版本行或共享锁/排他撤权的治理协议。必须保留撤权与派发之间的一致性、统一锁顺序，不可直接删除父锁。
- 不优先增大 Druid 连接池，本次 connectionAcquireMs 很小。历史只读并发报告有调度排队证据，应在 SQL
  工作减少后再评估线程数、连接池上限和读写容量隔离。

## 6. SSE、前端和生产代理

本仓库已经有实时 text-delta 预览、首个文字立即提交、后续输出合批、有界接收缓冲、状态查询与 SSE 建连并行，以及解码与后台 HTTP
刷新分离。此次首文字到实时 SSE 入队仅约 1ms，不能将“增加实时预览”当作尚未完成的主改法。

仍应做的工作：

- 接受 202 后立即建立 watch；保留现有并行行为。历史和预算刷新不进入正文展示关键路径。
- 点击发送后立即展示用户消息与助手占位，让用户看到操作已响应；占位和 connected/heartbeat 都不算模型首文字。
- 首段非空正文即时提交 React 状态并测量绘制；后续片段可按动画帧合并，控制 Markdown 大正文反复渲染成本。
- watch 建连本身有约 1.5 秒准备开销，缩短 READ 身份链和 owner/cursor 验证；如果生成链变快，它可能成为新的关键瓶颈。
- 可先保留两请求架构完成优化，再按端到端数据决定是否新增 submit-and-watch 流式接口。新接口仍需幂等和断线恢复，不能只为抢先写
  HTTP 200 而隐去受理失败。
- SSE 已发 no-cache/no-transform 和 X-Accel-Buffering: no。生产代理/CDN仍需实测首帧透传；Nginx 若忽略此响应头，需在实际 SSE
  路由配置 proxy_buffering
  off。参见 [Nginx proxy buffering](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_buffering)。
- HTTP 客户端已经有连接池，应实测复用率、冷连接和池淘汰。DNS安全检查与池按
  owner/版本隔离已有约束，不能为复用率随意删除。可使用 [Reactor Netty HTTP client metrics](https://docs.spring.io/projectreactor/reactor-netty/docs/current/reference/html/)
  记录池等待、连接及 TLS 阶段。

位置：[前端 send](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useChatSession.ts:828)、[前端 watch](/Users/cylj126/Workspace/my_project/arte_dev/frontend/src/features/ainew/useChatSession.ts:560)、[SSE Controller](/Users/cylj126/Workspace/my_project/arte_dev/backend/arte-ai/src/main/java/com/arte/ainew/web/controller/NewAiInvocationController.java:164)。

本次实际输入为 6 个供应商计费 Token，输出 829 Token；请求中的 maxInputTokens=32768 是容量上限，不代表向模型发送了 32768
Token。因此单纯降低它不是这次首字延迟的主要解法。

## 7. 实施顺序与验收

建议先补客户端首字指标和 Worker/发送前阶段指标，然后按以下顺序改动，每批单独复测：

1. 提交与派发准备快照：消除完整权限/配置链重复读取，合并身份读取；先将 321 次 JDBC 执行降至数十次，并记录具体剩余工作。
2. 合并短事务与发送前轻量复核：减少 createAttempt/reserve/markDispatch 等读写往返，同时保留版本、预算、lease/fencing。
3. Worker 即时唤醒和持续补充槽位：消除固定轮询延迟及整批等待。
4. 应用/数据库低延迟部署及 HTTP 热连接：验证内部总成本是否满足首字预算。
5. SSE 建连与前端绘制：避免生成链变快后被观察链或渲染阻挡。

可采用以下设计预算，总计 1000ms；这是目标分配，不是已经达成的测量结果，也不保证每个分段 P95 相加就等于端到端 P95：

| 阶段                     | 目标预算 |
|--------------------------|---------:|
| 点击到后端入口           |     50ms |
| 受理及必要事务           |    150ms |
| 派发领取及准备           |    150ms |
| 发送前最终核验/连接      |    100ms |
| 供应商请求发出到首段正文 |    450ms |
| SSE传输及首次绘制        |    100ms |

SSE建连与派发并行，要求建连在正文产生前完成；不能将它作为额外的一秒开销。

验收使用 `click → first non-empty assistant text painted`，通过 invocationId/measurementId 关联
202、Worker、供应商、SSE和浏览器。分别记录 accepted、首字、完成时间；不得用 HTTP 首字节、role-only 帧、connected 或 heartbeat
冒充正文首字。

对明确的模型、上下文范围、正常负载和网络条件定义端到端 P50/P95/P99，建议首轮目标为正常负载 P95≤1s。供应商排队/冷启动/长上下文也需单独测试；当前单次
389ms 不能作为所有模型所有请求的保证。若要求每次正文绝对≤1s，需要供应商服务等级和容量共同约束。

性能测试覆盖空历史、10/50轮历史、冷/热连接，以及不同账号/同账号/同租户的 1、4、8、16
并发；单会话现有契约禁止并行生成，并发压力应使用不同会话。验证排队时尽快呈现真实排队状态和执行可见性。

正确性回归至少覆盖：幂等重放不重复调用供应商、撤权/停用后提交与发送拒绝、会话版本冲突、预算不超占、Worker崩溃及租约重领、取消、SSE断线重放与预览/耐久偏移去重，以及写回调内权限变更后的再次检查。减小
SQL 数不能以破坏这些语义为代价。
