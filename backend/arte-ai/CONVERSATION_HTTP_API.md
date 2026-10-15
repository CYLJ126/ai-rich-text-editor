# 新 AI 会话 HTTP 接口

入口为 `com.arte.ainew.web.controller.NewAiConversationController`，保留已有 POST 路径。 需开启 `arte.ai-new.enabled=true`
。2026-10-15 的目录能力增加 4 张表，部署步骤见本文末尾；不初始化预算、不调用模型。
以下路径不含应用的 context-path；当前应用若配置 `/arte`，请求时添加该前缀。

## 认证与公共参数

所有接口显式声明方法级 `@PreAuthorize("isAuthenticated()")`，适配 arte-app 的 URL 权限收集规则，不允许匿名访问。
MVC 请求线程通过 `ExecutionContextFactory.createCurrent` 捕获身份，
服务端固定申请 `ai:conversation`；请求的 `scope.tenantId`、`scope.workspaceId` 必须匹配当前认证名称对应的 grants。
owner、主体 ID、角色、scopes、预算、release 和执行期限均不作为客户端可指定的身份信息。
release 来自服务端配置，读取期限为 30 秒与平台 maximum-timeout 中的较小值。

```json
{
  "scope": {
    "tenantId": "实际授权租户",
    "workspaceId": "实际授权空间"
  }
}
```

列表请求组合 core 的 `PageParam`，省略 `page` 时为 `{ "current": 1, "size": 20 }`。
显式 null、页码小于 1、页大小不在 1～100、偏移量溢出均拒绝；数据库查询不回填或修改请求对象。
当前只提供分页条件，不支持关键词、时间或状态筛选；因此未继承带有这些通用筛选条件的 `BaseParam`。

成功单值使用 `ResultContext<ConversationResponse>`；分页使用 `PageView<T>`，包含 `records/current/size/total`。
分页失败也保持 PageView 结构；返回文案按当前 HTTP 请求 Locale 构建，不依赖数据库线程语言。
请求仍需携带已有系统的登录凭据，并可通过 `Accept-Language` 选择语言。

## 创建会话

`POST /ai-new/conversation/createConversation`

请求头必须包含 `Idempotency-Key`（非空，最多 256 字符）；客户端为一次创建操作分配唯一键，网络重试沿用原键。

```json
{
  "scope": { "tenantId": "实际授权租户", "workspaceId": "实际授权空间" },
  "title": "最小链路联调"
}
```

title 为非空且最多 256 字符。可选 `chatProfile`、`resources` 委托已有服务校验；首版只支持 profile=null、无关联资料。
省略或传 null 的 resources 规范化为空列表。
同 owner、同键、同内容返回持久化的原会话；同键改变内容返回 409 / `AI_IDEMPOTENCY_CONFLICT`。
创建不执行模型，不创建或清空预算账本。

## 会话列表

`POST /ai-new/conversation/listConversations`

```json
{
  "scope": { "tenantId": "实际授权租户", "workspaceId": "实际授权空间" },
  "page": { "current": 1, "size": 20 }
}
```

数据库只查询当前 owner 的记录，通过 COUNT 和 LIMIT/OFFSET 分页，不将全部会话加载到内存。
当前按数据库会话键升序稳定排序，**不代表创建或更新时间顺序**；跨页并发创建时遵循普通 offset 分页语义。
超出末页返回空 records，并保留请求 current/size 和实际 total。

## 会话详情

`POST /ai-new/conversation/getConversation`

```json
{
  "scope": { "tenantId": "实际授权租户", "workspaceId": "实际授权空间" },
  "conversationId": "创建接口返回的 ID"
}
```

data 包含 conversationId、title、version、chatProfile、resources、state、createdAt、updatedAt，
不暴露内部 owner 或数据库哈希。不存在和其他用户的会话统一返回 404 / `AI_CONVERSATION_NOT_FOUND`。

## 会话轮次

`POST /ai-new/conversation/queryTurnsOfConversation`

```json
{
  "scope": { "tenantId": "实际授权租户", "workspaceId": "实际授权空间" },
  "conversationId": "创建接口返回的 ID",
  "expectedVersion": 1,
  "page": { "current": 1, "size": 20 }
}
```

expectedVersion 必填、非负，来自最新会话详情。存储在同一事务锁定会话、核对归属及版本、计数和查询该页轮次，
防止查询期间新轮次插入。多页之间版本变化返回 409 / `AI_VERSION_CONFLICT`；前端刷新详情后从第一页重新读取。
按 sequence 升序返回，空会话返回空分页；跨 owner 和不存在均返回相同的 404。

