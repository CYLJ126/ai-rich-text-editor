# S4-03：Turn、Outbox、查询和历史兼容

日期：2026-10-20。本批完成聊天数据与查询基础，未开放工具聊天提交或自动模型／工具循环。S4-04 仍负责总预算与子账户，S4-05
负责真实子调用、循环及取消／恢复，S4-06 负责页面和重生成意图。

## 1. 回答候选与旧数据

`AnswerCandidate(kind, executionId)` 区分 `INVOCATION` 和 `TOOL_CHAT_RUN`。Turn 的 `candidates` 是完整有序列表，
`selectedCandidate` 是显式选择；无选择时以最新候选为读取目标，再验证其权威状态。

旧 `invocationIds` 仅投影普通 Invocation；选中 Run 时 `selectedInvocationId=null`，Run ID
不进入该列表。写入同时验证两套字段一致，避免新旧读法把根类型混淆。同一字符串可分别代表两种执行类型，比较必须包含 kind。原
Java 构造入口保留，纯文本重新生成追加 Invocation 时保留已有 Run 候选和选择。

Turn 新写入内部 codec v2；v1 读取时明确转换为 INVOCATION。版本必须是整数，v1 不允许夹带新候选字段，v2
必须包含候选列表及选择字段；不能简单改版本号降级。HTTP Turn 投影及前端服务类型同步增加类型引用，页面的完整工具聊天展示尚未接线。

## 2. 受理与完成事务

`ToolChatRunStore.acceptChat(snapshot, turn, expectedConversationVersion, authorization)` 是可信内部端口，不是 HTTP
请求体绑定。调用方准备固定快照、服务端 Turn 和当前授权回调；回调在写事务内复核会话／归档、主体、工具集合及启停资格，不能在事务内访问网络、订阅异步工作或调用模型。此端口仍不创建子
Invocation。

受理将根／工作项、Run→会话／Turn 关联、类型化候选、会话版本与活动门闩、用户消息事实、首 Run 事件和 Outbox 一起提交。owner、提交版本、序号、旧
Turn 不变字段及固定用户输入均校验；同键同义返回原根，跨会话或改变 Turn 的重放冲突，重放仍复核当前资格。内部重试须复用原固定
Turn 意图和身份。

`active_invocation` 兼容保留原列名，保存活动根 ID 摘要，新增 `active_kind` 消除歧义。活动 Run 不会被普通 Invocation
的查询／释放逻辑误处理，原文本受理仍返回 CONVERSATION_BUSY。重命名会话及完成旧候选会保留活动根类型。

关联聊天的 Run 成功推进时，仅允许引用当前 WAITING_MODEL 计划中的真实模型子 Invocation。核验
owner、parentExecutionId、trace、固定模型／输入／生成参数、权限及期限上限、子调用成功、结果引用／所属 Attempt／字节摘要、完整 STOP
和纯文本且无工具提议；不能把 TOOL_CALLS 完成当作最终回答。最终子 ID 写入关联表，不复制正文或制造根 Invocation。

成功选择新 Run，失败／取消等终态保留旧选择；最终关联、Turn、会话版本、门闩释放、目录助手事实、根状态／工作项／命令回执、Run
事件／Outbox 同事务提交。PENDING_RECONCILIATION 等未终态继续占用会话。S4-02 的无聊天关联 `accept`
仍是内部存储基础，不能以此绕过聊天关联和最终结果核验。

## 3. Run 事件与 Outbox

`ToolChatRunEvent` 是独立 v1 根：runId、sequence、状态、检查点、发生时间。受理序号为 1，每次有效推进增加 1，等于该根
version+1。重放命令不重复产生事件；子 Invocation 游标不合并到 Run 游标。事件不包含用户／模型正文、工具参数、绑定配置或授权快照。

新增 Outbox kind=`RUN_EVENT`，与根事件同事务提交，使用既有领取、续租、fencing 和确认协议。`executionId` 是通用指针，`target()`
返回带类型引用；调用 `invocationId()` 读取 RUN_EVENT 会明确拒绝。旧 EVENT／DISPATCH 消费者按 kind 领取，不会领取根通知。

发布语义仍是至少一次，消费者按消息 ID／Run 序号去重，按独立游标补读权威事件。本批提供耐久事件和 Outbox 消费基础、HTTP
分页补读，不启动 Run 通知发布器、Worker 或 SSE；后续接线不能把 Outbox 已确认等同于用户已收到事件，也不能改变已提交事件游标。

