# 最小聊天数据模型

本阶段补齐用户消息和会话历史所需的值契约及独立表，基于 [顶层设计](../../ARTE顶层需求及设计.md) 的会话、上下文和统一执行职责。
不接入旧 AI 实体或表；账号和权限继续由已有身份桥接提供。[最小聊天服务](MINIMUM_CHAT_SERVICE.md) 已实现服务、持久化适配器、显式
JSON 编解码和独立 HTTP 接口；旧数据迁移仍留到后续阶段。

## Java 契约与表

| 值契约                                | 表                             | 拥有的事实                                                                                                  |
|---------------------------------------|--------------------------------|-------------------------------------------------------------------------------------------------------------|
| `Conversation` / `ConversationStatus` | `arte_ai_new_conversation`     | 服务端归属作用域、标题、固定模型绑定、关联资料、会话版本、软删除及时间                                      |
| `Turn` / `TurnKind` / `TurnStatus`    | `arte_ai_new_turn`             | 一次提交的用户文本、生成参数、提交顺序、依据的会话版本、幂等身份、上下文与执行关联、受理前错误、串行占位    |
| `ContextSnapshot`                     | `arte_ai_new_context_snapshot` | 实际发送的消息、固定来源片段、选择的历史版本与执行、模型绑定、输入字节预算、输出 token 预留、摘要和过期时间 |

三个主体使用不可变 `record`，状态和提交种类使用 `enum`。集合做防御性复制，构造时校验结构、字段长度、版本、时间、固定绑定和状态组合。
`ContextHistoryRef` 固定历史提交的 `turnId`、`turnVersion`、`executionId`；`ContextBudget` 分别表达输入字节和输出
token，避免混用单位。
`ContextFragment` 要求引用标识与来源一致，裁剪片段必须说明使用范围。

暂不新增独立消息表：本轮用户消息保存在 Turn，实际组装的历史及输入保存在 ContextSnapshot；助手输出、生成终态、用量、费用与输出事件继续归已有执行存储所有。
查询聊天历史时组合 Turn 与执行结果，不在聊天表复制模型结果或再维护一份生成状态。后续出现消息编辑、分支或独立检索需求，再增加对应模型。
当前使用 app 的 JDBC 适配器，不添加 ORM PO 或服务实现占位；AI 新模块仍只依赖 base 和 JDK。

## 提交生命周期

```text
PREPARING -> READY -> ACCEPTED
     |         |
     +---------+---> REJECTED
```

- `PREPARING`：输入已记录，占住会话串行提交位置，尚无上下文或执行关联。
- `READY`：不可变上下文已确定，尚未可靠关联已受理执行，继续占位。
- `ACCEPTED`：已有上下文及 executionId。模型成功、失败、取消或结果未知，从执行记录查询；这些结果不覆盖提交状态。
- `REJECTED`：已确认未受理，没有 executionId，错误明确为无副作用且结果确定，占位已释放。受理结果未知必须核对，不能伪装为拒绝。

`slotReleasedAt` 独立于提交状态。受理后的占位只能由聊天服务检查权威执行终态后释放，取消请求或本地超时不能作为释放依据。 SQL
保证 PREPARING / READY 不得释放，且同一作用域、同一会话最多有一条未释放记录；执行终态与槽位释放之间的业务判断仍须服务实现。

`sequence` 是提交顺序。重新生成保留原提交，创建 `REGENERATION` 提交，通过 `regeneratesTurnId` 关联同会话记录。
最小执行协调器目前每个执行仅有一次 Attempt，因此重新生成关联新的 executionId 和新的 Attempt，不覆盖此前结果，也不重复业务写操作。
历史组装必须选择要继续使用的生成版本，不能把所有重新生成结果拼接成多轮新问答。重新生成目标的存在性、先后关系及可再生成条件由ChatService
校验，当前只重新生成最后一个用户问题。

## 隔离、一致性和幂等

- `scope_key` 沿用 app 的 `ModelKeys.scope(ExecutionScope)`
  ，与执行和预算使用相同规范。会话保存完整租户、工作空间及主体；适配器负责生成键并校验读回的完整作用域。哈希及外键不替代授权。
