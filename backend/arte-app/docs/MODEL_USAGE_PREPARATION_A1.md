# A1：模型使用准备与受信预算初始化

日期：2026-11-02。

后续交付：[A2 模型到聊天闭环](MODEL_USAGE_CHAT_A2.md) 已于 2026-11-03 接入前端；下文保留 A1 当批范围与验证记录。

## 范围与额度规则

本批只交付后端。模型发布继续独立成功；准备操作不发布模型、不修改聊天配置、不调用供应商，不改变原 Invocation、ExecutionOwner
或结算。

唯一额度来源为 DATABASE 控制面的已批准规则：

- 已准备空间使用其当前保存的 `WorkspaceConfiguration.preset`，按精确 `rate.id + rate.version`
  匹配。新默认模板不覆盖已有空间；没有匹配规则返回待配置，不能借另一模型、另一费率或另一空间的额度补齐。
- 未准备空间采用当前已发布默认模板，且操作者必须具有目标空间 `workspace:policy:write`。仅具有模型使用权的普通成员不能开通空间
  AI。模板关闭、缺规则或新账户额度为零时不写入。
- 预算固定属于实际执行的 `tenantId + workspaceId + subjectId`。租户模型的管理空间及凭据责任主体不能替代执行空间及调用用户。
- 当前用户已有兼容账户时直接复用，不提高额度、不更新账户版本，不改预留、已计费用或余额。缺账户且有批准规则时，使用现有确定性预算引用，只初始化所选精确费率。
- 模型发布、使用授权、凭据用途和预算批准分别核验。客户端不能提交主体、金额、币种、费率、预算引用、连接地址或凭据。

现有空间规则及平台默认模板仍由原平台／空间配置流程维护。本批不增加配额审批、共享预算池、新表、DDL、业务库回填或新的开关。

## API

两个 POST 端点沿用 `/arte` 前缀、认证会话、公共错误、严格字段验证及服务端 traceId；随现有个人模型发布开关启用：

| 路径                                              | 请求                         | data                           |
|---------------------------------------------------|------------------------------|--------------------------------|
| `/ai-new/model-resource/getModelUsagePreparation` | `scope, resourceId, binding` | 当前 `ModelUsagePreparation`   |
| `/ai-new/model-resource/prepareModelUsage`        | 同上；必需 `Idempotency-Key` | 成功命令回执，value 为准备结果 |

`scope` 是实际执行空间；`binding` 必须为精确绑定，不解析 latest。请求不含预算金额及主体。接口既支持已发布个人／租户模型，也支持已经显式切入公共
ACL 的数据库旧模型；不自动迁移旧目录。

返回字段：scope、resource、binding、rate、state、reason、budgetRefs、approvedLimit。
`approvedLimit` 是规则批准额度，不是余额；READY 只表示当前准备条件具备，不保证余额足够、Worker 在线或供应商成功。
不返回内部发布证据、连接、凭据或授权代次。

| state／reason                                                       | 含义                                                       |
|---------------------------------------------------------------------|------------------------------------------------------------|
| READY                                                               | 当前模型使用条件及兼容账户已具备                           |
| PREPARABLE／BUDGET_NOT_PREPARED                                     | 有批准规则，可以显式初始化当前用户账户                     |
| PREPARABLE／WORKSPACE_AI_NOT_PREPARED                               | 有账户但空间 AI 尚未准备，有资格者可按模板准备并复用原账户 |
| BLOCKED／BUDGET_RULE_NOT_CONFIGURED                                 | 当前空间规则未批准该精确费率                               |
| BLOCKED／BUDGET_RULE_ZERO                                           | 批准额度为零，不新建账户                                   |
| BLOCKED／WORKSPACE_AI_DISABLED                                      | 当前空间配置或未准备空间的默认模板关闭 AI                  |
| BLOCKED／WORKSPACE_AI_NOT_PREPARED                                  | 空间尚未准备，当前成员没有准备空间的治理资格               |
| BLOCKED／CATALOG_NOT_INITIALIZED                                    | 尚无已发布目录                                             |
| BLOCKED／MODEL_NOT_MIGRATED                                         | 旧模型尚未显式切入公共 ACL                                 |
| BLOCKED／CREDENTIAL_STALE、CREDENTIAL_USE_DENIED、TEMPLATE_STALE 等 | 复用既有个人／租户模型的当前运行条件                       |

未授权的资源／空间返回原公共错误，不借状态查询泄露其他模型。BLOCKED 状态下准备写入返回 ACCESS_DENIED，先查询状态取得修复原因。
权威查询、数据损坏或基础设施故障传播原错误，不伪装为缺预算。已有定义缺失账本、账户归属／精确费率／定义额度不一致，或出现同费率多定义时拒绝初始化，避免清零历史。

## 事务、幂等和恢复

状态查询只取得当前身份及公共治理门控，不创建空间 AI、定义、账户、回执或事件。模型／凭据检查不发送网络请求、不解密密钥。

写入使用原同库命令执行器，在当前主体和租户／空间门控下，重查模型 ACL、范围、精确内容、当前政策、凭据代次及用途，再读取当前批准预算规则。
新空间 AI（适用时）、单费率定义与账户、审计、Outbox 和成功回执同事务提交；任一步失败全部回滚。
账户的现有行锁与原执行／结算写入兼容；准备操作不以读到的旧账户快照覆盖账本。

同键同内容重放返回原 operationId 和历史结果，并重新核验当前模型、凭据、空间与预算资格；撤权或停用后不能用旧成功回执恢复资格。
同键不同模型版本拒绝 IDEMPOTENCY_CONFLICT。不同键／不同请求实例并发准备仍复用唯一账户。
回执是当时的结果，不是后续调用许可；需要当前条件时重新查询状态，执行继续走原受理、派发与外发门控。

A2 再实现准备入口、提示、返回聊天及显式应用配置；本批不增加前端操作。

## 验证

新增专项 17
项全部通过：状态查询无业务写入、同键／异键／重装配重放、同键不同内容拒绝、并发唯一账户、精确费率版本、仅分配所选费率、已有预留／费用／额度／版本原样保留、缺规则／零额度／停用／撤权拒绝、账本缺失和孤立账户拒绝、审计失败整体回滚、严格
MVC，以及租户模型在目标空间按当前消费者准备账户。 另验证新预算被原聊天发现服务选择并用于 ChatService 受理，再次准备不改写账户。

最终定向回归按测试用例去重统计 150 项：149 通过，1 项既有 MySQL 来源约束迁移专项因未配置隔离 MySQL 跳过，无失败／错误。 范围包含
core 公共包边界、AI 定义读取／模型资格／政策门控／装配兼容、app 数据库控制面、个人发布、租户共享、模型 ACL HTTP／运行时、审核方案、JSON
回执及公共错误契约。 既有真实 Spring／H2／回环聊天、个人／租户密钥及 PROVIDER_BILL 结算用例均通过；回环监听用例在允许本机端口监听的环境复验通过。
编译使用 JDK 21，沙箱内 Mockito 采用显式 javaagent，避免运行时自附加限制。

复现 A1 专项（backend 目录）：

```shell
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -o -pl arte-app -am \
  -Dtest=ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

需要限制环境中的显式代理时，通过 `-DargLine=-javaagent:<本地 mockito-core-5.23.0.jar 的绝对路径>` 启动测试代理。

未运行真实 MySQL 锁竞争或多节点验收，未连接业务库、执行业务 DDL、调用真实供应商或重启服务；无前端改动，A2 的界面和浏览器验收未在本批实施。
`git diff --check` 通过。
