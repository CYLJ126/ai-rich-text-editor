# 新 AI 会话 HTTP 接口

入口为 `com.arte.ainew.web.controller.NewAiConversationController`，保留已有 POST 路径。
需开启 `arte.ai-new.enabled=true` 并部署原有新 AI DDL；本次接口不要求新增表、不初始化预算、不调用模型。
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
