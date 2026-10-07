# 新 AI 执行查询 HTTP 接口

`NewAiInvocationController` 提供执行状态、结果、单页耐久事件重放和 SSE 通知，沿用已有新 AI Controller 的 POST、`@Valid`、
`Mono<ResultContext<...>>` 和请求 Locale。只有 `arte.ai-new.enabled=true` 与 `arte.ai-new-execution.enabled=true`
同时满足时才注册；生成、执行配置的前置依赖仍需完整启用。

这些接口只读取，不启动 Worker、不调用模型，也不触发重新生成。SSE 订阅不重新执行调用；取消及远端核对尚未实现。

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
  "budgetState": "SETTLED",
  "resultAvailable": true,
  "partial": false,
  "error": null,
  "acceptedAt": "2026-10-07T08:00:00Z",
  "updatedAt": "2026-10-07T08:00:01Z"
}
```

- `budgetState` 查询本次 Attempt 的预留状态：`NOT_RESERVED`（未预留）、`RESERVED`（等待结算）、`PENDING_RECONCILIATION`（待对账，保留
  held）、`SETTLED`（已扣费）、`RELEASED`（已释放）。它独立于 Invocation.version，不能用账户总 held 推断某次调用是否结算。
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

## SSE 通知

```http
POST /ai-new/invocation/watchInvocation
Accept: text/event-stream, application/json
Authorization: Bearer <登录 Token>
Content-Type: application/json
```

```json
{
  "scope": {
    "tenantId": "example-tenant",
    "workspaceId": "example-workspace"
  },
  "invocationId": "<调用 ID>",
  "afterSequence": 0
}
```

使用 fetch 流式读取，以便携带现有认证头。`afterSequence` 为必填排他游标，浏览器成功处理通知后保存 sequence，重连携带该值。
首次连接从 0 重放；事件指针可能重复，按调用 ID 和 sequence 去重。

```text
id:5
event:invocation
data:{"executionId":"<调用 ID>","sequence":5,"kind":"TERMINAL"}

