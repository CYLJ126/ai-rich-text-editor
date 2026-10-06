# 最小 AI 数据契约 v1

日期：2026-10-04。适用于 `com.arte.ainew` 的首批数据声明；存储和预算方法及 MyBatis 事务实现见 [分布式存储与预算契约](DISTRIBUTED_PERSISTENCE.md)，调用方法声明见 [AI 调用接口契约](INVOCATION_API_CONTRACTS.md)，模型调用及 HTTP API 尚未接入。

## 1. 所有权、关系与实现范围

```text
Conversation（归属、配置、版本、资料关联）
  └─ Turn（固定用户输入、历史路径、回答候选）
       └─ Invocation（一次逻辑能力操作的权威状态）
            ├─ Attempt（实际尝试、归属、发送／执行事实）
            ├─ ContextSnapshot（实际输入与来源）
            ├─ ExecutionEvent（保存后的批次与状态）
            └─ ResultRef → ModelResult／其他专有结果
```

Conversation 不保存执行状态的另一份权威副本；它通过执行引用展示状态。一个 Turn 可以关联多个 Invocation，例如用户重新生成回答。一次 Invocation 可以有多个 Attempt，例如允许的自动重试。

动作、向量或后台能力调用可以没有 Conversation／Turn。会话不持有另一份执行终态；关联资料不授予资源访问权。Conversation／Turn 的 version 与 Invocation／Attempt 的 version 分别用于所属对象的条件更新，不混用。

当前代码包含不可变值对象、结构校验、状态关系谓词，以及独立存储／预算方法契约和 MyBatis 事务实现；第 1～3 步受理协调、固定配置及字节存储已接入，派发与其余策略后续实现。公共身份、引用和执行信封暂放在 `ainew.common`，未来整体迁移公共契约模块。复用现有 `ExecutionContext` 和运行上下文分离，不改变旧 `ai`、公共认证链路或文章实现。

## 2. 提交、重试、重新生成与编辑重发

| 操作 | 身份与处理 |
|---|---|
| 相同作用域幂等键、相同规范化请求摘要 | 返回原 Invocation／AcceptedExecution，不再次派发 |
| 相同作用域幂等键、不同规范化请求摘要 | 明确拒绝，不覆盖原输入 |
| 系统允许的自动重试 | 原 Invocation 下新增 Attempt；序号增加，仍受期限、总尝试数和预算限制 |
| 用户主动重新生成 | 新 Invocation、新幂等键；保留原 Turn，通过 replacesInvocationId 关联原调用；原结果仍可查询 |
| 用户编辑输入后重发 | 新 Turn、新 Invocation；supersedesTurnId 关联原轮次，parentTurnId 明确新历史路径；不修改已执行的用户输入 |
| 重放事件／查询结果 | 只读取记录，不建立 Invocation 或 Attempt |

当前存储的幂等作用域包括 tenant、workspace、subject 和稳定 capability.id；目标会话／动作身份进入请求摘要，同键换目标明确冲突。服务端计算请求摘要，覆盖固定能力／绑定版本、实际业务输入、资料／快照内容、执行选项、关联轮次等有效语义。排除新分配 executionId、traceId、授权解析时刻等临时数据；deadline 应使用可复现的请求期限策略参与摘要，不能把每次接收时重新计算的时间直接造成同键冲突。具体规范化编码仍由准入实现固定；当前耐久去重记录不自动过期，后续归档须保持约定的保留窗口。

已知终态不返回 RUNNING。UNKNOWN 停止新执行，可以依据同一次远端操作的核对证据收敛到 SUCCEEDED／FAILED／CANCELLED，不能重新排队或盲目派发。此处 terminal 表示已停止执行，UNKNOWN 的结果事实仍可经核对收敛，并追加更高序号的终态事件；其他确定终态不能被重新生成覆盖。状态图谓词只是必要条件，存储仍须检查版本、执行归属和核对证据，并记录审计。Attempt 超时或中断不表示未执行，也不证明没有费用；错误 certainty=UNKNOWN 时必须使用 UNKNOWN 状态，不能以普通失败隐藏不确定性。

