# 新 AI 预算账户查询 HTTP 接口

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
