# 新 AI 前端接口（第一步）

本目录手写维护，通过 `@umijs/max` 的 request 复用登录 Token 和当前语言，调用 `/arte/ai-new/` 的 9 个 JSON POST 接口。页面位于
`src/pages/AI/Chat`；SSE 单独使用 fetch，显式携带相同的 Bearer Token 和当前语言。

统一从 `@/services/arte-ai` 导入。成功返回 `{ httpStatus, body }`：普通接口使用 `body.data`，分页接口使用 `body.records`、
`body.current`、`body.size`、`body.total`；`body.code`、`body.desc` 同样保留。HTTP 202 仅表示消息已受理。

```ts
import { AiApiError, createConversation, listConversations } from '@/services/arte-ai';

const scope = { tenantId: '实际租户 ID', workspaceId: '实际空间 ID' };
// 页面应在一次创建开始时生成并保存；重试同一请求时复用此键及原始参数。
const key = crypto.randomUUID();
try {
  const created = await createConversation({ scope, title: '测试会话' }, key);
  const conversationId = created.body.data.conversationId;
  const page = await listConversations({ scope, page: { current: 1, size: 20 } });
  console.log(conversationId, page.body.records);
} catch (error) {
  if (error instanceof AiApiError) {
    console.log(error.httpStatus, error.code, error.desc, error.body);
  } else {
    // 网络错误/取消请求保持原错误；页面按需处理。
    throw error;
  }
}
```

| 功能     | 导出函数                                             | 响应位置                 |
|----------|------------------------------------------------------|--------------------------|
| 创建会话 | `createConversation(data, idempotencyKey, options?)` | `body.data`              |
| 会话列表 | `listConversations(data, options?)`                  | `body.records`           |
| 会话详情 | `getConversation(data, options?)`                    | `body.data`              |
| 轮次历史 | `queryTurnsOfConversation(data, options?)`           | `body.records`           |
| 发送文本 | `turnsForChat(data, idempotencyKey, options?)`       | `body.data`，HTTP 202    |
| 执行状态 | `getInvocationStatus(data, options?)`                | `body.data`              |
| 执行结果 | `getInvocationResult(data, options?)`                | `body.data.result.value` |
| 事件重放 | `invocationEvent(data, options?)`                    | `body.data.events`       |
| 预算快照 | `getBudget(data, options?)`                          | `body.data`              |

`options` 只接受 `{ signal?: AbortSignal }`，供页面切换会话/卸载时取消请求。封装不自动重试、不自动生成幂等键、不启动轮询、不弹窗。使用
`skipErrorHandler` 交给页面处理错误，同时全局响应拦截器尊重此标记，避免重复提示。

- 创建及发送使用必填 `Idempotency-Key`；读取接口不需要。范围 `scope` 由后端验证，不能自行声明用户或权限。
- 历史查询及发送的 `expectedVersion` 从会话详情读取；409/`205030` 表示版本冲突，需要页面刷新详情后决定下一步。
- 发送需要已发布的 capability/binding 固定版本、budgetRef 及 Token/超时限制；当前后端没有配置发现接口，后续页面需提供这些测试配置，不能假设默认值存在。
- 结果 409/`205041` 表示尚未可用；事件 410/`205037` 表示游标过期。空事件页不表示结束，`UNKNOWN` 不表示成功，也不允许自动重发。
- 文本结果先检查 `kind === 'GENERATION'`，再读取 `result.value.outputs[*].content`，通过 `'text' in part` 取文本。未知用量保持
  null，预算金额保持十进制字符串。

验证命令：`npm test -- src/services/arte-ai`。测试使用模拟响应及现有全局拦截器，不调用真实模型或扣除预算。

## 执行通知

`watchInvocation({scope, invocationId, afterSequence}, onEvent, {signal, onConnected?})` 返回持续读取连接的 Promise。
通知类型为 `{executionId, sequence, kind}`，成功处理后推进游标；封装按 sequence 去重，不自动查询结果、不自动重连，也不提交消息。
支持分块 UTF-8、多行 data、CR/LF、注释心跳、安全 error 帧；单帧上限 64 KiB，45 秒无数据中止旧连接。 页面负责低频 HTTP
恢复、重连、401/403 停止与 410 权威快照恢复。

预算变更通知 BUDGET_CHANGED 与其他 SSE 通知一样只携带指针。通过 getInvocationStatus.budgetState 查询本次预留处理状态，通过
getBudget 查询金额。 TERMINAL 不保证预算已结算；RESERVED 时继续观察，SETTLED/RELEASED/PENDING_RECONCILIATION 后读取账本快照。
PENDING_RECONCILIATION 表示金额尚待核对，不能视为零费用。结算阶段可在 Invocation.version 不变时更新。 多实例广播由后端
Redis 实现，前端继续使用相同 SSE/HTTP API 与 sequence 游标。
