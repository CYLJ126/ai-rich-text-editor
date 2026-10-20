# 新 AI 前端接口

> 2026-10-17 TOOL 设计关联：[AI TOOL 需求及设计](../../../../ARTE%20AI%20TOOL%20需求及设计.md)规划在本目录按现有
> request、ResultContext、Scope、幂等和取消规范增加工具服务。当前没有 TOOL 管理／调用或 ToolChatRun 导出函数；单次工具拟扩展通用
> Invocation 查询，工具聊天使用 kind=RUN 和独立游标。实现时须扩展状态与结果判别、带类型的回答候选、工作空间隔离及过程展示，不能把
> runId 传给 Invocation 查询或直接拼接子调用游标。本次没有新增前端代码。
> TOOL 评审修订 V0.2：计划增加默认绑定准备、调用预览摘要及 UNKNOWN
> 核对进度，普通页隐藏版本／重试等高级字段。工具聊天的三种重新生成意图、历史资料时刻和实际审批到期必须明确展示；轮询采用有界退避，空间切换／失权停止刷新。上述均为未来
> TOOL API，不是本目录当前导出函数。

S2-07 已新增 `tool.ts`，覆盖目录筛选／详情、默认配置准备、本人配置读取／保存／启停／升级预览及历史。页面直接从
`@/services/arte-ai/tool` 导入工具专属类型， 复用原 request／ResultContext／Scope，不修改纯文本聊天 API。精确配置使用
configurationJson 原文及工具 JSON 编辑器，写命令仍发送标准 JSON configuration，版本始终为字符串。 页面 `/AI/Tools`
只管理工具配置；不提供 invokeTool、预算、审批或
ToolChatRun。当前范围与验证见 [S2-07 实施记录](../../../../backend/arte-ai/TOOL_IMPLEMENTATION.md#15-s2-07工具目录与绑定页面)。
上方 2026-10-17 说明为规划时状态，当前实现以本段及实施记录为准。

S3 提供 `searchConversations`、`closeSearch`、`queryTurnsAround`，S4 已接入页面双入口，后端部署配置现默认开启搜索查询和后台同步。搜索返回
`body.data.records` 与不透明游标，不使用普通页码分页包装；不带 cursor 会新建 PIT，进行中的同词请求由搜索界面合并。
`closeSearch` 允许成功包装中的空 data。历史定位独立于原搜索 PIT，助手正文通过 `getInvocationSnapshot` 辅助函数调用既有批量
`getInvocationSnapshots` HTTP。详见[第三阶段](../../../../backend/arte-ai/CONVERSATION_SEARCH_S3_ACCEPTANCE.md)
和[第四阶段验收](../../pages/AI/ChatNew/CONVERSATION_SEARCH_S4_ACCEPTANCE.md)。

本目录手写维护，通过 `@umijs/max` 的 request 复用登录 Token 和当前语言，调用 `/arte/ai-new/` 的 JSON POST 接口。页面位于
`src/pages/AI/ChatNew`（旧测试入口仍位于 `src/pages/AI/Chat`）；SSE 单独使用 fetch，显式携带相同的 Bearer Token 和当前语言。

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

| 功能         | 导出函数                                                                         | 响应位置                        |
|--------------|----------------------------------------------------------------------------------|---------------------------------|
| 发现聊天配置 | `discoverChatOptions({scope}, options?)`                                         | `body.data.options`             |
| 创建会话     | `createConversation(data, idempotencyKey, options?)`                             | `body.data`                     |
| 会话列表     | `listConversations(data, options?)`                                              | `body.records`                  |
| 会话详情     | `getConversation(data, options?)`                                                | `body.data`                     |
| 轮次历史     | `queryTurnsOfConversation(data, options?)`                                       | `body.records`                  |
| 发送文本     | `turnsForChat(data, idempotencyKey, options?)`                                   | `body.data`，HTTP 202           |
| 执行状态     | `getInvocationStatus(data, options?)`                                            | `body.data`                     |
| 执行结果     | `getInvocationResult(data, options?)`                                            | `body.data.result.value`        |
| 批量快照     | `getInvocationSnapshots({scope, invocationIds, knownResultVersions?}, options?)` | `body.data` 数组，最多 20 个 ID |
| 合并单条快照 | `getInvocationSnapshot({scope, invocationId, knownResultVersion?}, options?)`    | 单个快照；同轮调度自动组成批次  |
| 事件重放     | `invocationEvent(data, options?)`                                                | `body.data.events`              |
| 预算快照     | `getBudget(data, options?)`                                                      | `body.data`                     |

`options` 只接受 `{ signal?: AbortSignal }`，供页面切换会话/卸载时取消请求。封装不自动重试、不自动生成幂等键、不启动轮询、不弹窗。使用
`skipErrorHandler` 交给页面处理错误，同时全局响应拦截器尊重此标记，避免重复提示。

只读请求按当前 Token、完整 scope 和参数合并进行中的请求，不缓存已完成请求的授权或余额。
每个使用者独立取消，最后一个退出才中止传输；切换账号后的迟到响应不返回给原使用者。写请求保持各自幂等协议。 快照包含
`invocationId/status/result/missing`；归属不符与不存在均为 `missing=true`，不泄露其他用户的调用。 只有全部使用者都持有同版本结果时才发送
`knownResultVersions` 省略字节；状态和本次预算预留仍重新读取。

- 创建及发送使用必填 `Idempotency-Key`；读取接口不需要。范围 `scope` 由后端验证，不能自行声明用户或权限。
- 历史查询及发送的 `expectedVersion` 从会话详情读取；409/`205030` 表示版本冲突，需要页面刷新详情后决定下一步。
- 发送需要已发布的 capability/binding 固定版本、budgetRef 及 Token/超时限制；固定引用、兼容预算、参数限制和推荐值从
  discoverChatOptions 获取，不能假设默认值存在。
- 结果 409/`205041` 表示尚未可用；事件 410/`205037` 表示游标过期。空事件页不表示结束，`UNKNOWN` 不表示成功，也不允许自动重发。
- 文本结果先检查 `kind === 'GENERATION'`，再读取 `result.value.outputs[*].content`，通过 `'text' in part` 取文本。未知用量保持
  null，预算金额保持十进制字符串。

验证命令：`npm test -- src/services/arte-ai`。测试使用模拟响应及现有全局拦截器，不调用真实模型或扣除预算。

## 执行通知

`watchInvocation({scope, invocationId, afterSequence}, onEvent, {signal, onConnected?, onText?})` 返回持续读取连接的
Promise。 通知类型为 `{executionId, sequence, kind, text?}`，OUTPUT 的 text 为已落库的文本增量，旧服务端只发指针时缺省。
成功处理后推进游标；封装按 sequence 去重，不自动查询结果、不自动重连，也不提交消息。 聊天 Hook 的回调只登记刷新事实，不等待
HTTP；连续通知合并，失败事实保留并低频重试。 状态、结果、历史、余额读取由刷新协调器完成，避免 HTTP 查询阻塞文字解码。
`onText` 接收独立的 `text-delta`：`{executionId, attemptId, offset, text}`，offset 按 UTF-16 计数。 每帧最多 256 个 UTF-16
字符，没有 SSE id，不推进耐久游标。它是模型预览，可能重复或缺失；页面按偏移合并并由 OUTPUT／终态结果修复。 支持分块 UTF-8、多行
data、CR/LF、注释心跳、安全 error 帧；单帧最多 8 Mi 个解码字符，以兼容历史大批次的 JSON 转义， 单批 text 和页面累计增量均限
1,000,000 个 UTF-16 字符；45 秒无数据中止旧连接。页面负责低频 HTTP
恢复、重连、401/403 停止与 410 权威快照恢复。

预算变更通知 BUDGET_CHANGED 只携带指针。通过 getInvocationStatus.budgetState 查询本次预留处理状态，通过
getBudget 查询金额。 TERMINAL 不保证预算已结算；RESERVED 时继续观察，SETTLED/RELEASED/PENDING_RECONCILIATION 后读取账本快照。
PENDING_RECONCILIATION 表示金额尚待核对，不能视为零费用。结算阶段可在 Invocation.version 不变时更新。 多实例广播由后端
Redis 实现，前端继续使用相同 SSE/HTTP API 与 sequence 游标。

停止请求正文使用 `{scope, invocationId}`，必须携带稳定 `Idempotency-Key`。202 的 `ACCEPTED` 表示命令已保存； 200 的
`ALREADY_TERMINAL` 表示无需停止。封装不取消 SSE、不修改本地执行终态，不自动重试或重新发送聊天。 运行中的停止可能保留 UNKNOWN
和待对账预算，继续以状态接口和终态结果为准。

S4-03：Turn 响应新增 `candidates`／`selectedCandidate`；`invocationIds` 只包含普通 Invocation。搜索命中的 `candidate` 标识回答根，
`invocationId` 对 Run 最终回答仍是真实模型子调用 ID。S4-05 后端已提供工具聊天提交／停止与真实循环，接口及开关见
`backend/arte-ai/TOOL_CHAT_EXECUTION.md`；页面与前端服务封装留 S4-06，不可把 Run ID 交给 Invocation
查询。只读 Run API 见 [后端说明](../../../../backend/arte-ai/TOOL_CHAT_HISTORY.md)。
