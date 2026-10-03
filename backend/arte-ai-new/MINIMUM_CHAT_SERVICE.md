# 最小会话与聊天服务

已打通“创建会话 → 提交用户文本 → 组装历史 → 模型受理 → 查询结果与历史 → 继续交流”，以及重新生成、取消、命名和软删除。
沿用 [聊天数据模型](MINIMUM_CHAT_MODEL.md) 的三张表和 [最小模型调用](MINIMUM_MODEL_CALL.md) 的执行、预算、授权与外发链路。
新 AI 生产代码仍只依赖 base 和 JDK；JDBC、JSON、Spring 及现有账号接入位于 app，不访问旧 AI 表或使用旧实体。

## 服务与持久化

- `ConversationService`：按租户、工作空间及主体管理会话；创建时固定服务端模型绑定，列表支持标题检索和分页，命名／删除比较预期版本。
- `ChatService`：耐久记录提交、串行推进、固定上下文、使用统一协调入口，查询时组合提交及权威执行结果。提供明确的重新生成和取消操作。
- `ContextService`：只使用用户文本和已授权的成功历史；同一问题采用最后成功的生成结果，保留实际历史版本及执行引用，按文本
  UTF-8 字节裁剪完整问答对。
- `ChatStore` / `JdbcChatStore`：会话锁原子分配顺序、比较版本并占位；提交锁串行处理准备和模型受理。快照写入与 READY
  更新为一个事务，生成结果继续存于模型账本。
- `ChatAccessPolicy` / `ExistingChatAccessPolicy`：复用现有账号、成员、任务、应用及绑定许可。模型结果读取、派发、事件和取消仍走已有模型权限检查。
- `ModelConsentProvider`：app 在用户明确确认后，为服务端准备的实际协议正文和目标生成同意；独立提交后才受理模型，异步线程不接收
  HTTP 对象。

元数据读取不要求当次外发同意；新派发要求 AI 与外发许可以及明确确认。主体从现有认证会话交叉核对，客户端不能提供主体、执行上下文、目的地、凭据、绑定版本或报价。
所有聊天数据查询都按完整作用域过滤；权限撤销后不能依靠旧执行上下文读取结果。

## 版本、重复提交与恢复

创建的会话版本为 1。每次新提交推进一次会话版本，准备上下文固定提交前的版本；命名和删除也推进版本。
后续新命令应读取最新会话版本。相同提交重放保留原来的 `expectedVersion`、文本、参数、重新生成目标及 `Idempotency-Key`
，不重新读取版本并改写请求。 相同幂等键而请求摘要不同返回冲突。会话有未释放提交时，新提交、命名和删除拒绝竞争；已完成执行的占位在查询或新命令前核对并释放。

模型受理键为服务端派生的 `chat:<turnId>`。独立模型 HTTP 入口拒绝该保留前缀。 READY 已提交后才进入模型受理；模型受理和聊天关联分别提交。关联写入失败时保持
READY，查询或同键重放按稳定键核对权威执行并补齐关联，不再次调用模型。 即使快照已过期，也先恢复已存在的受理；过期且未受理的提交拒绝新派发。
准备事务失败留下 PREPARING，不留下孤立快照；同键重放可重新准备。无法确认是否受理时保持待核对状态，不错误标记 REJECTED。

提交驱动的数据库锁仅覆盖准备与异步模型受理，不等待供应商网络结果。不同实例处理同一提交依靠同一数据库锁协调；连接池须容纳聊天、同意及模型的独立事务。
本阶段的恢复由查询和同键重放触发。模型进程失联后的执行恢复仍沿用最小模型链的运维核对机制，不自动重新派发或恢复供应商流。

## 历史与重新生成

历史接口按提交顺序倒序分页，包含普通提交、重新生成、拒绝及未完成提交；返回 `ChatTurnResult(turn, execution)`
，用户输入和模型结果分属各自记录。 构造新上下文时仅使用 SUCCEEDED 的文本结果，跳过失败和结果未知的执行；同一问题的多次生成不会被当成多轮新问答。
最多检查最近 256 条提交并选取配置数量的成功问答对；容量不足时从最旧的完整问答对开始移除，不裁剪当前问题或把部分回答冒充完整回答。
当前文本本身超出容量时记录已确认拒绝；空文本、过大文本及非法参数在输入边界拒绝。

