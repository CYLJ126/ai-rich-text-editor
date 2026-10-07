# 新 AI 执行查询 HTTP 接口

`NewAiInvocationController` 提供执行状态、结果和单页耐久事件重放，沿用已有新 AI Controller 的 POST、`@Valid`、`Mono<ResultContext<...>>` 和请求 Locale。只有 `arte.ai-new.enabled=true` 与 `arte.ai-new-execution.enabled=true` 同时满足时才注册；生成、执行配置的前置依赖仍需完整启用。

这些接口只读取，不启动 Worker、不调用模型，也不触发重新生成。实时 SSE watch、取消及远端核对尚未实现。

## 认证与共同参数

所有请求携带 `Authorization: Bearer <登录 Token>` 和 `Content-Type: application/json`，可通过 `Accept-Language` 指定响应语言。应用 context-path 若为 `/arte`，以下路径均加上 `/arte` 前缀。

`scope` 是选择范围，不是授权凭据。服务器在 MVC 请求线程捕获当前 Authentication，申请 `ai:read`，服务层再次检查当前授权及主体／空间归属；不接收客户端声明的 owner、权限或原执行上下文。每次读取使用新查询期限（30 秒与服务器 maximumTimeout 中较小值），允许读取已经超过原模型执行期限的记录。读取不需要 `Idempotency-Key` 或预算参数。

## 状态

```http
POST /ai-new/invocation/getInvocationStatus
```

```json
{
  "scope": {"tenantId": "example-tenant", "workspaceId": "example-workspace"},
  "invocationId": "<消息提交返回的 invocationId>"
}
```

成功返回 HTTP 200，外层为 `ResultContext` 的 `success`、`code`、`desc`、`data`。`data` 示例：

```json
{
  "invocationId": "<调用 ID>",
  "kind": "GENERATION",
  "conversation": {
    "conversationId": "<会话 ID>",
    "conversationVersion": 0,
    "turnId": "<轮次 ID>"
  },
  "state": "SUCCEEDED",
  "version": 2,
  "activeAttemptId": "<Attempt ID>",
  "resultAvailable": true,
  "partial": false,
  "error": null,
  "acceptedAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:01Z"
}
```

- `state` 读取耐久 Invocation 权威，不能根据 HTTP 200 或事件流结束判断生成成功。
- `conversation` 可为 null；其中 `conversationVersion` 是受理时的会话版本，不是当前会话版本。
- `resultAvailable` 表示已有耐久结果引用，不表示输出完整，也不代替读取结果时的字节完整性校验。没有引用时 `partial=null`。
- `FAILED` 或 `UNKNOWN` 可能仍有可读的部分结果。`UNKNOWN` 表示远端结果尚不确定，不能据此自动重发或认为会话已经解除活跃限制。
- `error` 只包含平台安全错误码、阶段、可重试事实、副作用确定性及关联 ID；`retryable` 不授予自动重发权限。
- 响应不返回原始 InvocationRequest、用户输入、授权快照、预算选择或存储摘要。

## 结果

```http
POST /ai-new/invocation/getInvocationResult
```

请求与状态接口相同。服务层先读取 Invocation 权威结果引用，再校验结果字节的归属、类型、版本及摘要；未提交引用的孤立结果不会返回。

HTTP 200 时 `data.invocationId` 为本次调用，`data.kind` 区分封闭结果类型，`data.result.value` 为对应类型的结果。当前文本生成使用 `GENERATION`，其 `value` 包含 `outputs`、`complete`、`finishReason`、`model`、`usage` 等字段。

- 文本位于 `data.result.value.outputs[*].content[*].text`，保持原有空白和换行。
- `complete=false` 的部分输出仍可读取，须结合状态接口显示失败或结果未知。
- `usage.basis=UNKNOWN` 时 Token 数保持 null；不补零或估算实际费用。已报告用量也不代表预算结算已经完成。
- 尚无结果引用时返回 HTTP 409／`AI_RESULT_NOT_AVAILABLE`，页面可继续查询状态；已确定失败且没有结果时，不应无限重试结果接口。
- 已提交引用但字节缺失或损坏属于 HTTP 500 基础设施错误，不伪装为“结果尚未生成”。

## 事件重放

```http
POST /ai-new/invocation/invocationEvent
```

```json
{
  "scope": {"tenantId": "example-tenant", "workspaceId": "example-workspace"},
  "invocationId": "<调用 ID>",
  "afterSequence": 0,
  "limit": 100
}
```

`afterSequence` 为必填非负整数、排他游标，0 从首个保留事件开始；`limit` 为必填整数，范围 1～256。HTTP 200 的 `data` 包含：

```json
{
  "invocationId": "<调用 ID>",
  "events": [],
  "nextCursor": {"executionId": "<调用 ID>", "afterSequence": 0},
  "retainedAfterSequence": 0
}
```

`events` 返回已持久化平台事件，包括 `sequence`、`kind`、负载类型／版本、时间和负载。生成输出在 `kind=OUTPUT` 的 `payload.events` 内；首批文本增量字段为 `text`。

下一页请求使用 `nextCursor.afterSequence`，按 `sequence` 去重。空页只表示本次没有新事件，不能证明调用结束。过期游标返回 HTTP 410／`AI_CURSOR_EXPIRED`，页面应读取权威状态／结果并明确处理历史缺口，不应静默重置游标假装事件完整。游标只选择读取位置，不授予访问权。

## 错误与测试

| HTTP 状态 | 含义 |
|---|---|
| 200 | 查询成功，不表示模型执行成功 |
| 400 | 参数缺失、非法或超出单页上限 |
| 401 | 未登录或认证无效 |
| 403 | 当前身份缺少 ai:read 或空间未授权 |
| 404 | 调用不存在或不属于当前主体／空间，二者响应一致 |
| 409 | 权威结果尚未可用 |
| 410 | 事件游标已过期 |
| 500 | 数据库、结果字节或其他基础设施故障，响应不暴露原始异常 |

`InvocationHttpIntegrationTest` 使用真实 MVC 参数绑定、隔离 H2、现有授权／受理／派发／存储组件和测试网关信号，覆盖查询状态、完整／部分结果、未知用量、游标分页与过期、身份与权限隔离、查询期限和基础设施错误。不访问当前数据库或真实模型。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=InvocationHttpIntegrationTest,ChatHttpIntegrationTest,ConversationHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
