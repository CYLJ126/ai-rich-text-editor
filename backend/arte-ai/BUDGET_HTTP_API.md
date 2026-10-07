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

测试不访问当前业务数据库或真实模型供应商。当前接口提供账户汇总；预算管理、充值／调整限额、预留明细和人工费用对账不属于本次
HTTP 实现。