重新生成只允许当前最后一个用户问题，避免隐式创建对话分支；保留原提交及结果，固定原问题的实际上下文，再创建独立提交和执行。
原执行须已结束且结果确定；OUTCOME_UNKNOWN 不允许直接重新生成。使用新的幂等键，并再次明确确认可能产生的新费用与外发。
取消只请求当前模型执行停止，不提前释放提交占位。已派发调用停止后仍可能成为 OUTCOME_UNKNOWN 并保留待对账费用，不宣称远端已取消。

## 启用和数据库

聊天默认关闭，需要已有身份、安全桥接和执行支撑，以及 `arte.ai-new.model.enabled=true`。 按现有部署方式准备安全桥接、执行支撑、新模型及新聊天
DDL；没有自动执行数据库脚本或迁移旧聊天数据。

```yaml
arte:
  ai-new:
    model:
      enabled: true
      # 其余固定模型、连接、凭据引用、预算及许可配置见 MINIMUM_MODEL_CALL.md
    chat:
      enabled: true
      context-max-bytes: 8192
      history-pairs: 32
      snapshot-ttl: PT10M
```

`context-max-bytes` 统计文本 UTF-8 字节，与供应商完整 JSON 正文上限分开；完整正文仍由网关校验。
`history-pairs` 为 0 到 32；快照期限使用 ISO-8601 Duration，必须大于 0 且不超过 1 小时。 输出 token 默认沿用配置的模型上限，客户端可以请求更小值。
当前仍使用单个已配置、固定版本的模型绑定；配置目录、旧版本解析与升级迁移不在此阶段实现，不自动把已有会话切换到新版本。

## HTTP 入口

新聊天入口为 `/AI/Chat`，接入与菜单脚本见 [第一步](../../frontend/docs/ai-new-chat-step1.md)
，消息发送、外发确认及结果观察见 [第二步最小聊天闭环](../../frontend/docs/ai-new-chat-step2.md)。
`GET /api/ai-new/chat/bootstrap` 返回当前已授权配置空间、默认模型及外发展示信息；关闭时可查询开关状态。
页面请求停止、重新生成及异常恢复见 [第三步执行控制与异常处理](../../frontend/docs/ai-new-chat-step3.md)。
阅读、复制、草稿和第一版验收清单见 [第四步使用体验与验收](../../frontend/docs/ai-new-chat-step4.md)。
该初始化接口不登记任务、同意或预算，不自动放行外发，也不返回凭据；模型生成仅由用户明确确认后提交聊天请求触发。

基础路径：`/api/ai-new/conversations`，使用现有登录会话。

| 操作     | 方法与相对路径                     | 输入                                                                                                                          |
|----------|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| 创建     | POST `/`                           | JSON：tenantId、workspaceId、title；返回 201                                                                                  |
| 列表     | GET `/`                            | tenantId、workspaceId、可选 title、offset、limit                                                                              |
| 查询     | GET `/{id}`                        | tenantId、workspaceId                                                                                                         |
| 命名     | PATCH `/{id}`                      | JSON：tenantId、workspaceId、expectedVersion、title                                                                           |
| 软删除   | DELETE `/{id}`                     | tenantId、workspaceId、expectedVersion                                                                                        |
| 提交     | POST `/{id}/turns`                 | JSON：tenantId、workspaceId、expectedVersion、text、可选 options、externalTransferConfirmed；请求头 Idempotency-Key           |
| 重新生成 | POST `/{id}/regenerate`            | JSON：tenantId、workspaceId、expectedVersion、originalTurnId、可选 options、externalTransferConfirmed；请求头 Idempotency-Key |
| 历史     | GET `/{id}/turns`                  | tenantId、workspaceId、可选 beforeSequence、limit                                                                             |
| 单次提交 | GET `/{id}/turns/{turnId}`         | tenantId、workspaceId                                                                                                         |
| 取消     | POST `/{id}/turns/{turnId}/cancel` | tenantId、workspaceId                                                                                                         |

列表默认 50 条、最多 100 条，offset 上限 10000。历史默认 50 条、最多 100 条，`beforeSequence` 是不包含该序号的向前分页边界。
提交及重新生成返回 202 和组合查询结果；202 表示可靠受理，实际生成状态查看 `execution.status`，不表示生成成功。
拒绝、版本和幂等冲突等错误使用与独立模型入口相同的稳定错误信封。存储或受理结果不明确时可能返回
503，应先查询历史核对，再使用相同请求重放，不能换键盲目重试。