## 4. 查询 HTTP

启用 `arte.ai-new.enabled` 和 `arte.ai-new-tool.enabled` 后提供以下 POST；以应用原有 `/arte` 前缀为准。每次通过现有可信上下文解析当前主体与
scope，要求 `ai:read`、`ai:conversation`，复核当前会话、归档及类型化候选关系。

| 路径                                | 请求                                   | 返回 data                                                           |
|-------------------------------------|----------------------------------------|---------------------------------------------------------------------|
| `/ai-new/tool-chat/getRunStatus`    | `{scope, runId}`                       | 状态、version、检查点、计划数、结果可用性、安全错误及时间           |
| `/ai-new/tool-chat/getRunResult`    | `{scope, runId}`                       | `{runId, finalInvocationId, result}`；result 为最终 Generation 结果 |
| `/ai-new/tool-chat/replayRunEvents` | `{scope, runId, afterSequence, limit}` | 独立 Run 事件数组，按 sequence 递增                                 |

`afterSequence` 为排他非负游标，不得超过已提交序号；limit 为 1—100，消费者取本页末序号继续读。请求不接受
owner、执行上下文或工具参数等未知字段。未认证 401、无权限 403、不可见根／会话 404，未有最终成功回答为既有
AI_RESULT_NOT_AVAILABLE／409；参数及游标错误 400。复用现有错误处理与 Locale 包装，不另设数字错误码。

状态不返回固定输入、owner、配置、预算账户或授权快照；最终结果不包含本轮工具过程。独立查询服务不依赖 ES 或搜索
PIT；内容可见性装配也独立于搜索开关。现有通用 Invocation 查询若读取最终模型子调用，仍核验其父 Run
的会话归属；中间模型／工具不会通过聊天搜索正文入口公开。

## 5. 历史、目录和搜索

历史加载器分别解析两种候选。选中 Run 时核验会话／Turn 关联、根完整成功、最终子 Invocation 和真实结果，只将原 USER 与最终
ASSISTANT 文本带入后续上下文。未解决的活动根拒绝加载，已知失败／取消跳过；中间提议、工具参数和工具结果不跨轮重放。

目录沿用原用户消息预览／计数，重新生成不重复记录用户事实。Run 助手消息时间表示最终回答对聊天可见的提交时间，采用根
updatedAt；不冒称模型首字时间。离线目录回填同样识别两类候选，Run 只恢复最终助手事实。

搜索沿用实际最终子 Invocation 的源单元和结果字节；索引中的 invocationId 始终是真实模型子 ID。通过父 Run 定位
Turn，候选序号基于完整类型列表。命中回查补充 `candidate={kind:TOOL_CHAT_RUN,executionId:runId}`，原 Invocation 候选仍返回
INVOCATION 类型。前端不能把模型子 ID 当作 Run 候选 ID。

只在根最终成功并固定最终子关联后生成助手索引；中间模型提议、工具正文、失败根及部分回答不作为 Run
的最终答案。既有纯文本历史／部分结果搜索规则保留。搜索仍是异步轮扫同步，不承诺根完成后立即可搜；过期索引在返回正文前核验当前归属、Turn、结果和归档。回查按批读取
Run 与关联，避免逐命中重复查询。未变更 ES 映射或增加新源类别。

## 6. 数据库交付及验证边界

新装使用 [完整基线](scripts/arte-ai-new-ddl-mysql.sql)。已有 S4-02
环境停写、备份后执行 [S4-03 增量](scripts/arte-ai-tool-chat-history-migration.sql)：新增活动类型列、Run 聊天关联及事件两张表；原活动
Invocation 默认标记 INVOCATION。旧表／快照不做破坏性重写。

新增列被纯文本存储共同使用，不能只靠关闭 TOOL 开关跳过 DDL。全体新 AI 节点协调升级后恢复写入；v2 Turn
写入后旧节点不能解码，不支持旧新版本混跑或直接降级。运行时不建表、不回填；本批未连接配置业务库、执行部署 DDL 或重启应用。

测试与当前基线失败、未运行项见 [实施记录 §34](TOOL_IMPLEMENTATION.md#34-s4-03turnoutbox查询和历史兼容2026-10-20)。H2
契约、MockMvc 和固定回环模型响应不代表生产 MySQL、多节点、真实性能或实际工具循环验收。
