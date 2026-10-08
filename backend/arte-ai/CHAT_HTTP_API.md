# 新 AI 消息提交 HTTP 接口

模型绑定、能力固定版本、兼容预算及服务器参数限制可通过
[聊天配置发现接口](CONFIGURATION_HTTP_API.md) 获取；提交时仍重新验证授权、配置和预算。

`NewAiChatController` 沿用会话 Controller 的 POST、`@Valid`、方法级认证声明、`Mono<ResultContext<...>>` 和按请求 Locale 包装的风格。启用条件为 `arte.ai-new.enabled=true`。

## 请求

```http
POST /ai-new/chat/turnsForChat
Authorization: Bearer <登录 Token>
Content-Type: application/json
Idempotency-Key: <本次提交操作的唯一键>
Accept-Language: zh-CN
```

应用部署的 context-path 若为 `/arte`，请求地址为 `/arte/ai-new/chat/turnsForChat`。以下引用对应 `examples/ainew-admission.yml` 的示例定义，须替换为实际授权配置，`conversationId` 使用会话创建接口返回值。

```json
{
  "scope": {
    "tenantId": "example-tenant",
    "workspaceId": "example-workspace"
  },
  "conversationId": "<已创建的会话 ID>",
  "expectedVersion": 0,
  "text": "请只回复：新 AI 链路测试成功。",
  "capability": {"type": "capability", "id": "text-generation", "version": "v1"},
  "binding": {"type": "binding", "id": "default-text", "version": "v1"},
  "budgetRef": "example-budget",
  "maxInputTokens": 1024,
  "generationOptions": {
    "maxOutputTokens": 128,
    "temperature": null,
    "topP": null,
    "stopSequences": []
  },
  "timeoutSeconds": 60
}
```

- 顶层字段均为必填；生成选项中 `temperature`、`topP` 可不指定，`stopSequences` 使用空数组表示无停止词。
- `scope`、绑定与预算只是选择参数，不授予权限。MVC 请求线程捕获真实登录身份，申请 `ai:invoke` 和 `ai:conversation`；授权解析器及现有服务校验当前主体、空间、绑定与预算使用资格。预算账户须事先由管理入口初始化。
- `expectedVersion` 从会话创建或详情取得。新请求遇到旧版本或活跃调用返回冲突；重放原提交时须保留原版本、文本及全部生成／容量／超时选择。
- 服务端分配 USER 消息 ID、ExecutionContext、发布引用及绝对 deadline；仅构造单条 USER 文本，不接收客户端身份、角色、历史内容或模型凭据。
- `maxInputTokens` 是输入容量上限。输出预留等于 `generationOptions.maxOutputTokens`；二者之和不得超过实际授权绑定的上下文窗口。输入 UTF-8 字节上限和输出 Token 上限仍由现有 Service 校验。当前 `utf8-estimate-v1` 为保守容量估算，不是计费用量。
- 输入上限、输出上限分别不能超过绑定窗口，合计超出窗口时明确返回 HTTP 400／`205010`（AI_CONTEXT_CAPACITY_EXCEEDED）；
  合计容量合法但输出超过服务器 `arte.ai-new.limits.max-output-tokens` 时返回 HTTP 400／`205004`
  （AI_EXECUTION_LIMIT_EXCEEDED）。 本地 profile 的窗口为 65536、输出上限为 4096，可使用输入 32768／输出
  512。不能把输入、输出都填成窗口值；这表示需要两倍容量。 拒绝发生在受理前，不创建 Invocation、Turn、快照或派发消息，不调用模型、不预留预算。
- `timeoutSeconds` 是整数秒，范围为 1 秒到服务器配置 `maximumTimeout`，不静默截断。相对超时也写入 `ExecutionOptions.requestedTimeout`，同键重放不会因重新分配绝对期限而冲突，也不能延长原调用。
- 尝试次数由服务端 `arte.ai-new-execution.retry.max-attempts` 固定（默认 3，包含首次），工具步骤和并发固定为
  0，输出字节上限来自服务端配置；仅已证明未执行的瞬时失败自动重试。

## 响应及执行

成功返回 **HTTP 202**，响应外层沿用 `ResultContext` 的 `success`、`code`、`desc` 和 `data`。`data` 结构为：

```json
{
  "invocationId": "<已耐久受理的调用 ID>",
  "conversationId": "<会话 ID>",
  "kind": "INVOCATION",
  "acceptedAt": "2026-10-07T08:00:00Z"
}
```

回执只在 Invocation、Turn、会话版本和 DISPATCH Outbox 的受理事务成功后返回。返回 202 不表示模型已完成或预算已结算；Controller 不调用 Gateway、不主动派发、不等待模型结果。执行需要另外启用生成与执行配置，以及 Worker 自动轮询或显式手动消费。