- 复合外键限制上下文、轮次和重新生成引用的会话与作用域；上下文引用还必须匹配 Turn 的 `conversationVersion`
  。执行关联也受作用域复合外键约束，同一 executionId 不能归属两个提交。
- 会话 `version` 用于按预期版本执行命令，Turn 的 `conversationVersion` 固定历史选择依据，Turn 自身 `version`
  用于条件更新。JdbcChatStore 在事务内结合会话版本比较、提交序号分配、占位和写入处理竞争，唯一键本身不承担整个流程。
- 幂等唯一身份为 `(scope_key, operation, key)`，普通提交为 `chat.turn.submit`，重新生成为 `chat.turn.regenerate`。重放须比较
  `requestDigest`；规范摘要必须包含会话、预期版本、用户输入、参数及重新生成目标。相同键、不同输入须报冲突。
- 已有模型受理事务与聊天写入不能假定是一个事务。聊天服务先持久化待提交记录，并使用稳定派生的模型幂等键；查询和同键重放核对已有执行并补齐关联，不再次生成。恢复范围见最小聊天服务说明。

## JSON 与上下文边界

Turn 的 `payload_format` 固定为 `arte.chat.turn.v1`；Snapshot 为 `arte.chat.context.v1`。JSON 列仅承载类型化值的持久化表示：

| 列                             | 对应值                                                                         |
|--------------------------------|--------------------------------------------------------------------------------|
| `resource_refs_json`           | `List<ResourceRef>`                                                            |
| `input_json` / `messages_json` | `List<Message>`，最小输入仅允许 USER 文本；准备后的历史可以包含 ASSISTANT 文本 |
| `model_options_json`           | `ModelOptions`                                                                 |
| `fragments_json`               | `List<ContextFragment>`                                                        |
| `history_refs_json`            | `List<ContextHistoryRef>`                                                      |

app 编解码器按显式字段和内容种类处理，例如文本部件使用 `{"type":"text","text":"…"}`；不允许通过任意 Java 类名反序列化。
数据库原生 JSON 检查语法，内容形状、未知类型、长度和各个引用的规则仍须在编解码和领域边界校验。

Snapshot 构造校验格式，不计算真实输入字节或摘要，不验证历史存在性、费用、来源授权或绑定与执行是否一致。 ContextService
只选取经过授权的用户消息和历史，检查历史执行及版本，测量文本 UTF-8 字节，固定绑定，并生成规范上下文摘要。
完整供应商协议正文仍由已有模型网关检查容量并生成外发摘要；上下文摘要与协议正文摘要不同。 过期快照不直接用于新的派发；
`expiresAt` 表达逻辑失效，不自动删除历史、执行或账本。JdbcChatStore 采用快照只新增、不得覆盖的规则。

## 建表与验证

SQL 位于 [arte-ai-new-chat-ddl-mysql.sql](../arte-app/scripts/arte-ai-new-chat-ddl-mysql.sql)。先部署已有新模型表，再执行聊天脚本。
新建 `arte_ai_new_execution` 时，`(execution_id, scope_key)` 复合唯一键在模型 DDL 的表声明内创建；聊天脚本的条件升级块仅为此前已建且缺少该键的执行表补键。
聊天脚本创建上述三张新表，不改旧 AI 表，不自动建会话或填入历史数据。要求支持 CHECK 约束的 MySQL 8.0.16
或更高版本。软删除保留引用链，不配置级联删除。

`ChatContractsTest` 验证不可变性、提交状态、重新生成、受理不确定性和上下文边界。
`ChatSchemaTest` 读取实际脚本，在 H2 MySQL 模式下验证唯一键、复合外键、状态约束、占位释放及会话条件更新。 H2 测试替换 MySQL
存储声明、生成列的 STORED 关键字及索引迁移前置块；不等同于 MySQL 原生部署验证。本阶段没有执行生产数据库脚本。

ConversationService、ContextService、ChatService 及 app
持久化适配器已打通该最小流程，操作方式及边界见 [最小聊天服务](MINIMUM_CHAT_SERVICE.md)。
