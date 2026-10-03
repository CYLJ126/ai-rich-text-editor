# 最小会话与聊天服务

已打通“创建会话 → 提交用户文本 → 组装历史 → 模型受理 → 查询结果与历史 → 继续交流”，以及重新生成、取消、命名和软删除。
沿用 [聊天数据模型](MINIMUM_CHAT_MODEL.md) 的三张表和 [最小模型调用](MINIMUM_MODEL_CALL.md) 的执行、预算、授权与外发链路。
新 AI 生产代码仍只依赖 base 和 JDK；JDBC、JSON、Spring 及现有账号接入位于 app，不访问旧 AI 表或使用旧实体。

## 服务与持久化

- `ConversationService`：按租户、工作空间及主体管理会话；创建时固定服务端模型绑定，列表支持标题检索和分页，命名／删除比较预期版本。
- `ChatService`：耐久记录提交、串行推进、固定上下文、使用统一协调入口，查询时组合提交及权威执行结果。提供明确的重新生成和取消操作。
- `ContextService`：只使用用户文本和已授权的成功历史；同一问题采用最后成功的生成结果，保留实际历史版本及执行引用，按文本
  UTF-8 字节裁剪完整问答对。
- `ChatStore` / `JdbcChatStore`：会话锁原子分配顺序、比较版本并占位；提交锁串行处理准备和模型受理。快照写入与 READY
  更新为一个事务，生成结果继续存于模型账本。
- `ChatAccessPolicy` / `ExistingChatAccessPolicy`：复用现有账号、成员、任务、应用及绑定许可。模型结果读取、派发、事件和取消仍走已有模型权限检查。
- `ModelConsentProvider`：app 在用户明确确认后，为服务端准备的实际协议正文和目标生成同意；独立提交后才受理模型，异步线程不接收
  HTTP 对象。

元数据读取不要求当次外发同意；新派发要求 AI 与外发许可以及明确确认。主体从现有认证会话交叉核对，客户端不能提供主体、执行上下文、目的地、凭据、绑定版本或报价。
所有聊天数据查询都按完整作用域过滤；权限撤销后不能依靠旧执行上下文读取结果。

## 版本、重复提交与恢复

创建的会话版本为 1。每次新提交推进一次会话版本，准备上下文固定提交前的版本；命名和删除也推进版本。
后续新命令应读取最新会话版本。相同提交重放保留原来的 `expectedVersion`、文本、参数、重新生成目标及 `Idempotency-Key`
，不重新读取版本并改写请求。 相同幂等键而请求摘要不同返回冲突。会话有未释放提交时，新提交、命名和删除拒绝竞争；已完成执行的占位在查询或新命令前核对并释放。

模型受理键为服务端派生的 `chat:<turnId>`。独立模型 HTTP 入口拒绝该保留前缀。 READY 已提交后才进入模型受理；模型受理和聊天关联分别提交。关联写入失败时保持
READY，查询或同键重放按稳定键核对权威执行并补齐关联，不再次调用模型。 即使快照已过期，也先恢复已存在的受理；过期且未受理的提交拒绝新派发。
准备事务失败留下 PREPARING，不留下孤立快照；同键重放可重新准备。无法确认是否受理时保持待核对状态，不错误标记 REJECTED。

提交驱动的数据库锁仅覆盖准备与异步模型受理，不等待供应商网络结果。不同实例处理同一提交依靠同一数据库锁协调；连接池须容纳聊天、同意及模型的独立事务。
本阶段的恢复由查询和同键重放触发。模型进程失联后的执行恢复仍沿用最小模型链的运维核对机制，不自动重新派发或恢复供应商流。

## 历史与重新生成

历史接口按提交顺序倒序分页，包含普通提交、重新生成、拒绝及未完成提交；返回 `ChatTurnResult(turn, execution)`
，用户输入和模型结果分属各自记录。 构造新上下文时仅使用 SUCCEEDED 的文本结果，跳过失败和结果未知的执行；同一问题的多次生成不会被当成多轮新问答。
最多检查最近 256 条提交并选取配置数量的成功问答对；容量不足时从最旧的完整问答对开始移除，不裁剪当前问题或把部分回答冒充完整回答。
当前文本本身超出容量时记录已确认拒绝；空文本、过大文本及非法参数在输入边界拒绝。

重新生成只允许当前最后一个用户问题，避免隐式创建对话分支；保留原提交及结果，固定原问题的实际上下文，再创建独立提交和执行。
原执行须已结束且结果确定；OUTCOME_UNKNOWN 不允许直接重新生成。使用新的幂等键，并再次明确确认可能产生的新费用与外发。
取消只请求当前模型执行停止，不提前释放提交占位。已派发调用停止后仍可能成为 OUTCOME_UNKNOWN 并保留待对账费用，不宣称远端已取消。

## 启用和数据库