records 包含固定 userMessage、turnId、sequence、parentTurnId、supersedesTurnId、version、时间戳、
invocationIds 和 selectedInvocationId。回答文本、执行状态与用量需通过调用查询接口取得；轮次不复制第二份执行终态。
当前未自动选择回答候选，selectedInvocationId 可为 null，客户端应保留 invocationIds 用于后续查询。
本接口用于页面展示历史；生成时的 `ConversationService.history`、多轮上下文、编辑／重新生成仍为后续工作。

## 错误与验证

### 调试日志

会话创建、列表、详情和历史入口记录只含范围／目标／分页／版本的诊断上下文，并在授权解析后补充 `traceId`、`executionId` 和稳定主体
ID。信息放在请求属性中，MVC 异步派发后的异常处理器仍可读取，不依赖后台线程的 SecurityContext 或 MDC。

- `AI conversation query started/completed`（DEBUG）：操作、范围、分页、版本、耗时、总数及本页记录数；失败摘要
  `AI conversation query failed` 为 WARN，含与 HTTP 一致的 trace ID。
- `New AI SQL started/completed`（DEBUG）：完整 Mapper statement ID、SQL 类型、参数化 SQL、耗时和返回行数。新 AI
  独立工厂注册专用诊断拦截器，仍禁用 MyBatis 默认参数／结果日志。
- `New AI SQL failed`（ERROR）：失败 statement、参数化 SQL、SQLState、数据库错误码和根因类型，可区分 `countConversations` 与
  `conversationSnapshots` 等不同阶段。
- `New AI HTTP failure`（ERROR）：HTTP 路径、请求诊断上下文、根因类型和 SQL 错误码，并输出原异常的完整堆栈及 `Caused by`。预期
  4xx 仅记录 WARN 摘要。

重新构建并重启后复现问题，先查 `New AI SQL failed`，再查同一次会话查询的 trace ID 和 `New AI HTTP failure` 下的数据库根因。正常成功日志需
DEBUG；现有 `arte-app` 的 Log4j2 root 为 DEBUG，若运行环境调高了级别，可对
`com.arte.ainew.application.conversation.DefaultConversationService` 和
`com.arte.ainew.persistence.mybatis.ExecutionSqlDiagnostics` 单独启用 DEBUG。失败诊断在 ERROR 级别可见。

日志不额外序列化认证头、幂等键、聊天正文、内部 JSON 快照、绑定参数或结果正文。完整异常堆栈保留数据库自身的错误描述；不要为查询问题开启全局
SQL 参数打印。

新增 H2 故障测试分别移除会话表、`owner_key` 列和 `workspace_snapshot` 列，验证实际 SQL 异常、SQLState、完整堆栈、跨 MVC
异步派发的请求标识，以及日志未输出认证头和操作键；另验证正常空列表的分页响应。只修改各测试自己的临时数据库：

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=ConversationHttpIntegrationTest#diagnostics*' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-10-10：上述 3 项诊断测试与模块编译通过。会话／持久化完整回归另发现已有 XML 占位符 `workspaceSnapshot` 与 Java
`@Param("snapshot")` 不一致，52 项中 47 项失败；本次日志改动未修改 Mapper SQL、DDL 或实际数据库。该绑定问题与生产列表查询的
SQL 语法异常应分别按新日志定位。

2026-10-11 修复与核对：

- 本机 22:31 日志确认列表失败于 `AdmissionMapper.conversationSnapshots`，SQL 使用 `SELECT workspaceSnapshot`，MySQL 报
  `Unknown column 'workspaceSnapshot' in 'field list'`（SQLState `42S22`、错误码 `1054`）；预算读取也有同类错误。当前源码的读取列已为
  `workspace_snapshot`，必须重新构建并重启，不能继续使用旧进程中的 Mapper。
- 本批将会话、执行、事件、预算与字节存储的数据库列统一为 `workspace_snapshot`，对应 Java 参数／投影字段为 `workspaceSnapshot`
  ，XML 值占位符使用 `#{workspaceSnapshot}`，与 `@Param("workspaceSnapshot")` 对齐。修复事件写入残留的驼峰列名，并恢复上下文投影的
  `context-snapshot` 类型别名；已有快照 JSON 和类型别名不因持久化参数改名而改变。
