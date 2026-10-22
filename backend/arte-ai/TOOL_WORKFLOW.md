# 工作流消费 TOOL 契约（S6-01）

S6-01 提供受信 Java 宿主可调用的 `WorkflowToolExecution`，用于将一个工作流逻辑步骤关联到现有 TOOL Invocation，
并在调度恢复时读取同一调用。当前没有工作流引擎、画布、DSL、节点调度器或工作流 HTTP 接口；页面不能据此创建可视化流程。

## 1. 身份与保存责任

工作流宿主必须在首次提交工具前，耐久保存 `ToolWorkflowStep`：

| 字段                      | 约束                                                                            |
|---------------------------|---------------------------------------------------------------------------------|
| `key.workflowRunId`       | 工作流运行实例 ID，由受信宿主管理                                               |
| `key.nodeId`              | 该工作流中的稳定节点 ID                                                         |
| `key.operationId`         | 一次逻辑操作的稳定 ID；调度重试、接管、租约续期沿用原值                         |
| `input`                   | 原 `ToolAdmissionInput`，包括绑定、参数、请求限制与可选收费账户；受理后不得改写 |
| `key.invocationId(owner)` | 首次外发前即可计算并保存的 Invocation ID，收到受理回执后核对一致                |

归属包含当前可信上下文的 tenant／workspace／subject。v1 身份算法使用有格式标记的 UTF-8 长度前缀 SHA-256， 避免分隔符拼接歧义；同一
owner 和逻辑步骤总是得到同一 Invocation ID 与受理幂等键。 绑定、参数、deadline、trace、Worker 和调度 attempt
不参与身份生成，不能通过修改参数或绑定获得另一次执行。 该算法是耐久协议，升级须兼容既有身份，不能直接换算法后重放旧步骤。

循环的不同轮次、明确发起的新业务操作使用各自的 `operationId`，也须先保存。恢复、超时、UNKNOWN、读取失败或用户刷新 都不是分配新
operationId 的理由。UNKNOWN 写入必须先按原调用完成核对；本契约没有自动重做、补偿或“失败后换键再试”的逻辑。

## 2. 提交和恢复顺序

1. 工作流宿主在自己的检查点中保存上述不可变步骤和计算出的 Invocation ID，再调用 `submit(step, current)`。
2. `current` 来自当前真实身份和工作空间授权，并带有本次入口的绝对期限。普通请求 executionId 和幂等键可随调度刷新，
   消费服务使用步骤派生的固定值受理；新调用保留当前 trace，重放沿用原调用的 trace、版本、配置、参数、策略及期限。
3. `submit` 复用 `ToolAdmissionService`，经当前工具／资源授权及同库事务，提交原 Invocation、快照、事件和必要的 DISPATCH。 返回
   `AcceptedExecution` 只表示受理成功。审批工具仍先进入 `WAITING_APPROVAL`，不能绕过确认或提前创建 Attempt。
4. 工作流保存回执。如受理已提交但响应或检查点更新丢失，使用原步骤重放 `submit`，仍返回原 Invocation。 消费服务重建不依赖 JVM
   缓存，也不要求工作流检查点与 TOOL 数据库做分布式事务。
5. 恢复优先调用 `snapshot(key, currentReadContext)`，读取原状态、结果和预算状态。该方法只查询，不受理、不派发、不调用工具。 它复用
   `ExecutionEventService.snapshots`，复核当前 READ、完整 owner、工具历史读取资格及权威 ResultRef；撤权或不可见返回
   `AI_NOT_FOUND`。
6. 工作流根据权威状态推进自己的检查点。取消或丢失此次订阅不等于取消 TOOL；需要停止时沿用现有 Invocation 控制／审批接口。

| 查询事实                                             | 消费方行为                                                             |
|------------------------------------------------------|------------------------------------------------------------------------|
| ACCEPTED／WAITING_APPROVAL／QUEUED／RUNNING 等非终态 | 保存原 ID 并等待；按现有审批／事件／查询机制继续，不能新建逻辑操作     |
| SUCCEEDED                                            | 消费原 `InvocationResult.Tool`；重复查询不会重新调用                   |
| FAILED／TIMED_OUT／CANCELLED／INTERRUPTED            | 保留原状态和错误事实，由工作流政策决定中止或人工处置；本服务不自动重提 |
| UNKNOWN                                              | 保留不确定性和副作用事实，阻止依赖其成功的步骤；仅可查询、核对原调用   |
| 不存在／不可见／读取异常／结果不可读取               | 显式失败；不得将错误解释为“尚未执行”并自动换键提交                     |