```

通知仅包含调用 ID、序号和种类，不携带原请求、输出正文或预算金额。收到 STARTED/TERMINAL/BUDGET_CHANGED
时用原有状态接口读取权威状态；有已提交结果时读取结果。 TERMINAL 到达即可展示回答及历史；若
budgetState=RESERVED，继续观察直到预算通知到达并刷新账本。流关闭不代表调用或结算成功。

- Invocation/Event/EVENT Outbox 同事务提交。afterCommit 只异步唤醒发布器；回滚不通知。
- `ExecutionEventPublisher` 只消费 EVENT，每批最多 64 条，校验租约后发布并 ACK。无在线浏览器也可 ACK；之后建连仍从耐久
  EventStore 重放。
- 提交后唤醒是正常路径；统一 5 秒恢复扫描负责进程崩溃、遗漏唤醒和租约恢复，不为每个连接设置数据库轮询。
- watch 先注册通知再分批读取历史，每页最多 64 条；同一个连接提示合并，序号去重。已读过的旧提示不再查库。
- 每 15 秒发送注释心跳，不查询状态表。每 10 秒重新检查应用读取授权。连接期限为 60 秒与 maximumTimeout 的较小值； 到期重连重新通过
  HTTP Token 认证，不延长模型调用的执行期限。
- 建连前的参数、归属、权限和过期游标错误使用原 HTTP 错误响应。建连后的错误用 `event:error` 和安全的 `{httpStatus,code}`帧表示。
- 取得执行终态与预算处理结果后排空事件再关闭。没有预留的终态直接结束；RESERVED
  保持订阅，SETTLED/RELEASED/PENDING_RECONCILIATION 为本次预算处理结果。UNKNOWN 的后续核对可另行订阅；待对账仍保留 held。
- 慢连接采用有界提示、反压、连接期限和 MVC 有界写入线程池（4～16 线程、256 个待写任务），取消订阅释放监听器，不取消模型执行。
- 前端断线／连接不可用时每 10 秒查询一次并尝试重连，45 秒没有心跳则主动结束旧连接；401/403 停止自动请求。 410
  先读取权威状态、结果和历史，然后对该调用采用低频 HTTP，避免不断重连同一已过期游标。

运行开关仍是 `arte.ai-new-execution.worker-enabled=true`，同时启用 DISPATCH 和 EVENT 发布器，无新增 DDL 或凭据。 测试中关闭自动
Worker 时，可分别手动调用两个 Worker 的 pollOnce。

### 预算变更与 Redis 跨实例广播

`reserve` 和首次 `settle` 在账本事务内追加 `BUDGET_CHANGED` Event 和 EVENT Outbox，payload 为
`{state,reservationVersion,accountVersion}`。SSE 仍只传指针。回滚不通知；幂等重放不重复写事件、不重复扣费。
终态提交和账本结算独立，通知序号也独立于 Invocation.version；历史读取无需等到扣费结束。

配置在 `backend/profile/app.properties`，构建过滤到 AI application.properties：

```properties
arte.ai-new-events.transport=redis
arte.ai-new-events.channel=arte:ai-new:events:v1
arte.ai-new-events.publish-timeout=3s
```

默认 `local` 为进程内通知；多实例选 `redis`，复用现有 `RedissonClient` 与 Redis 连接配置，无新增凭据或依赖。
共享数据库的所有节点必须使用相同 transport/channel；不同环境应隔离 channel。Redis 模式缺少客户端会明确启动失败。
publish-timeout 限制为 100ms～30s，且必须短于 Outbox 租约。 所有 Redis 模式实例都订阅，包括关闭执行 Worker 的 HTTP 节点；开启
Worker 的节点领取 EVENT，发布成功后才 ACK。 广播仅传 `{schemaVersion,owner,invocationId,sequence}`，显式
StringCodec，不发送正文、金额或凭据。

Redis Pub/Sub 提示不耐久（见 [Redis 官方说明](https://redis.io/docs/latest/develop/use-cases/pub-sub/)）；数据库 Event 与
Outbox 是权威。 发布失败、超时或 ACK 失败保留租约等待恢复扫描；发布成功但订阅端断开时，重新订阅会唤醒本节点所有连接按游标重读数据库。
重复提示通过 sequence 去重，按 owner 隔离；浏览器断线通过重放和 10 秒 HTTP 兜底恢复。 停机只移除本组件的监听器，不关闭共享
RedissonClient。

无新增 DDL、菜单或权限。应先重建后端、更新所有实例，再启用 Redis 广播；新增预算事件要求这些实例使用支持 budget-changed 的编码器。
本次仍不提供逐字输出。

Spring MVC 使用响应式流适配
SSE，并根据写入需求消费事件，参见 [Spring 官方异步请求文档](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)。

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

`InvocationHttpIntegrationTest` 使用真实 MVC 参数绑定、隔离 H2、现有授权／受理／派发／存储组件和测试网关信号，覆盖状态／结果、SSE
已完成重放和活动调用推送、提交后唤醒、事务回滚、空页与终态提交竞争、游标过期及身份隔离。不访问当前数据库或真实模型。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=InvocationHttpIntegrationTest,ChatHttpIntegrationTest,ConversationHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Redis 集成测试仅在显式传入 `-Darte.ai-new.test.redis-address=redis://127.0.0.1:<临时端口>` 时运行；不读取应用 Redis
连接或真实凭据。 使用可销毁的独立 Redis 实例，不接生产数据。覆盖双实例通知、订阅断开期间已 ACK 事件的恢复、归属隔离和
HTTP-only 节点自动订阅。 本地预算测试覆盖终态先于结算、慢订阅者收到最后结算事件、广播失败未 ACK、账本／事件同事务回滚及幂等重放。