## 3. 首批类型与字段约束

| 类型 | 关键字段及边界 |
|---|---|
| Conversation | owner、title、version、固定 chatProfile、resources、ACTIVE／DELETED、创建／更新时间 |
| Turn | 固定 userMessage、parentTurnId、supersedesTurnId、invocationIds、selectedInvocationId、version；选中项必须属于候选列表，实际归属和成功／部分结果展示规则由会话服务校验 |
| InvocationRequest | capability、binding 的固定定义引用；Kind、受约束 CapabilityInput；ExecutionOptions、服务端 ExecutionContext；必须有幂等键且选项不得延长期限 |
| Invocation | requestDigest、可选会话关联／contextSnapshotId／原调用引用、state、version、activeAttemptId、结果／错误、时间；完整成功要求非 partial 结果且无错误 |
| Attempt | attemptNumber、workerId、fencingToken、租约期限、version、状态、Dispatch、远端请求标识、预留引用、Usage、ExecutionError；UNKNOWN 必须保留可能已执行事实 |
| ContextRequest | 显式消息、历史选择、memoryIds、resources、target、ContextBudget；history=null／空记忆列表明确表示不加载 |
| ContextSnapshot | 实际历史轮次、固定模型绑定、最终规范化消息、来源片段、裁剪事实、Token 容量与计数依据、SHA-256 摘要、保留期限 |
| GenerationRequest | messages、GenerationOptions、允许 ToolDefinition、TextOutput／StructuredOutput；只有描述，不持有执行回调 |
| ModelResult | 实际模型身份、助手输出、FinishReason、complete、结构化结果、Usage、来源；complete 不代替平台状态或领域保存 |
| Usage | UNKNOWN／ESTIMATED／PROVIDER_REPORTED；单项 null 为未知，0 为已知零；报告值也允许部分项缺失 |
| BudgetReservation | 绑定 Invocation／Attempt、Money、固定费率版本、状态、version、有效期；记录存在不表示事务已提交 |
| BudgetSettlement | settlementKey、reservationId、Usage、charge、状态及时间；未知费用仅能待对账，RELEASED 要求已知零费用 |

身份、归属、错误事实及编码信封由服务端生成。外部 DTO 不直接反序列化 ExecutionContext、owner、租约、状态或已受理记录；客户端可以表达待验证的资源选择和执行要求。模型只能提出内容或工具请求，不能生成可信主体、工具权限、来源证明和发布版本。

nullable 仅用于注释说明的“未选择”“不存在”“尚未知”字段；必须存在的集合使用空不可变集合，集合及元素不允许 null。对象使用 record／封闭的嵌套结果族；List／Map／Set 防御性复制，向量使用不可变 Float 列表，避免数组通过访问器被修改。StructuredValue 是有界不可变 JSON 值树，禁止通过 Object 字段装入 SDK 实例、线程、连接或可变 POJO。

## 4. 上下文、生成与输出身份

ContextRequest.HistorySelection 是选择条件；快照中的 history.turnIds 必须是实际选入路径上的轮次，不保留“最近 N 轮”的模糊条件。ContextSnapshot.messages 已包含实际编入的资料，fragments 用于来源映射和范围展示，不能再拼接一次。maxInputTokens 加输出／工具预留不得超过固定模型窗口；计数同时记录 estimatedTokens 和 tokenizerVersion，不能把字符长度当作 Token。

Fragment.originalCharacters 及 Truncation 包含实际裁剪说明；全文未带入、被完全省略或不支持的内容均须准确呈现。服务端核对 citationId、固定资源版本、范围及摘录摘要，不能把模型产生的任意链接当作经过校验的来源。资源授权和目的地外发策略在读取／发送边界重新检查，过期快照禁止直接作为新输入；expiresAt 本身不删除文件或数据库字节。