首次调用确定未受理或提交响应不确定时，宿主可以用 **原步骤**再次请求可靠受理；不能将普通查询失败当作创建新操作的依据。
并发同意图提交仍只有一个 Invocation；同 key 改绑定、参数、限制或收费账户返回幂等冲突，不增加派发。 TOOL Store 的事务幂等门控及
Invocation ID 唯一锁覆盖“双方均未读到原快照”的并发窗口，消费者侧不另设 JVM 锁。

## 3. 与现有执行、预算和授权的关系

- 工作流 ID 只负责关联，不提供额外权限。本接口是内部消费边界，不接受浏览器自报 owner、工具版本、价格或执行器，
  不增加管理权限、默认授权或跨主体恢复能力。受信宿主仍须校验工作流自身的访问权、步骤归属和操作身份来源。
- 当前 `parentExecutionId` 的非空路径专用于持久化 ToolChatRun 子执行，并有事务内 Run／步骤／停止门闩校验。 S6-01 的工作流关联通过步骤
  key 与固定 Invocation ID 实现，TOOL 仍按独立调用受理，parent 为 null； 不把 workflowRunId 填入聊天 Run parent。提交上下文若携带
  parent 或根 budgetRef，明确拒绝，不能静默丢弃。
- 免费工具继续 FREE，不创建模型 Token 费用或预算预留；收费工具使用 `input.budgetRef` 指定的现有 TOOL 账户，
  资格、报价、预留、结算与核对均沿用 S5-02。重放不新增收费 Attempt、账单或预算预留。
- 当前没有工作流根费用总额、步骤份额、工作流统一停止传播或调度资格的事务联动；这些需在真正建设工作流运行时后另行设计。
  本接口不会因为携带 workflowRunId 自动消耗聊天 Run 的 C／H 配额，也不会自动合并模型与工具账户。
- 原 Invocation 的 deadline 包含原受理、排队、审批和执行；恢复读取或重放使用新的当前请求期限进行授权及等待， 但不能延长原执行
  deadline。新操作首次受理仍与现有产品及工具上限求交。
- `WorkflowRuntime` 保留现有未实现边界。未来若要建立真正的工作流 parent，必须提供权威根记录和同事务步骤校验， 不能仅放宽
  `ToolAdmissionService` 的公共 parent 限制。

## 4. 装配与验收边界

`WorkflowToolService` 随现有 TOOL 执行装配启用，共用受理、已授权结果查询及 Clock。三开关和宿主端口条件不变； 构造不执行
SQL、不建表、不启动轮询或工作流 Worker，不增加配置项、HTTP 路由、持久化别名或清单版本。

`WorkflowToolIntegrationTest` 使用临时 H2、真实 MyBatis 事务、受理、Gateway、通用 Worker 和已授权结果查询，覆盖：

- 响应丢失、消费服务重建、配置变化、执行中恢复及原期限过后回读完成结果；原 deadline 不被重放延长。
- 同步骤并发提交，以及不同绑定抢占同一逻辑操作时，只生成一条 Invocation／DISPATCH。
- 同操作修改参数、限制、账户或绑定的冲突；明确循环轮次才分配另一个操作身份。
- 待执行、未找到、撤权和跨空间读取均不触发执行；伪 parent、继承根账户及过期入口在写入前拒绝。
- 收费调用重放只有一次预留和账单；带本人确认的模拟写操作进入 UNKNOWN 后，多次恢复仍只有一次业务调用及 Attempt。

本批验证范围及具体计数见 [实施记录 §48](TOOL_IMPLEMENTATION.md#48-s6-01工作流消费契约与集成用例)。 不将这些用例等同于生产
MySQL、多节点接管、真实业务写入、性能压测或工作流检查点引擎验收；S6-02／S6-03 保持独立批次。