聊天默认关闭，需要已有身份、安全桥接和执行支撑，以及 `arte.ai-new.model.enabled=true`。 按现有部署方式准备安全桥接、执行支撑、新模型及新聊天
DDL；没有自动执行数据库脚本或迁移旧聊天数据。

```yaml
arte:
  ai-new:
    model:
      enabled: true
      # 其余固定模型、连接、凭据引用、预算及许可配置见 MINIMUM_MODEL_CALL.md
    chat:
      enabled: true
      context-max-bytes: 8192
      history-pairs: 32
      snapshot-ttl: PT10M
```

`context-max-bytes` 统计文本 UTF-8 字节，与供应商完整 JSON 正文上限分开；完整正文仍由网关校验。
`history-pairs` 为 0 到 32；快照期限使用 ISO-8601 Duration，必须大于 0 且不超过 1 小时。 输出 token 默认沿用配置的模型上限，客户端可以请求更小值。
当前仍使用单个已配置、固定版本的模型绑定；配置目录、旧版本解析与升级迁移不在此阶段实现，不自动把已有会话切换到新版本。

## HTTP 入口

新聊天入口为 `/AI/Chat`，接入与菜单脚本见 [第一步](../../frontend/docs/ai-new-chat-step1.md)
，消息发送、外发确认及结果观察见 [第二步最小聊天闭环](../../frontend/docs/ai-new-chat-step2.md)。
`GET /api/ai-new/chat/bootstrap` 返回当前已授权配置空间、默认模型及外发展示信息；关闭时可查询开关状态。
页面请求停止、重新生成及异常恢复见 [第三步执行控制与异常处理](../../frontend/docs/ai-new-chat-step3.md)。
阅读、复制、草稿和第一版验收清单见 [第四步使用体验与验收](../../frontend/docs/ai-new-chat-step4.md)。
该初始化接口不登记任务、同意或预算，不自动放行外发，也不返回凭据；模型生成仅由用户明确确认后提交聊天请求触发。

基础路径：`/api/ai-new/conversations`，使用现有登录会话。

| 操作     | 方法与相对路径                     | 输入                                                                                                                          |
|----------|------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| 创建     | POST `/`                           | JSON：tenantId、workspaceId、title；返回 201                                                                                  |
| 列表     | GET `/`                            | tenantId、workspaceId、可选 title、offset、limit                                                                              |
| 查询     | GET `/{id}`                        | tenantId、workspaceId                                                                                                         |
| 命名     | PATCH `/{id}`                      | JSON：tenantId、workspaceId、expectedVersion、title                                                                           |
| 软删除   | DELETE `/{id}`                     | tenantId、workspaceId、expectedVersion                                                                                        |
| 提交     | POST `/{id}/turns`                 | JSON：tenantId、workspaceId、expectedVersion、text、可选 options、externalTransferConfirmed；请求头 Idempotency-Key           |
| 重新生成 | POST `/{id}/regenerate`            | JSON：tenantId、workspaceId、expectedVersion、originalTurnId、可选 options、externalTransferConfirmed；请求头 Idempotency-Key |
| 历史     | GET `/{id}/turns`                  | tenantId、workspaceId、可选 beforeSequence、limit                                                                             |
| 单次提交 | GET `/{id}/turns/{turnId}`         | tenantId、workspaceId                                                                                                         |
| 取消     | POST `/{id}/turns/{turnId}/cancel` | tenantId、workspaceId                                                                                                         |

列表默认 50 条、最多 100 条，offset 上限 10000。历史默认 50 条、最多 100 条，`beforeSequence` 是不包含该序号的向前分页边界。
提交及重新生成返回 202 和组合查询结果；202 表示可靠受理，实际生成状态查看 `execution.status`，不表示生成成功。
拒绝、版本和幂等冲突等错误使用与独立模型入口相同的稳定错误信封。存储或受理结果不明确时可能返回
503，应先查询历史核对，再使用相同请求重放，不能换键盲目重试。

提交示例（会话刚创建、版本为 1）：

```json
{
  "tenantId": "personal-1",
  "workspaceId": "workspace-1",
  "expectedVersion": 1,
  "text": "请解释这段代码的职责",
  "options": {"maxOutputTokens": 512},
  "externalTransferConfirmed": true
}
```

已有模型执行及耐久事件入口继续可用。此阶段没有 SSE、逐 token 输出、文章资料、助手、记忆、工具或旧前端切换。

## 验证

`NewChatIntegrationTest` 在 H2 中使用实际 DDL、现有身份和权限记录、独立模型预算／执行／事件存储，供应商网络以文本测试适配器替代。
覆盖多轮历史、幂等及跨实例竞争、准备事务回滚、受理关联恢复、快照过期及篡改、重新生成、取消、主体隔离、许可撤销、容量裁剪、HTTP 状态和
Spring 事务代理接线。
`NewChatConfigurationTest` 验证默认关闭及缺失基础设施时启动失败；已有数据契约、安全接入和模型调用测试继续作为回归验证。 H2
验证不等同于生产 MySQL 部署验证，没有执行实际数据库变更。
