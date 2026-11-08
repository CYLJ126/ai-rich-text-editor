# 新 AI 预算账户查询 HTTP 接口

> 2026-11-08：[会话异常恢复专项 V1.3](../../ARTE%20AI%20会话异常恢复需求及设计.md) ER-1、ER-2 已实现。
> 合格普通文本本地终态会释放会话占用，原预留、实际账单、执行结束证明及核对权限契约保留。
> 重新生成来源已放宽；历史跳过和页面继续提问仍待 ER-3、ER-4，管理责任核对路径仍待 ER-5。

A1 的模型使用准备接口、批准额度来源和幂等初始化约束见 [模型使用准备](../arte-app/docs/MODEL_USAGE_PREPARATION_A1.md)
。该接口复用当前用户账本，不改变下述执行及结算契约。

> S5-02 增量：工具按次价格、可信费用证据、独立／Run 账户、费用核对及升级要求见 [工具计费说明](TOOL_BILLING.md)
> 。下文保留原批次边界，当前收费能力以该说明为准。

`NewAiBudgetController` 查询当前用户授权范围内的预算账本，沿用新 AI HTTP 接口的 POST、`@Valid`、`Mono<ResultContext<...>>`
和请求 Locale。只需 `arte.ai-new.enabled=true`；查询不依赖生成／执行开关或 Worker，也不调用模型。

## 请求

```http
POST /ai-new/budget/getBudget
Authorization: Bearer <登录 Token>
Content-Type: application/json
Accept-Language: zh-CN
```

部署 context-path 若为 `/arte`，完整请求地址为 `/arte/ai-new/budget/getBudget`。

```json
{
  "scope": {
    "tenantId": "example-tenant",
    "workspaceId": "example-workspace"
  },
  "budgetRef": "example-budget"
}
```

- `scope` 和 `budgetRef` 为必填选择参数，须替换为实际授权配置；标识非空且最多 256 字符。
- MVC 请求线程捕获真实 Authentication，申请 `ai:read`。`BudgetAccountQueryService` 再次验证当前权限、grant
  中的预算使用资格以及预算定义的主体／空间归属，然后读取账本。客户端自报的 owner、权限或金额不参与授权。
- 不需要 `ai:invoke`、`ai:conversation`、`ai:budget:admin` 或 `Idempotency-Key`；预算管理权限本身不授予读取权限。
- 查询期限为 30 秒与服务器 maximumTimeout 中较小值，身份不依赖后台线程的 ThreadLocal。
- 账户须事先通过已有管理入口初始化。查询不创建账户、重置 held／charged、调整限额、预留或结算费用，也不触发模型调用。

## 响应

成功返回 HTTP 200，外层沿用 `ResultContext` 的 `success`、`code`、`desc`、`data`。示例 `data`：

```json
{
  "budgetRef": "example-budget",
  "currency": "CNY",
  "limit": "100",
  "held": "10",
  "charged": "2.5",
  "available": "87.5",
  "rateVersion": {"type": "rate", "id": "default-text", "version": "v1"},
  "version": 3
}
```

费率引用和金额仅为结构示例，不是供应商定价。

| 字段        | 含义                                         |
|-------------|----------------------------------------------|
| budgetRef   | 已授权预算账户引用                           |
| currency    | 所有金额共用的 ISO 币种代码                  |
| limit       | 耐久账本中的账户限额                         |
| held        | 尚未释放的预留，包含已派发且费用待对账的预留 |
| charged     | 已按可信费用事实结算的金额                   |
| available   | `limit - held - charged`，允许为负           |
| rateVersion | 固定费率版本引用                             |
| version     | 本次账户快照版本                             |

四个金额字段均为十进制 **字符串**，避免浏览器将高精度金额转换为浮点数；计算时应使用十进制运算。它们来自同一个权威账户快照，不分别读取或在查询时重新计费。响应不暴露
owner、授权快照或凭据。

未知费用继续占用 held，不用估算费用或零值冒充已知费用；charged 为 0 不代表所有执行都免费。已经发生的实际费用可使 charged 超过
limit，可用余额不裁剪为零。正余额只是读取时的事实，不保证下一次请求足够预留，Worker 仍须按该请求的容量上界原子检查预算。

服务层核对账户引用、归属、限额／币种和费率版本与当前固定预算定义一致；配置冲突时明确拒绝，不以配置覆盖耐久账本。

## 错误

| HTTP 状态 | 含义                                                                                                            |
|-----------|-----------------------------------------------------------------------------------------------------------------|
| 400       | 参数非法；授权账户未初始化（AI_BUDGET_NOT_INITIALIZED）；账本与固定配置冲突（AI_BUDGET_CONFIGURATION_CONFLICT） |
| 401       | 未登录或认证无效                                                                                                |
| 403       | 身份缺少 ai:read、授权已停用，或主体／空间未授权                                                                |
| 404       | 预算未知或不在当前主体可用范围，统一 AI_BUDGET_NOT_AVAILABLE，避免暴露其他用户账户存在性                        |
| 500       | 数据库、编码或其他基础设施错误，不伪装成零余额或未初始化，不暴露原始异常                                        |