提交示例（会话刚创建、版本为 1）：

```json
{
  "tenantId": "personal-1",
  "workspaceId": "workspace-1",
  "expectedVersion": 1,
  "text": "请解释这段代码的职责",
  "options": {"maxOutputTokens": 512},
  "externalTransferConfirmed": true
}
```

已有模型执行及耐久事件入口继续可用。新聊天默认启用流式输出；文章资料、助手、记忆、工具及旧前端切换仍不在本阶段范围。

## 验证

`NewChatIntegrationTest` 在 H2 中使用实际 DDL、现有身份和权限记录、独立模型预算／执行／事件存储，供应商网络以文本测试适配器替代。
覆盖多轮历史、幂等及跨实例竞争、准备事务回滚、受理关联恢复、快照过期及篡改、重新生成、取消、主体隔离、许可撤销、容量裁剪、HTTP 状态和
Spring 事务代理接线。
`NewChatConfigurationTest` 验证默认关闭及缺失基础设施时启动失败；已有数据契约、安全接入和模型调用测试继续作为回归验证。 H2
验证不等同于生产 MySQL 部署验证，没有执行实际数据库变更。

## 第一批性能与事务优化（2026-10-03）

历史分页一次读取 Turn，再按执行 ID 批量读取 Execution；模型授权按本次响应中不同的能力／绑定版本去重，不跨请求缓存权限。
已完成并释放占位的 Turn 不再加行锁；仅 READY 关联恢复和终态占位释放需要条件写入。 前端初次加载及主动刷新仍查询历史，生成期间每两秒只查询当前未完成的
Turn，并更新原分页缓存；完成、权限错误或观察窗口结束后停止轮询。

上下文装配、历史引用校验和外发同意在 Turn 行锁事务之外执行；提交快照时重新核对 Turn 状态和行版本。 最终模型受理及 Turn
关联仍用行锁串行保护，模型预算／执行账本仍独立提交，防止并发同键请求误拒绝或重复派发。 新聊天和模型读取授权采用
SUPPORTS，加入聊天存储的 READ_COMMITTED 事务；异步外发前仍重新检查当前授权。 同意和执行账本的 REQUIRES_NEW 保留，不要求事务跨线程传播。

观测通过公共 Telemetry 端口接入 Micrometer。复用已有 MeterRegistry；没有指标后端时提供 SimpleMeterRegistry，仅在内存中保存统计。
本次没有新增 Actuator、Prometheus 依赖或开放指标网络接口；要长期保存、查看时间序列及 P95/P99，需要接入指标导出后端。
默认日志记录失败阶段及超过一秒的阶段耗时，正常短阶段使用 DEBUG，包含 operation、outcome、elapsedMs、traceId，不记录消息、凭据和请求正文。

| 指标                                                              | 含义                                                                                           |
|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------|
| `arte.execution.duration` / `arte.execution.operations`           | 按阶段及结果统计耗时和次数：提交、认领、上下文准备／校验、受理、历史、单轮查询、准入、模型执行 |
| `arte.chat.turn.lock.acquire`                                     | 从申请事务到取得 Turn 行锁的耗时，包含连接获取和 SQL 执行，不代表纯数据库锁等待                |
| `arte.chat.turn.transaction`                                      | Turn 写事务总耗时，包含等待及失败路径                                                          |
| `arte.ai.execution.queue.wait`                                    | 从数据库受理排队到 Worker 开始执行的等待，恢复时保留原排队时间                                 |
| `arte.ai.provider.duration`                                       | 模型网络交互阶段耗时，包含成功与失败                                                           |
| `arte.ai.execution.finished`                                      | 模型执行终态次数，包括 OUTCOME_UNKNOWN                                                         |
| `arte.ai.tokens`                                                  | 供应商报告的输入／输出 Token；未知用量不记为零                                                 |
| `arte.execution.workers.active` / `arte.execution.workers.queued` | 本机线程及队列占用                                                                             |
| `arte.database.connections.active` / `idle` / `waiting`           | Druid 连接池使用及等待线程数，以固定的数据源 Bean 名区分                                       |
| `arte.database.connection.wait`                                   | Druid 连接池累计等待次数与等待时间                                                             |