- 已只读核对 `backend/profile/app.properties` 指向的实际 MySQL：9 张快照表的 `workspace_snapshot` 列存在，计数、分页快照和预算快照
  SQL 可执行；该连接下的结算表仍为 `snapshot`
  。若实际运行也使用此连接，发布时执行[结算列名迁移](scripts/arte-ai-new-settlement-snapshot-migration.sql)
  ；仅在旧列存在且新列不存在时执行，已改名的库跳过。此轮没有修改实际数据库。
- 按 `workspace_snapshot`／`workspaceSnapshot` 统一后，新 AI 全包回归 292 项中 288 项通过、4 项因未配置隔离 Redis
  而跳过；其中持久化回归 44 项全部通过，验证迁移保留结算事实、原回执重放和后续结算不重复计费。测试使用隔离 H2／本机模拟
  HTTP，未调用真实模型。
- `arte-app` 及依赖模块离线打包通过，生成 `backend/arte-app/target/arte-app-boot.jar`；结算列名迁移与应用重启尚未执行。

完整新 AI 回归：

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=com/arte/ainew/**/*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

本机 HTTP／SSE 模拟服务需要允许绑定回环端口；Redis 条件测试需要其声明的隔离测试实例。只读 SQL 核对不代表部署后 HTTP
或真实模型链路已经验收。

### 响应与契约验证

- 400：请求格式、字段、分页、幂等键或当前不支持的会话配置。
- 401：没有有效登录身份。
- 403：当前登录身份无权使用请求空间或 ai:conversation。
- 404：会话不可见或不存在。
- 409：幂等内容冲突或会话版本冲突。
- 500：基础设施错误，仅返回 core 的安全错误文案。

已增加真实 MVC + H2 MySQL 模式集成测试，覆盖参数绑定、序列化、响应语言、幂等、分页、空页、
历史顺序、真实受理后的轮次引用、跨用户隔离、版本冲突和 MVC 身份捕获。
测试不访问用户数据库、不使用真实供应商凭据、不调用模型。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=ConversationHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 会话目录与元数据接口（2026-10-15）

本节是 `/AI/ChatNew` 新会话栏的后端契约。旧
`createConversation/listConversations/getConversation/queryTurnsOfConversation`
路径及页码排序保持兼容。新接口统一使用 `ResultContext<T>`（包括游标分页），失败也使用该信封。 所有路径仍以
`/ai-new/conversation/` 开头，具有相同的登录、scope 解析及 owner 隔离；写入另执行真实写权限校验。 搜索、临时聊天、分组、归档、删除没有新增命令。

| POST 路径 | 请求字段（均含 scope） | data |
| --- | --- | --- |
| createDefaultConversation | 仅 scope；Idempotency-Key 必填 | 旧 ConversationResponse |
| getConversationMetadata | conversationId | Item |
| renameConversation | conversationId、expectedMetadataVersion、title；Idempotency-Key 必填 | Receipt |
| setConversationPinned | conversationId、expectedMetadataVersion、pinned；Idempotency-Key 必填 | Receipt |
| getMetadataCommandReceipt | commandId | CommandResult |
| listConversationsCursor | pinned、size（1～20）、可选 cursor/includeTotal | Page |
| queryConversationWindow | normalSize（1～100）、可选 anchorConversationId | Window |

`scope`、目标和写入版本不可省略。pinned 是明确目标状态，不能省略或用 toggle。 人工名称在提交前去首尾空格，服务端也执行同样规范化，并拒绝空白及超过
256 UTF-16 单元的人工输入。 默认创建按 Asia/Shanghai 的当前日期生成 `新会话yyyyMMdd`；同一天允许多个同名会话。
创建的摘要不包含服务器时钟，同一原键跨午夜重试仍返回首次会话 ID，不另建会话或报日期变化冲突。
旧创建接口允许自定义初始名；首次问题命名规则同新创建，已有旧数据迁移时采用保守保护（见下文）。

### 元数据与保存确认

Item 字段：`conversationId/title/metadataVersion/pinned/titleManuallyEdited/autoTitleResolved/previewText/`
`userMessageCount/lastMessageGeneratedAt/sortActivityAt/createdAt/statisticsReady`。 不返回 owner、完整消息、资料或执行快照。时间是
UTC Instant，投影和排序保留微秒精度；前端按约定时区展示 yy-MM。 无消息时 `lastMessageGeneratedAt=null`，排序才回退
createdAt。尚未回填的消息数／时间可以是 null，不能显示成已知的 0。
`statisticsReady=false` 表示回填未完成或历史证据不完整，不得把未知日期伪装成创建日期。