## 验证

`BudgetHttpIntegrationTest` 使用真实 MVC 参数绑定、隔离 H2、当前授权和账本组件，验证高精度金额、预留／结算／释放／待对账、负余额、未初始化账户、读取无写入、跨主体／空间／grant
隔离、最小读取权限、配置冲突和安全异常响应。Spring 装配测试验证默认关闭，以及只开启受理时预算查询组件装配不访问数据库。

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am \
  -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  -Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider \
  '-Dtest=BudgetHttpIntegrationTest,InvocationHttpIntegrationTest,ChatHttpIntegrationTest,ConversationHttpIntegrationTest,AdmissionIntegrationTest,ConfigurationAssemblyTest,ExecutionAssemblyTest,MybatisExecutionPersistenceTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试不访问当前业务数据库或真实模型供应商。账户汇总与下述待对账查询、人工核对已实现；充值及调整限额仍由预算控制面处理。

## 待对账查询和人工核对

当前没有接入供应商账单查询适配器。停止、断流、用量缺失等未知费用仍保留预留；由有 `ai:budget:admin`
权限的当前账户归属主体核实实际账单和执行结束事实后处理。 普通用户只有 `ai:read` 时可查看自己获授权预算的待对账记录，不能自报金额结算；不能处理其他主体的预算。
以下接口均受真实身份、租户／空间、当前 grant 和预算归属检查约束。

| POST 路径                             | 所需权限                 | 请求字段（均含 scope）                   |
|---------------------------------------|--------------------------|------------------------------------------|
| /ai-new/budget/pendingReconciliations | ai:read                  | budgetRef、current（≥1）、size（1～100） |
| /ai-new/budget/confirmReconciliation  | ai:read、ai:budget:admin | 见下例；必须有 Idempotency-Key           |
| /ai-new/budget/reconciliationReceipt  | ai:read                  | budgetRef、invocationId、key（原核对键） |

查询返回 ResultContext.data：`{page:{current,size,total,records:[...]},canReconcile:true/false}`。 records 提供调用／会话
ID、调用状态及版本、预留 ID 及版本、预算引用、预留 Money、远端请求 ID 和受理时间；不暴露输入、凭据或授权快照。 只列生成类已终止调用的
RESERVED／PENDING_RECONCILIATION 预留；运行中的预留不允许人工提前结束。 当前沿用 v1 紧凑 JSON
快照及账户索引过滤、数据库计数分页，没有新增数据库表；大规模账单可在后续迁移中增加专用状态索引。

```json
{
  "scope": {"tenantId": "example-tenant", "workspaceId": "example-workspace"},
  "budgetRef": "example-budget",
  "invocationId": "<待核对调用 ID>",
  "invocationVersion": 4,
  "reservationId": "<查询返回的预留 ID>",
  "reservationVersion": 1,
  "actualCharge": "0.123456789012345678",
  "currency": "CNY",
  "evidenceRef": "<供应商账单或人工核对凭据编号>",
  "note": "<金额与执行结束的核对说明>",
  "executionEnded": true
}
```

实际金额必须明确填写非负十进制字符串，最多 20 位整数及 18 位小数；币种必须匹配账本。可以确认零费用，但不能因取消或估算自动填写零。
凭据、说明和执行结束确认均必填。核对基于管理员确认的外部凭据，不是服务端已经自动校验了供应商账单。

存储统一按 Invocation → Account → Reservation 锁定并比较版本，同事务完成：

1. 最终结算为 SETTLED，held 减去该次预留、charged 增加实际费用（实际费用可超预留或限额）；未知 Token 用量仍保持未知。
2. 用户停止的 UNKNOWN 收敛为 CANCELLED；其他生成 UNKNOWN 收敛为 FAILED／GENERATION_RECONCILED：已确认执行结束，但未恢复完整输出。已有完整成功结果保持
   SUCCEEDED，部分回复保留原引用。
3. 推进 UNKNOWN 的 Invocation／Attempt 版本和 fencing，使旧 Worker 不能覆盖核对结果；按原 Invocation ID
   有条件释放门闩，不影响后来已开始的新调用。
4. 保存账单证据、说明、核对主体、trace、时间、实际 Money、操作键及结果版本，并追加预算／终态事件和发布 Outbox。