指标只使用低基数阶段、结果及固定数据源名称标签；traceId 不进入指标标签。 数据库纯行锁等待和慢 SQL 仍需结合数据库诊断及现有
Druid SQL 统计判断。

新增回归验证覆盖 20 条历史仅一次执行查询、一次模型授权、无 Turn 行锁；上下文及同意在行锁事务外；当前权限撤销；指标采集失效不影响业务；
前端单轮轮询保持已加载分页、终态停止及权限错误后的手动恢复。 第二批任务租约／重启恢复见下文；第三批流式／Token
上下文预算见下文；部署仍采用单活 Worker。

本批验证：后端相关回归 88 项、前端相关回归 16 项通过。全项目 TypeScript 检查仍有 `canvas-ai-dialog.tsx` 第 204、212 行的既有
attachments 类型错误，本批聊天代码无报错。 未运行生产 MySQL 压测或真实供应商调用。

## 第二批执行恢复与生命周期（2026-10-03）

生产模型链改为数据库耐久队列：执行、事件、预算预占及工作正文同事务提交，受理接口不再等待本机准入。 Worker
在专用线程上取得并发许可后认领；数据库单活租约防止两个进程同时执行，任务 token 围栏保护 RUNNING、派发及终态写入。 未完成工作上限为
threads + queue-capacity，启动速率窗口保存在数据库，重启不会重置。默认维持 4 个并发、32 个排队位置、每分钟最多 60 次启动。

期限内且未派发的失联任务可以恢复；恢复保留 executionId／attemptId，重验输入指纹、当前配置、授权及外发同意。 已派发的失联任务转
OUTCOME_UNKNOWN，不自动重发，费用继续待核对；旧版本没有正文的未派发遗留任务转 INTERRUPTED 并释放预算预占。
取消写入数据库，运行中取消及到期关闭已注册 socket，等待实际工作退出后再结算和释放许可。停机先停止受理和认领，保持续约并限时收尾，未认领工作留待重启恢复。
新配置 lease-duration、poll-interval、shutdown-grace 使用原有 Maven 占位符机制，默认 PT30S、PT0.5S、PT10S。

升级必须先停止旧后端，执行 [工作队列 DDL](../arte-app/scripts/arte-ai-new-work-ddl-mysql.sql)，再重新构建并启动。
本次没有执行实际数据库迁移；数据库单活约束仍不提供多实例负载均衡。详情见 [模型调用说明](MINIMUM_MODEL_CALL.md)。

DurableModelWorkerIntegrationTest 覆盖原子回滚、排队容量／幂等、重启后的多轮历史、未派发恢复／旧租约拒绝、已派发未知结果、
持久化取消、速率窗口、过期退款、正文篡改、坏任务隔离、权限撤销、旧版本任务清理及停机后队列保留。 BoundedTaskExecutorTest
补充取消／到期关闭 I/O 但不提前完成，以及优雅停机。

本批后端相关回归共 95 项通过（其中新增耐久 Worker 集成测试 14 项），包含本机 HTTP 阻塞读取取消验证。 使用 H2 和本机协议替身，没有执行生产
MySQL 迁移、真实模型调用或压力测试。

## 第三批流式输出与 Token 上下文预算（2026-10-03）

新聊天默认发送 stream=true 和 stream_options.include_usage=true，CompatibleChatProviderAdapter 按
[DeepSeek 对话补全文档](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) 解码 SSE，兼容最后终止块携带用量以及单独的空
choices 用量块。 PinnedHttpConnectionRuntime 继续固定 IP、校验 TLS、限制 HTTP 帧及响应总大小，并在取消／到期时关闭
socket；不引入隐式重试。 UTF-8 字符、SSE 行和 JSON 事件可以跨任意网络片段，解析器在完整帧到达后发布 assistant content。
工具输出、异常 finish_reason、非法编码、超大帧或缺少终止标记会终止调用；不把断流前的文字当作成功结果。