Receipt 包含：`conversationId/commandId/conversation(Item)/pinnedCount/changed`。 成功写入才推进 metadataVersion；同值提交返回
changed=false，不推进版本。 人工改成不同名称同事务设置 titleManuallyEdited=true；改回日期名也不清除该标志。 命名／置顶不推进
Conversation.version，不改消息时间或活动排序。 后台聊天保存先锁定并读取最新会话快照，保留人工名称；元数据保存保留现有执行版本与活跃调用门闩。

```json
{
  "scope": {"tenantId": "tenant", "workspaceId": "workspace"},
  "conversationId": "会话 ID",
  "expectedMetadataVersion": 2,
  "pinned": true
}
```

置顶上限为同 tenant/workspace/subject 下 7 个。服务端使用 owner 名额锁和会话行锁，在 READ_COMMITTED 短事务内检查并提交，
跨进程争抢第 7 位也不能成为 8 个。取消置顶释放名额；重复目标不重复计数，不从 localStorage 导入旧置顶。

改名与置顶共用元数据命令键空间（与创建命令分开）。同键同语义重放原结果；同键不同目标、版本或参数返回 409 /
AI_IDEMPOTENCY_CONFLICT。 **成功及可预期业务拒绝均持久化**，原失败不能因后来名额变化变成一次新写入。 基础设施异常整笔回滚，不伪装成业务拒绝。

`getMetadataCommandReceipt` 在命令已落库时返回 200，data 为：

```json
{
  "commandId": "原 Idempotency-Key",
  "outcome": "VERSION_CONFLICT",
  "receipt": null
}
```

成功时 outcome=APPLIED、receipt 为原 Receipt；查询不再写入。 404 表示当前 owner 未查到已提交回执，
**不能证明原请求未在执行**；可继续查原键或原样重放原命令，不能换键竞争写入。 明确版本冲突后，读取目标最新元数据、由用户确认修订的保存意图，分配新命令键。超时／5xx
保留原请求与原键。 跨 owner 的会话读取／修改统一 404，不泄露其他主体的存在性。

错误：版本／幂等冲突均为 409；第 8 个置顶返回 409 / `AI_PIN_LIMIT_REACHED`（205043），文案为“最多可置顶7个会话，请先取消一个”。
无权限／只读为 403；输入和游标错误为 400；基础设施失败为 500。

### 命名和列表信息维护

- 首条受理的用户问题按扩展字素簇（Java 21 的 `\X`）计数；超过 20 字取前 20 字加“……”，恰好或不足 20 字保留原问题。
- 最新用户问题摘要同理，阈值 30。多个 Text 部分按换行连接；没有文本的附件消息不虚构标题，记录自动处理已跳过。
- titleManuallyEdited 为真时跳过自动覆盖。autoTitleResolved 持久化，只处理首条问题；重试、重新生成不再自动改名。
- HTTP 人工输入上限仍为 256 UTF-16 单元；内部自动标题容纳完整字素簇，避免 20 个带肤色 emoji 被该旧上限切坏。
- 用户消息以 conversationId+messageId 的唯一事实去重计数，不按 Turn 数、Invocation 数或助手数计数。
- 用户生成时间取持久化 Turn.createdAt；助手消息取对应 invocation/attempt 首条耐久 TextDelta 事件 occurredAt。
  同一助手消息后续批次只查去重事实，不重复写列表统计。部分输出也是真实可见消息；usage、工具参数增量和 SSE 到达时间不是消息生成时间。
- 用户投影在受理事务中维护，助手投影在输出事件落库事务中维护；失败整体回滚。后台维护不触发前端列表查询。
- `turnsForChat` 受理响应增加可选 `titleMetadata`：title、metadataVersion、titleManuallyEdited、autoTitleResolved。
  通过目标轻量读取取得，仅供更新名称；不携带摘要／轮数／日期来暗中刷新目录。辅助读取失败返回 null，不影响已受理回执。

### 分页、排序与窗口重读

两区均按 `(sortActivityAt DESC, conversationId DESC)` 排序。conversationId 使用数据库二进制字符排序打破并列。 排序索引覆盖
owner/pinned/active/sortMicros/conversationId；列表仅查询元数据表，不查每个会话历史。 旧会话列表仍按 id_key ASC 的 offset
协议；不能混用两种续页参数。

