# arte-base

依据 [ARTE 顶层需求及设计](../../ARTE顶层需求及设计.md) 的「顶层接口 §1」声明公共层契约。

顶层 Java 类型已声明；身份、作用域、资源／来源引用、幂等、执行上下文、错误、受理及事件的核心值契约已实现结构校验和必要的值语义。
具体范围、必填／缺省约定、迁移边界及测试见 [核心值契约说明](CORE_VALUE_CONTRACTS.md)。公共服务、端口及其他值类型继续按阶段实现。

授权与外发端口已定义 evaluate 和操作边界的强制检查方法，具体请求、决策、失败处理及接入责任见
[授权与资料外发契约](AUTHORIZATION_AND_EGRESS.md)。当前尚未接入真实身份、权限库及网络出口提供者。

| Java 类型          | 使用原则                                                                     |
|--------------------|------------------------------------------------------------------------------|
| `interface`        | 可替换的网关、运行时、策略、基础设施或领域扩展端口                           |
| 普通 `class`       | 平台拥有的应用服务与流程协调；通过组合、依赖和委托协作                       |
| `record`           | 请求、结果、引用、配置及记录的值快照，不直接充当可变持久化实体               |
| `enum`             | 本模块拥有的固定状态或种类；跨领域可扩展标识保留字符串或版本引用             |
| `sealed interface` | 已知且互斥的数据变体，不作为所有能力的执行父接口                             |
| `abstract class`   | 仅在明确存在共享状态、模板流程或公共行为时引入；当前阶段没有该依据，暂不声明 |

`api` 放调用入口，`spi` 放提供者端口，`model` 放数据与状态。授权及外发操作签名已定义，其他操作按后续场景细化；不添加无行为的
Impl 或
Abstract 占位类。

已实现核心 record 的可空性和字段结构校验，ExecutionContext 的授权范围使用不可修改的防御性副本； 其余值类型仍保留声明。record
自带的浅不可变性不代表泛型负载已经深度不可变。 涉及生命周期的 record 是某时点的快照，持久化实体、序列化、Schema
内容校验及事务处理由后续实现层单独设计。

公共层不依赖 AI、文章、供应商 SDK、Spring、数据库或 Redis 客户端。租户、主体、资源、执行、来源与变更信封归公共层；模型参数、会话状态、文章内容和领域补丁归各自模块。

| 类型                                                                                       | Java 声明   | 包（com.arte.base 下） | 职责               |
|--------------------------------------------------------------------------------------------|-------------|------------------------|--------------------|
| [AuthorizationService](src/main/java/com/arte/base/api/security/AuthorizationService.java) | `interface` | `api.security`         | 主体与资源动作授权 |
| [EgressPolicy](src/main/java/com/arte/base/api/security/EgressPolicy.java)                 | `interface` | `api.security`         | 资料外发策略       |
| [AdmissionController](src/main/java/com/arte/base/api/admission/AdmissionController.java)  | `interface` | `api.admission`        | 通用执行准入       |
| [LeaseCoordinator](src/main/java/com/arte/base/spi/coordination/LeaseCoordinator.java)     | `interface` | `spi.coordination`     | 执行归属与租约协调 |
| [ReliableMessageBus](src/main/java/com/arte/base/spi/messaging/ReliableMessageBus.java)    | `interface` | `spi.messaging`        | 可靠消息传递       |
| [OutboxStore](src/main/java/com/arte/base/spi/messaging/OutboxStore.java)                  | `interface` | `spi.messaging`        | 事务消息存储       |
| [CacheStore](src/main/java/com/arte/base/spi/cache/CacheStore.java)                        | `interface` | `spi.cache`            | 可重建热点缓存     |
| [NotificationBus](src/main/java/com/arte/base/spi/messaging/NotificationBus.java)          | `interface` | `spi.messaging`        | 在线通知与唤醒     |
| [ArtifactStore](src/main/java/com/arte/base/spi/artifact/ArtifactStore.java)               | `interface` | `spi.artifact`         | 通用文件产物存储   |
| [AuditSink](src/main/java/com/arte/base/spi/observability/AuditSink.java)                  | `interface` | `spi.observability`    | 审计记录接收       |
| [Telemetry](src/main/java/com/arte/base/spi/observability/Telemetry.java)                  | `interface` | `spi.observability`    | 指标与追踪         |
| [JobScheduler](src/main/java/com/arte/base/api/job/JobScheduler.java)                      | `interface` | `api.job`              | 通用后台工作调度   |
| [ChangeSetService](src/main/java/com/arte/base/api/change/ChangeSetService.java)           | `class`     | `api.change`           | 通用变更提案与路由 |
| [ChangeSetHandler](src/main/java/com/arte/base/spi/change/ChangeSetHandler.java)           | `interface` | `spi.change`           | 领域变更处理扩展   |

