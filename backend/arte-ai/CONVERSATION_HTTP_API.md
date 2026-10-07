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
