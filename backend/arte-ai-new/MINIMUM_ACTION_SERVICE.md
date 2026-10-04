# 最小独立 AI 动作

第一批实现内置已发布文本动作 `rewrite/v1`：传入原文和补充要求，提交改写、查询、取消、重新生成及读取流式结果。动作无需创建
Conversation 或 Turn，也不读取旧 AI 表。第二步已接入 [文章资料、草稿、选区及参考上下文](MINIMUM_RESOURCE_CONTEXT.md)；修改提案、编辑器采纳和正式文章保存属于后续步骤。

## 输入与执行

`AiActionService` 只依赖新 AI 和
base。服务端固定动作指令、能力／绑定版本。客户端只能提交文本、补充要求和受限模型选项，提示词角色、连接、凭据与执行身份由服务端控制。首期输入是用户明确传入的文本，不将它标记为经过验证的文章来源。改写指令要求保留原文事实、含义，只输出改写正文；输出质量仍需用户核对。

`AiActionExecution` 是不可变输入快照，包含作用域、动作版本、模型版本、最终消息、模型／执行选项、再生成来源、提交幂等键、摘要及登记时间。首期快照直接存储在动作表，不复用要求存在聊天会话的
ContextSnapshot 表。资料改写还附带独立 ResourceContextSnapshot，保留实际来源、范围、容量和摘要；旧纯文本输入仍支持。

输入同时核对 UTF-8 字节数和 Token 预算，包含系统指令与要求；不静默截断原文。使用与聊天相同的保守 UTF-8 Token
估算策略，估算不代表供应商实际计费。读取、重放、重新生成及控制前验证固定输入摘要。

动作输入先在独立数据库事务内登记，随后获取正文对应的外发同意并提交统一 InvocationCoordinator。模型执行、预算、耐久
Worker、事件及部分正文复用现有链路。动作侧不持有事务等待供应商，不增加隐式模型重试。

## 幂等、恢复与权限

- 动作提交键在租户／工作空间／主体范围内唯一；同键不同原文、要求、选项、动作／模型版本或再生成来源返回 409。
- 模型提交键是服务端派生的 `action:{actionExecutionId}`；直接模型入口拒绝该命名空间，防止伪造动作受理关联。
- 同键并发请求共用一个动作和模型执行。动作与模型不使用跨领域事务；模型已受理但响应丢失时，通过派生键查询恢复，不重复派发。
- 响应结构为 `{action, execution}`。查询中 `execution=null` 表示输入已登记但尚未可靠受理；重放原请求、原键并重新确认外发可继续。只有提交成功返回
  202 时才表示模型可靠受理，生成终态查看 `execution.status`。
- 重新生成必须显式发起并使用新键，创建新动作及执行，保留原指令、原文和要求，不把旧答案带入输入；可调整模型选项。原执行未完成或为
  OUTCOME_UNKNOWN 时拒绝重新生成。再次重放相同再生成请求只返回既有执行。
- 查询及事件读取使用当前权限，主体隔离；读取时不要求重新同意外发。每批事件重新授权，撤销权限会停止输出。提交和重新生成必须确认外发，并复用现有应用／连接策略及预算授权。
- 取消请求与实际执行终态分开。排队取消可在未派发前结束；运行中取消关闭 I/O 后，已派发结果不确定的调用保留部分正文与待对账费用，不呈现成功结果。

## HTTP API

启用后使用 `/api/ai-new/actions`，沿用当前登录认证。tenantId／workspaceId 接受现有授权范围，主体和默认模型由服务器确定。

| 操作     | 路径                               | 输入                                                                                                             |
|----------|------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 改写     | POST `/rewrite/executions`         | JSON：tenantId、workspaceId、text、可选 requirements／options、externalTransferConfirmed；请求头 Idempotency-Key |
| 查询     | GET `/executions/{id}`             | tenantId、workspaceId                                                                                            |
| 重新生成 | POST `/executions/{id}/regenerate` | JSON：tenantId、workspaceId、可选 options、externalTransferConfirmed；请求头新的 Idempotency-Key                 |
| 取消     | POST `/executions/{id}/cancel`     | tenantId、workspaceId                                                                                            |
| 流式输出 | GET `/executions/{id}/events`      | tenantId、workspaceId、可选 exclusive after（默认 -1）或 Last-Event-ID                                           |

改写与重新生成返回 202、动作结果和 Location。这里路径中的 `{id}` 是 `action.actionExecutionId`，模型 ID 是
`execution.executionId`；两者不同。未知动作返回 400，资源不存在返回 404，授权或外发确认拒绝返回 403，幂等冲突返回
409，原执行不可重新生成时返回 429。

提交示例：POST `/api/ai-new/actions/rewrite/executions`，`Idempotency-Key: rewrite-demo-1`。

```json
{
  "tenantId": "personal-1",
  "workspaceId": "workspace-1",
  "text": "这段文字需要改写，请保留原本的事实和意思。",
  "requirements": "表达更简洁，语气自然",
  "options": {"maxOutputTokens": 512},
  "externalTransferConfirmed": true
}
```

SSE 与聊天使用同一发送机制，每帧为 `event:model`，含
executionId、sequence、status、textDelta、result、error。用模型执行身份和单调游标去重，刷新后可从部分正文的 partialSequence
补读。连接断开不取消模型，不重发问题；完整结果与成功终态以账本查询为准。

动作订阅有独立容量池（默认 64）和发送线程（最多 4），订阅默认最多 2 分钟，十秒心跳，数据库通知唤醒并定期补读。容量配置复用现有
stream-max-clients／stream-timeout；同时启用动作和聊天时两者各受该容量约束，模型预算及执行队列仍然共享。

## 部署

1. 确认现有新模型、身份、预算及耐久工作队列已经部署，包括模型的流式增量与部分正文表。
2. 执行 [动作增量 DDL](../arte-app/scripts/arte-ai-new-action-ddl-mysql.sql)。新安装也执行同一脚本；该表不依赖聊天表。
   已有实例部署第二步版本还须先执行 [资料上下文升级 DDL](../arte-app/scripts/arte-ai-new-resource-context-ddl-mysql.sql)，具体升级顺序见资料上下文说明。
3. 在 `backend/profile/app.properties` 设置 `arte.ai-new.action.enabled=true`，重新构建并启动。默认
   false；开启后缺少动作表或基础设施会启动失败。

动作开关独立于 `arte.ai-new.chat.enabled`。复用已配置的默认模型、最大输入字节和输出 Token，以及
`chat.context-window-tokens`、`chat.context-safety-tokens`、`chat.streaming-enabled`
等预算／流式设置；即使聊天关闭，这些配置仍生效。未自动执行生产数据库变更或真实模型调用。

## 验证

`NewAiActionIntegrationTest` 使用真实动作
DDL、身份／授权、预算、耐久队列与流式协议替身，验证固定输入、补充要求、跨实例同键竞争、响应丢失恢复、部分正文补读、重新生成、未知结果、排队／运行中取消、Token
容量、篡改保护、主体隔离、权限撤销、订阅上限及 HTTP／SSE 行为。

`NewAiActionConfigurationTest` 验证默认关闭、缺失基础设施／Schema 时失败，以及聊天关闭且没有聊天表时的接线；现有 profile
测试验证聊天和动作同时启用。数据库验证使用 H2 的 MySQL 模式，不证明生产 MySQL 部署语法或行为。

本批相关后端检查共 129 项通过，其中新增动作集成测试 18 项、配置测试 4 项。使用项目要求的 JDK 21；本机 Mockito 测试通过显式
javaagent 启动，模型协议测试的 HTTP 服务仅监听本机。