响应为不可变核对回执。页面显示核对人、时间、金额、凭据、调用 ID 与请求键；可用请求键查询保存的回执。 同键同内容返回原回执，不重复扣费；同键异内容
409。调用或预留版本冲突 409 后重新查询并核对；最终结算不可被另一个键覆盖。
数据库或回执写入失败则整体回滚，不能出现费用已扣而审计未保存的部分成功。费用未明时保持原预留，不伪造成功结果或最终费用。
网络错误／408／5xx 必须保留原表单和键重试；页面在不确定时锁定表单。

聊天页面“待对账”入口按需查询当前预算，不建立额外循环状态查询。成功后刷新费用、执行状态及历史；普通 UNKNOWN 会话可以继续提问。
`RegenerationReconciliationIntegrationTest` 覆盖高精度及零费用、超额实费、并发核对、防重、防覆盖、旧 Worker、后续门闩、审计故障回滚、HTTP
权限与字段校验。

## 会话异常恢复与费用解耦（ER-2 事务已实现）

- 专项目标允许普通文本本地执行处理终止后继续聊天，仍保留 UNKNOWN 事实及 PENDING_RECONCILIATION 预留；解除会话占用不等于释放资金或证明远端结束。
- 新问题和主动重新生成使用独立调用及预留，原 held 继续参与 available 计算；余额不足可以阻止新调用，不能改成旧执行仍在生成的提示。
- 展示账户 charged／held／available，并在待对账记录中按原调用展示预留、币种、挂起时间及处理入口。held 包含执行中资金，不能全部标为待对账。
- 当前没有自动供应商账单适配器；无证据继续待核对，不按超时自动补零或释放。人工核对仍需要当前权限、实际金额、证据和执行结束确认。
- 旧核对按原调用 ID 有条件释放占用，不切换上下文候选、不修改后续已受理输入、不影响新调用；响应丢失查询原回执或按原键恢复，不能重复结算。
-

ER-2 已验证普通文本 UNKNOWN 正常释放 gate、重新生成另行预留，以及旧费用核对不清除新调用占用；费用查询／核对请求和账本规则保持不变。历史／页面／管理核对等剩余目标按阶段实施。验证证据见专项
§9.3，整体验收见[会话异常恢复专项](../../ARTE%20AI%20会话异常恢复需求及设计.md)
的 ER-AC14～ER-AC24。

### V1.1 核对责任与受限管理接口（新增目标，待实施）

本人接口仍只处理当前 owner；V1.1 为普通用户增加可达的核对责任路径，不能通过替换管理员身份上下文来操作其他账户。 后端以
scope、真实 owner 及 budgetRef 解析稳定 accountRef，在既有公共账户资源授权记录上指定主核对责任人，并审计指派与接替。 公共动作是
budget-account:assign-reconciler 和 budget-account:reconcile；工作空间管理员、模型创建者和 ai:budget:admin 均不自动拥有目标账户权限。

目标本人账户读取增加 accountRef 和 reconciliationHandling，后者含 status（SELF_SERVICE／ASSIGNED／UNASSIGNED）及
responsible；ASSIGNED 提供获授权可见的主责任人 subjectId／displayName，其他状态可为 null。
它只是定位和责任展示，不是授权令牌。无责任人时指向有账户指派权的治理流程，不假称管理员已经能够代办。

| 目标路径                                       | 请求与授权                                                                                                    |
|------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| `/ai-new/budget/managedPendingReconciliations` | scope、accountRef、分页；当前 ai:read 加目标账户 budget-account:reconcile，仅读最小财务记录                   |
| `/ai-new/budget/confirmManagedReconciliation`  | scope、accountRef、既有核对字段及 Idempotency-Key；上述权限再加 ai:budget:admin，核验目标调用／预留／账户归属 |
| `/ai-new/budget/managedReconciliationReceipt`  | scope、accountRef、invocationId、原 key；当前读取及账户授权仍须有效                                           |

管理操作的真实 actor 是核对人，账本 owner 是被明确授权的目标账户；两者分别审计。复用原版本／金额／证据／幂等及不可覆盖结算规则，跨入口或接替也不能重复扣费。
授权不开放聊天正文、模型凭据、其他账户、额度或费率修改。本人、责任人、指派人三种身份和撤权必须分别验收。

账户使用者／凭据持有人协助提供可归属账单，核对责任人跟进并核验；运维协助安全诊断，不替代账单或取得结算权。
汇总余额、日总消费、模型已恢复均不足以证明单次费用。无法取得归属证据时明确保留待核对资金这一已知限制，不自动补零、释放或提供未设计的垫付／冲销。
管理接口和责任派生字段均尚未实现；普通用户场景须有实际可达处理路径方可验收。完整边界及 ER-AC29～ER-AC31
见[专项 §6.3.1](../../ARTE%20AI%20会话异常恢复需求及设计.md#631-核对责任授权与管理核对契约待实施)。