GenerationEvent 只表达适配后的 TextDelta／ToolCallDelta／UsageReported／Finished，没有平台耐久游标。工具参数增量可能是不完整 JSON，必须聚合为 ToolCall 后再解析、Schema 校验和重新授权。助手消息可只含工具提议，工具响应需有 toolCallId；跨消息提议／响应配对由组装器校验。

ExecutionPayload.OutputBatch 将增量组成有界批次；ExecutionEvent 的 sequence 在单一 executionId 内跨 Attempt 单调递增，Cursor.afterSequence 是排他位置，0 表示从首个保留事件开始。事件类型与负载必须匹配，负载类型／版本在白名单编码注册表中解析；通知只能唤醒订阅者，遗漏通知从 EventStore 回读。游标不含凭据，订阅与结果查询重新授权。

ModelResult 的 LENGTH、CONTENT_FILTER、OTHER 不可声明 complete；完整输出可能仍未通过业务 Schema 校验。终态事件及结果引用在权威状态提交后发布；分离的事件库用 Outbox 补齐，不靠 Flux.onComplete 推断成功。用户取消观看不会生成取消任务命令；ControlReceipt.ACCEPTED 只是控制请求受理，取消与成功竞态由执行权威判断。

## 5. 其他能力输入与结果

| 能力 | 请求 | 结果与限制 |
|---|---|---|
| 向量 | EmbeddingRequest.Input 的稳定 inputId＋文本 | EmbeddingResult 固定模型、spaceId、维度、有限数值向量与 Usage；validateAgainst 检查数量／顺序／身份。spaceId 的可信生成由模型、预处理及索引版本规则落实 |
| 工具 | ToolInvocation 的 callId、固定工具定义、StructuredValue.ObjectValue 参数 | ToolResult 保留结构化输出、来源、产物、实际副作用与错误；UNKNOWN 不作为安全重试证据 |
| 媒体 | MediaRequest 的类型、提示和产物参考 | MediaResult.Completed 必须有本系统产物；Pending 保留远端任务。未启用媒体执行时不注册可执行能力 |
| 远程应用 | START／CONTINUE、结构化输入、可选隔离会话 | RemoteApplicationResult.Completed／Pending；task 与 session 的 owner 和 connection 一致，执行前仍与当前主体／实际绑定核对 |

ArtifactRef 只能代表已经校验并转存到本系统的固定产物；供应商临时 URL 的下载、校验、有效期和转存归连接／产物实现处理。引用不授予下载权。RemoteTaskRef 的远端状态和控制能力不等于本地 Invocation 状态，不承诺平台可以逐步拦截远端内部执行。

## 6. 校验、版本及序列化

构造器执行 SDK 无关的结构校验：非空、范围、唯一标识、必要字段、有限向量、时间顺序与状态内的一致性。当前绝对结构上限为标识 256 字符、文本合计 1,000,000 个 Java 字符、常规列表 256 项、消息部件／产物 64 项、工具 128 项、结构化树深度 32／节点 10,000。执行选项上限为尝试 10 次（含首次）、输出 32 MiB、工具步骤 100／并发 16；默认聊天应使用更低配额并可禁用工具。字符上限不等于字节、Token 或费用；同时限制请求总字节与运行缓冲。

数据布局初版为 v1。持久化／传输编码信封必须携带受信类型别名和 schemaVersion，引用的定义 version 与记录的乐观锁 version 是不同概念。ExecutionEvent、ResultRef 已显式包含相关编码版本；其他根记录由对应编码信封携带。以下约定区分内部耐久快照与尚待实现的 HTTP 传输编码；内部白名单实现见 [存储契约](DISTRIBUTED_PERSISTENCE.md)：