`ChangeSetHandler` 对应设计中注册的领域处理器，由目标领域或组合模块提供。公共路由不内置文章规则；未注册时明确不支持。Job
的领域处理器接入方式在后续签名设计时确定。

下一步接入已验证身份、个人 Tenant／Workspace 映射及真实资源／外发策略提供者，再按审计、产物、准入、异步与事件、任务、变更路由推进。领域应用幂等、版本校验及事务仍由领域保证。

## 数据与状态声明

依据顶层接口 §1.2、设计 §3 及附录 B，使用以下 Java 类型。服务端构建的执行上下文不接受客户端自报主体；公共层不拥有 AI 状态，AI
不拥有公共 Job 和目标领域的正式保存状态。

| 类型                                                                                             | Java 声明 | 包（com.arte.base 下） | 职责                                                                                |
|--------------------------------------------------------------------------------------------------|-----------|------------------------|-------------------------------------------------------------------------------------|
| [PrincipalType](src/main/java/com/arte/base/model/identity/PrincipalType.java)                   | `enum`    | `model.identity`       | 主体种类；认证及主体有效性由服务端验证。                                            |
| [PrincipalRef](src/main/java/com/arte/base/model/identity/PrincipalRef.java)                     | `record`  | `model.identity`       | 经验证主体的引用，不承载凭据。                                                      |
| [ExecutionScope](src/main/java/com/arte/base/model/identity/ExecutionScope.java)                 | `record`  | `model.identity`       | 租户隔离、工作空间及执行主体范围。                                                  |
| [ResourceRef](src/main/java/com/arte/base/model/resource/ResourceRef.java)                       | `record`  | `model.resource`       | 资源、正式版本或草稿及范围的通用引用；范围结构由资源领域解释。                      |
| [SourceRef](src/main/java/com/arte/base/model/resource/SourceRef.java)                           | `record`  | `model.resource`       | 可定位的来源引用；查看和重用时重新授权。                                            |
| [SchemaRef](src/main/java/com/arte/base/model/schema/SchemaRef.java)                             | `record`  | `model.schema`         | 可版本化的 Schema 引用，不限定具体 Schema 实现库。                                  |
| [SecretRef](src/main/java/com/arte/base/model/security/SecretRef.java)                           | `record`  | `model.security`       | 加密凭据的引用；不携带明文凭据，实际解析归连接及基础设施实现。                      |
| [IdempotencyKey](src/main/java/com/arte/base/model/execution/IdempotencyKey.java)                | `record`  | `model.execution`      | 按主体、操作和请求摘要限定的幂等标识；执行与业务应用分别管理。                      |
| [CancellationRef](src/main/java/com/arte/base/model/execution/CancellationRef.java)              | `record`  | `model.execution`      | 取消信号的权威执行引用；不以实例内存标志作为分布式取消事实。                        |
| [ExecutionContext](src/main/java/com/arte/base/model/execution/ExecutionContext.java)            | `record`  | `model.execution`      | 服务端构建的执行上下文；预算及发布使用公共引用，不导入 AI 专有类型。                |
| [AcceptedExecution](src/main/java/com/arte/base/model/execution/AcceptedExecution.java)          | `record`  | `model.execution`      | 可靠受理回执；执行种类和初始状态由所属模块定义，受理不等于生成、保存或应用完成。    |
| [ExecutionEvent&lt;T&gt;](src/main/java/com/arte/base/model/execution/ExecutionEvent.java)       | `record`  | `model.execution`      | 带单调序号的通用执行事件；类型化负载由所属模块扩展，重放不重新执行。                |
| [SideEffectStatus](src/main/java/com/arte/base/model/execution/SideEffectStatus.java)            | `enum`    | `model.execution`      | 失败或中断后副作用是否已发生的事实状态，与能力声明的风险等级分开。                  |
| [ResultCertainty](src/main/java/com/arte/base/model/execution/ResultCertainty.java)              | `enum`    | `model.execution`      | 操作结果的确定性；结果未知时不能盲目重试。                                          |
| [CancellationStatus](src/main/java/com/arte/base/model/execution/CancellationStatus.java)        | `enum`    | `model.execution`      | 取消请求与取消完成分别表达；已完成表示执行已结束，未承诺被取消。                    |
| [ExecutionError](src/main/java/com/arte/base/model/execution/ExecutionError.java)                | `record`  | `model.execution`      | 稳定错误信封；错误码和失败阶段由模块扩展，不包含凭据或敏感全文。                    |
| [ChangeStatus](src/main/java/com/arte/base/model/change/ChangeStatus.java)                       | `enum`    | `model.change`         | 变更提案、草稿采纳与正式保存的状态；待保存不等于已应用。                            |
| [ChangeSet&lt;P&gt;](src/main/java/com/arte/base/model/change/ChangeSet.java)                    | `record`  | `model.change`         | 跨领域变更信封；P 为目标领域的具体补丁，Schema 与规则由该领域提供。                 |
| [ApplyRequest&lt;P&gt;](src/main/java/com/arte/base/model/change/ApplyRequest.java)              | `record`  | `model.change`         | 独立于生成请求的应用命令；领域事务负责 applicationKey 去重及 expectedVersion 校验。 |
| [VersionConflict](src/main/java/com/arte/base/model/change/VersionConflict.java)                 | `record`  | `model.change`         | 条件应用失败时的版本冲突信息，不返回未授权内容。                                    |
| [ChangeApplicationResult](src/main/java/com/arte/base/model/change/ChangeApplicationResult.java) | `record`  | `model.change`         | 变更应用结果；正式保存以返回的新资源版本为准，冲突信息与失败状态分别表达。          |
| [ApplicationRecord](src/main/java/com/arte/base/model/change/ApplicationRecord.java)             | `record`  | `model.change`         | 正式应用的记录快照；与目标领域新版本及应用去重在同一事务中提交。                    |
| [JobStatus](src/main/java/com/arte/base/model/job/JobStatus.java)                                | `enum`    | `model.job`            | 通用 Job 的权威调度状态，独立于 AI Invocation 和编排 Run。                          |
| [Job](src/main/java/com/arte/base/model/job/Job.java)                                            | `record`  | `model.job`            | 可恢复工作项的状态快照；实际领域处理器按 handlerKey 接入，不承载 AI 专有状态。      |
| [Lease](src/main/java/com/arte/base/model/coordination/Lease.java)                               | `record`  | `model.coordination`   | 执行归属、失效时间及 fencing token 的租约快照，不替代领域事务。                     |
| [ArtifactStatus](src/main/java/com/arte/base/model/artifact/ArtifactStatus.java)                 | `enum`    | `model.artifact`       | 产物存储生命周期；可用不等于已插入文章或已获访问权限。                              |
| [ArtifactRef](src/main/java/com/arte/base/model/artifact/ArtifactRef.java)                       | `record`  | `model.artifact`       | 通用产物引用及完整性元数据；字节存储与业务附件关系分开。                            |
| [Artifact](src/main/java/com/arte/base/model/artifact/Artifact.java)                             | `record`  | `model.artifact`       | 产物元数据快照；文件字节由 ArtifactStore 管理。                                     |

`ChangeSet<P>`／`ApplyRequest<P>` 的补丁类型由目标领域决定，`ExecutionEvent<T>`
的负载由事件所属模块决定。取消使用权威执行引用，幂等键携带操作及输入摘要。公共受理回执的执行种类、初始状态及错误码保留扩展空间，不在
base 枚举中引入 AI 类型。