模型线程合并增量，首批立即提交，之后在收到新内容时按约 100ms 或约 512 个字符的批次提交，结束及失败时尝试提交剩余部分。
增量事件、部分正文缓存及游标同事务提交，并沿用 Worker 租约围栏；每次输出总量最多 1 MiB，单批最多 16 KiB。
落库成功后只发本机唤醒通知，不把模型线程接到浏览器 socket；通知失败仍能从数据库补读。 只有完整结果、成功终态和预算结算原子提交后，回答才成为
SUCCEEDED 和后续成功历史。 部分输出在刷新、取消、断流及 OUTCOME_UNKNOWN 后仍可查询，保留费用待核对。成功历史响应不重复携带完整
partialText。

GET /api/ai-new/conversations/{id}/turns/{turnId}/events 返回 text/event-stream，支持 exclusive after 与 Last-Event-ID。
每帧包含 executionId、sequence、status、textDelta、result、error；前端核对执行身份和单调游标，重复帧不重复拼接。
连接只订阅耐久事件，断开不自动取消模型；页面刷新后使用已保存部分正文的游标补读，不重新发送问题。
每次事件读取重新检查当前权限，权限撤销停止输出。连接默认最多 2 分钟，到期需重新认证订阅；服务端默认最多 64 个订阅、4 个发送线程，
每个订阅最多一个在途发送任务，慢连接不增加无界缓冲。提交通知触发立即补读，另有每两秒的数据库恢复扫描和十秒心跳。
页面连接正常时每十秒查询当前轮次校准状态，流不可用时恢复两秒查询；最多四次有退避的 SSE 连接尝试。
这些重连只补读已提交事件，不重试模型请求。轮询、手动刷新以及加载更早历史时的旧快照不能覆盖已经展示的增量或终态。

上下文同时核对文本 UTF-8 字节、消息数量和 Token 预算： estimatedInputTokens + outputTokenReserve + safetyTokenReserve <=
contextWindowTokens。 默认应用侧 context-window-tokens=8192、context-safety-tokens=256，输出上限仍为 2048，因此默认输入估算上限为
5888； 这不是供应商官方模型窗口声明。TokenEstimator 是可替换策略，当前 ConservativeTokenEstimator 使用 UTF-8
字节加每条消息及回复模板余量， 版本为 utf8-byte-upper-bound-v1，明确是保守估算，不是 DeepSeek 官方精确分词或实际计费用量。
整对裁剪最旧问答，优先保留最新成功历史和完整当前问题；当前问题单独超过容量则在预算预占与模型派发之前拒绝。
重新生成保持原始上下文，重新核对新输出预留。计量事实与估算器版本纳入 v2 快照摘要并保存到侧表，旧 v1 快照继续读取。

新增配置仍使用 application-ai-new.yml 中的 Maven 占位符，从 app.properties 编译进去：
streaming-enabled=true、context-window-tokens=8192、context-safety-tokens=256、stream-max-clients=64、stream-timeout=PT2M。
streaming-enabled=false 可以保持非流式模型调用和当前轮次查询观察。初始化接口 streaming 字段反映实际聊天模式。

升级顺序：停止旧后端，确认第二批工作表已部署，执行
[第三批迁移脚本](../arte-app/scripts/arte-ai-new-stream-ddl-mysql.sql)，重新构建并启动。 脚本新增增量、部分正文及 Token
预算表，并原子放宽快照格式约束以接受 v1/v2，不改写旧快照或聊天数据。 新安装的模型和聊天基础 DDL 已包含新表；已有数据库仍须执行迁移脚本更新原快照约束。
缺少所需表会在启用组件时启动失败。反向代理须保留 SSE 流和长连接，不缓存响应；服务端返回 Cache-Control: no-store 与
X-Accel-Buffering: no。 本次没有执行生产数据库迁移或真实供应商调用。

本批后端相关回归 114 项、前端相关回归 58 项通过，覆盖逐字节 UTF-8 流、实际本机 HTTP 分块传输、部分输出刷新／游标补读、断流与取消、
旧租约拒写、增量事务回滚、权限撤销、有界订阅、上下文整对裁剪与摘要保护、旧快照升级兼容，以及前端刷新／分页与增量并发合并。
数据库验证使用 H2；升级测试将 MySQL 多子句 ALTER 拆为 H2 等价语句，不证明 MySQL 部署语法或 DDL 原子性。 全项目 TypeScript
检查仍只有 canvas-ai-dialog.tsx 第 204、212 行既有 attachments 类型错误，本批聊天代码无新增类型报错。