```json
{
  "scope": {"tenantId": "tenant", "workspaceId": "workspace"},
  "pinned": false,
  "size": 20,
  "includeTotal": true
}
```

Page 为 `records/nextCursor/hasMore/total`。服务器读取 size+1 判断 hasMore，未请求 includeTotal 时 total=null，续页默认不计数。
前端应只在初次／显式刷新需要时请求 total。末页 nextCursor=null、hasMore=false；达到前端 100 条保留上限不表示已到末页。 cursor
是不透明 Base64URL 位置，绑定当前 owner、pinned 和固定 V1 排序。它不是凭据或加密数据；客户端必须原样回传，
不能解析用于业务判断。服务端严格校验结构、范围与过滤条件，所有 SQL 始终再次带当前已授权 owner，游标不授予资源访问权。

`queryConversationWindow` 用同一次 REPEATABLE_READ 短事务读取完整置顶（最多 7 条）和连续普通窗口，返回：
`pinned(Page)/normal(Page)/anchorConversationId/anchorFallback`。 普通窗口最多 `min(normalSize,100-本次置顶数)` 条，返回新的
nextCursor，避免先追回已经释放的前部页面。 传可见首个普通会话 ID 时按其 **当前**排序位置从该行开始读取；锚点不存在、越权或已经置顶则
fallback 到第一页。 置顶迁走当前锚点时，客户端应优先传下一可见普通行 ID，以保留位置；像素偏移由前端保留并恢复。 无锚点即数据库第
1 页，供初次、手动刷新和返回顶部使用。

多个请求之间不是冻结的数据库全量快照。消息改变活动排序时，移到续页游标之前的会话可能在本轮浏览未出现；
手动刷新第一页恢复当前顺序。前端每次窗口重读独立批次、丢弃迟到响应、按 ID 去重；这些保护已接入 `/AI/ChatNew`，
实现范围及隔离验证见[前端列表验收记录](../../frontend/src/pages/AI/ChatNew/CONVERSATION_DIRECTORY_ACCEPTANCE.md)
。真实服务联调仍待实施。

### 迁移、旧数据回填与回退

1. 使用新实例前备份数据库，停旧写入进程。已有库仅执行
   [会话元数据增量 DDL](scripts/arte-ai-new-conversation-metadata-migration.sql)；空库使用更新后的
   [完整 DDL](scripts/arte-ai-new-ddl-mysql.sql)，二者不可重复执行。本次开发没有在用户数据库执行 DDL。
2. 这四张表分别保存轻量元数据、原命令结果、消息身份时间去重事实及回填进度；不复制完整消息正文或另一份执行终态。
   新代码创建会话／受理消息已需要这些表，不能先部署代码再等表就绪。
3. 首次升级时显式设置 `arte.ai-new.conversation-metadata-backfill.enabled=true`。 新 AI 自动配置注册启动回填，分批间隔
   100ms；每次初始化最多 10 个缺失投影并推进最多 10 个任务， 每任务一次最多一轮或 100 个历史事件。进度在数据库内，退出／重启／多实例不会重复加数。
   该开关不执行 DDL，也没有对普通用户开放运维 HTTP 入口。 通过项目配置时，将该项写入 `backend/profile/app.properties`，重新生成
   Maven 过滤资源并重启；
   `ainew/config/application.properties` 已映射此开关，common／example 默认 false，避免未迁移数据库启动回填。 也可直接使用
   Spring 启动参数 `--arte.ai-new.conversation-metadata-backfill.enabled=true`。
4. 未记录人工标志的历史名称保守视为人工名称，阻止未来自动覆盖；历史置顶默认 false。
   初始投影统计为未知，完成当前会话历史扫描后发布真实去重计数与生成时间；空会话可直接确认为 0/null。 列表请求不触发回填。完成日志为
   `AI conversation metadata backfill completed`，任务表为空后可关闭开关。 首次开放新列表前应完成缺失投影的注册；回填过程中统计允许未知。
5. 历史调用或事件已裁剪时不猜测助手生成日期：回填保留真实用户计数／摘要，lastMessageGeneratedAt=null、statisticsReady=false。
   这类旧记录只能按最后已知消息事实排序，证据恢复前不宣称完整活动顺序。 不因失败而永久重跑同一裁剪任务；需要原始证据恢复后单独校准。用户最新问题／助手实际新输出仍正常维护。
