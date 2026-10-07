# 新 AI 消息提交 HTTP 接口

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
- `timeoutSeconds` 是整数秒，范围为 1 秒到服务器配置 `maximumTimeout`，不静默截断。相对超时也写入 `ExecutionOptions.requestedTimeout`，同键重放不会因重新分配绝对期限而冲突，也不能延长原调用。
- 尝试次数固定为 1、工具步骤和并发固定为 0、输出字节上限来自服务端配置。

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

当前 `AcceptedExecution` 不包含轮次 ID；响应不伪造轮次 ID 或当前会话版本。可通过现有 `queryTurnsOfConversation` 查询轮次的 `invocationIds` 与本次调用对应。后续实现 `NewAiInvocationController` 的状态／结果接口后，页面通过 `invocationId` 查询进度和回答。

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

预算不足是在 Worker 预留时确定的执行失败，不能将 202 解释为余额足够。当前接口仅提交无历史的单轮文本，历史上下文、重新生成、编辑重发不属于本次实现。

## 验证

`ChatHttpIntegrationTest` 通过真实 MVC 参数绑定、隔离 H2 MySQL 模式和现有授权／服务／存储组件验证受理事务、相对超时重放、同键内容冲突、并发重复提交、会话版本与活跃调用冲突、跨主体／空间／预算隔离、缺少权限、容量与输出限制及异步身份捕获。测试不访问当前数据库、不调用外部模型。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=ChatHttpIntegrationTest,ConversationHttpIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