- 使用受信别名（例如 `ai.invocation`、`ai.output-batch`），白名单映射到固定具体类型；不启用按任意 Java 类名的默认多态反序列化。
- HTTP 传输中 StructuredValue 映射为标准 JSON 值；内部快照使用显式类型包装。消息部件、输出格式、增量事件和异步结果使用显式 discriminator。CapabilityInput 解码依据已登记能力／Schema，不能相信客户端提供的类名。
- Instant 使用 UTC ISO-8601；Money.amount 使用十进制字符串，currency 使用 ISO 币种代码；摘要使用小写 SHA-256 十六进制。大序号在浏览器协议中使用十进制字符串，避免 JavaScript 数字精度损失。
- 增加可选字段须明确默认含义；移除／改义字段或增加不兼容枚举值升级 Schema，并为旧任务提供转换或旧版本 Worker。未知版本明确拒绝，不用默认值冒充兼容。
- 不序列化 ExecutionRuntimeContext、进程内取消信号、Authentication、凭据或 SDK 对象；引用再使用时重新授权。含全文的请求／结果不能直接写入普通日志，record.toString() 也不例外。

Java Serializable 用于测试数据不持有运行对象；测试仅对自身创建的可信对象做往返。生产存储与网络采用上述版本化显式编码，不使用 Java 原生反序列化接收不可信字节。已新增内部耐久快照的白名单 JSON 编码器；StructuredValue 内部使用显式类型包装，HTTP 标准 JSON DTO 仍由传输适配器落实。

## 7. 存储已落实的保证与应用边界

- 原子受理：当前授权、能力与绑定解析、Schema／角色／模态校验、摘要计算、会话版本及活跃轮次串行校验、幂等唯一约束，以及 Invocation／Turn 关联与可恢复派发依据提交。
- 执行与恢复：租约和 fencing、Attempt 唯一序号、发出前持久化事实、显式重试策略、未知结果核对、期限与取消命令传播。构造记录不代表这些保证已经实现。
- 上下文与结果：匹配快照的绑定／所有权／输入摘要，检查容量及当前外发许可；验证输出 Schema、工具配对、来源、完整性及产物。
- 预算：预留／结算原子防重、币种与费率一致性、重复结算请求摘要、余额和待对账。预留过期、任务取消或费用缺失都不自动证明可以释放。
- 输出：分配连续持久化序号、保存后发布、历史到实时无遗漏切换、游标过期、慢消费者有界缓冲、终态与结果引用同事务提交。

模型路由、上下文选择和重试保留独立版本化策略边界；当前先固定默认绑定、显式资料选择与保守重试规则。策略方法签名随应用接口设计落实。Agent 决策／结果组合、Workflow／Run、复杂记忆及完整评估数据后置，不为高级场景提前建立持久化引擎。

原子受理、版本／租约／fencing、事件防重／序号／过期游标、终态／结果引用／Outbox 同库提交、预算预留／结算已由 [MyBatis 存储实现](DISTRIBUTED_PERSISTENCE.md) 落实。当前固定授权、文本 Schema、规范化摘要、无历史文本组装、快照及结果字节存储和可靠受理已由 [受理应用层](ADMISSION_IMPLEMENTATION.md) 实现；模型派发、供应商输出校验、轮询发布与订阅后续接入。

## 8. 验证

从 backend 运行存储、数据契约及现有执行上下文测试：

```bash
mvn -o -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=com.arte.ainew.persistence.*Test,com.arte.ainew.contract.*Test,com.arte.ainew.context.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

覆盖不可变嵌套集合、有界结构、能力输入匹配、幂等必需字段、期限、重新生成关系、未知执行与费用、实际上下文、工具提议、向量对应、远端身份及可信序列化往返。现有上下文测试继续验证授权、跨线程身份隔离、Worker 重建与取消／期限。新增存储测试验证 H2 MySQL 模式下的事务回滚、跨实例竞争与恢复；仍须验证实际 MySQL 部署与供应商协议兼容性。