6. 回退优先停回填／切换界面，保留新增表和幂等事实；不要直接 DROP 表丢弃置顶与人工保护。 若需回退旧二进制，应先检查新自动标题是否超过旧代码
   256 UTF-16 容量，保留备份并使用兼容读取的版本； 不允许旧写入进程与新代码混跑，旧代码无法同步新目录投影。

验证使用隔离 H2 MySQL 模式，覆盖并发名额、原回执、版本隔离、Unicode、消息去重、历史回填、锚点和 HTTP 授权。 生产 MySQL 的执行计划、DDL
上线、回填完成及前端接入须分别验收，H2 测试不等于生产部署完成。

### 已有库列表为空的修复验证（2026-10-15）

- 现象：消息区从原会话记录恢复了“新会话10142202”，新列表接口返回空窗口。日志显示 10:26／10:35 曾因缺少元数据表返回 500，10:36
  建表后返回 200。
- 只读核查当前开发库：原会话 9 条，元数据 0 条，缺失投影 9 条，任务 0 条。建表不会自动注册旧会话；项目过滤配置还缺少上述回填开关的映射。
- 补齐配置模板和安全默认值，并调用现有持久化适配器的 `backfillConversationMetadata(10)` 执行有界、幂等回填；未执行
  DDL，未修改原会话名称或消息正文。
- 16 批后完成：缺失投影 0、待处理任务 0。实际适配器查询该会话所属空间的数据库第一页返回普通会话 3 条，包含目标会话；目标用户消息数为
  1、statisticsReady=true。
- 配置映射及旧库回填专项 3 项通过，IDE 定向编译通过。本次确认的是当前开发库修复，页面需显式刷新列表；浏览器连接／权限限制下未直接完成页面验收。

### 本阶段验证记录（2026-10-15）

- 专项 93 项通过：会话 HTTP 16、聊天 HTTP 13、持久化 52、存储工厂 4、错误映射 7、列表成本 1。 包含真实并发第 7
  位争抢、拒绝／成功回执重放、人工／自动竞争、活动聊天中的版本隔离、跨午夜默认名、 19／20／21、29／30／31 及带肤色组合
  emoji、投影回滚、旧库增量迁移、历史裁剪时未知日期、游标范围和窗口锚点。
- 扩展新 AI 回归共 339 项，335 通过、4 项按原 Redis 等环境条件跳过。使用本地模拟 HTTP 服务和隔离 H2，无真实供应商调用。 命令排除了
  ProfileConfigurationTest：原工作区 PLATFORM 模式缺少 ExecutionAuthorizationResolver Bean， 该类另行完整运行的 3 项中 2
  项装配失败、1 项通过；本次没有改动相关平台实现或配置来掩盖失败。
- 回归中修复一个时间精度边缘：新会话 createdAt 可包含纳秒，数据库时钟为毫秒，快速受理／改名的 updatedAt 不能早于锁内已有时间。
  该保护不改变列表的消息生成时间口径。

```sh
# 专项
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=MybatisExecutionPersistenceTest,ConversationHttpIntegrationTest,ConversationListPerformanceTest,ExecutionSqlSessionFactoryTest,AdmissionExceptionTest,ChatHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 新 AI 回归，本地模拟 HTTP 需允许回环端口
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=com/arte/ainew/**/*Test,!ProfileConfigurationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

列表基线环境：本机 macOS arm64，Maven 3.9.16，运行 JDK 26.0.2.1，编译 release 21；隔离 H2 MySQL 模式。 数据为单 owner
的合成轻量元数据；测试移除历史／会话快照表，以确认目录完全不依赖历史读取。 每组预热 10 次、测量 30 次续页（20
条），无硬编码的机器速度断言；以下取扩展回归这一次的观测值：

| 会话数 | 置顶数 | P50 ms | P95 ms | 首个 20 条 Page 字节数 |
| --- | --- | --- | --- | --- |
| 1,000 | 0 | 1.456 | 2.324 | 6,643 |
| 1,000 | 7 | 1.598 | 3.565 | 6,642 |
| 10,000 | 0 | 4.240 | 6.307 | 6,644 |
| 10,000 | 7 | 5.442 | 9.985 | 6,643 |

有 total 的第一页 2 条 SQL，续页 1 条；完整窗口读取 4 条（带锚点另 1 条），窗口两区合计不超过 100 条。 H2 EXPLAIN
确认命中目录索引。这是本地查询结构和成本基线，不代表真实 MySQL、网络／鉴权端到端延迟或浏览器滚动验收； 上线前仍需在目标 MySQL
数据规模及实际消息历史下检查 EXPLAIN 与延迟。