当前 `AcceptedExecution` 不包含轮次 ID；响应不伪造轮次 ID 或当前会话版本。可通过现有 `queryTurnsOfConversation` 查询轮次的 `invocationIds` 与本次调用对应。页面通过 [执行查询接口](INVOCATION_HTTP_API.md) 的 `invocationId` 查询进度、回答和已持久化事件；状态响应也包含受理关联的轮次 ID。

同键同内容返回原受理回执及原受理时间；新操作须使用新幂等键。不要因网络超时自动生成新键重发。

| HTTP 状态 | 含义 |
|---|---|
| 202 | 新提交或原提交重放已受理 |
| 400 | 请求缺失／非法、超限、配置不可用、预算未初始化等 |
| 401 | 未登录或认证无效 |
| 403 | 当前身份缺少所需权限，或空间未授权 |
| 404 | 会话不存在或不属于当前主体 |
| 409 | 幂等键内容冲突、会话版本冲突或会话忙 |
| 500 | 基础设施错误 |

预算不足是在 Worker 预留时确定的执行失败，不能将 202 解释为余额足够。客户端仅提交本轮文本，服务端按会话版本加载最近最多十轮的完整成功回复；历史和本轮文本共同占用输入额度。
参数及业务拒绝使用 WARN 日志记录 method、path、HTTP 状态、业务 code 和异常类型；不记录请求正文、认证头或异常原文。

页面可通过 [预算查询接口](BUDGET_HTTP_API.md) 展示账户限额、held、charged 和 available。正余额不代替 Worker
的原子预留；结果已生成也不代表预算已完成结算。

## 验证

`ChatHttpIntegrationTest` 通过真实 MVC 参数绑定、隔离 H2 MySQL 模式和现有授权／服务／存储组件验证受理事务、相对超时重放、同键内容冲突、并发重复提交、会话版本与活跃调用冲突、跨主体／空间／预算隔离、缺少权限、容量与输出限制及异步身份捕获。测试不访问当前数据库、不调用外部模型。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=ChatHttpIntegrationTest,ConversationHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 重新生成最新一轮

```http
POST /ai-new/chat/regenerate
Idempotency-Key: <本次重新生成的新键>
Content-Type: application/json
```

```json
{
  "scope": {"tenantId": "example-tenant", "workspaceId": "example-workspace"},
  "originalInvocationId": "<最新一轮的来源调用 ID>",
  "expectedConversationVersion": 3,
  "timeoutSeconds": 60
}
```

返回 HTTP 202／ResultContext，data 为 `{"executionId":"<新调用 ID>","kind":"INVOCATION","acceptedAt":"..."}`。
源调用须归属当前主体及最新 Turn，并已进入已知终态，或是具有耐久取消标记的用户停止生成 UNKNOWN。 普通 UNKNOWN
先通过 [费用及执行人工核对](BUDGET_HTTP_API.md) 收敛。活跃调用、旧轮次、旧会话版本、跨主体请求会明确拒绝。
受理事务在会话锁下再次验证条件，不能靠先查后写绕过门闩。

新调用复用来源调用保存的实际输入快照、模型及能力固定版本、生成参数、预算账户和发布引用；仍重新校验当前权限、配置、容量及预算资格。
不向原上下文追加来源回复，不重读最新历史替代原输入，不覆盖原 Invocation，也不自动重发原调用。 输入快照分配新 ID
和有效期；新的截止时间属于新的独立执行。每次主动重新生成使用新键，网络重试必须保持原来源、版本、超时及键。
同键重放在会话版本及门闩检查之前返回原回执，后续消息不会改变重放语义。

同一个 Turn 的 invocationIds 追加新候选（最多 256 个，达到上限仍可重放已有操作），不新增或重写用户问题。受理时保留原答案选择；新候选完整成功后才选择它，失败／停止时保留此前完整答案。
选择成功候选同时推进会话版本，防止并发提交使用切换答案前的历史。会话版本、Turn.sequence 和 Turn.version 各自独立： 新问题才增加
sequence；重新生成受理与成功答案选择会推进会话版本。历史分页及最近十轮选择以实际 Turn 数量为准。

新调用通过相同 Dispatcher/SSE/取消链路运行，独立预留与结算费用；旧调用待对账金额不会转移或清零。
页面只在最新轮次显示“重新生成”，可查看各候选回复，标出用于后续聊天的完整答案。
`RegenerationReconciliationIntegrationTest` 验证独立调用、双实例重放、来源隔离、上下文固定、停止后的原答案保留、费用隔离及超过十轮的序号／分页。
