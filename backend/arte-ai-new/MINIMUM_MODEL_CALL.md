# ai-new 最小模型调用

依据顶层设计 §2.5、§2.6、§2.8 和快速受理／耐久输出约定，打通一条独立的 **文本、非流式、一个尝试**模型链。 生产代码依赖方向为
`app → ai-new → base`。app 同时保留旧 AI 依赖；旧聊天路径、模型表、供应商适配器和前端没有切换。

## 已实现的链路

```text
经验证会话 → 服务端任务上下文 → 固定能力／连接／绑定
          → 当前模型使用授权 → 固定协议正文及摘要 → 明确外发同意
          → 外发检查 → 数据库原子受理、预算预留及耐久工作队列
          → 单活 Worker 租约 → 异步准入、限速及任务认领 → 当前配置／授权／外发复查与审计
          → 记录可能发送 → 受控连接 → 映射结果／用量
          → 原子提交结果、终态事件、预算状态
```

| 模块／类型                                                   | 职责                                                                                                 |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| ai-new：CapabilityCatalog／ConnectionManager／BindingManager | 组合 ModelDefinitionStore 查询固定发布版本；管理／发布命令仍待补充                                   |
| ai-new：ModelBindingResolver                                 | 核对 MODEL 类型、固定版本、已发布状态、完整绑定作用域、model.generate 操作和连接匹配                 |
| ai-new：ModelGateway／DefaultModelGateway                    | 类型化 prepare／generate，选择唯一适配器；不执行业务工具                                             |
| ai-new：InvocationCoordinator                                | 逐次授权、外发检查、准入、幂等受理、一个尝试、有界执行、查询／重放／取消                             |
| ai-new：BudgetService／BudgetQuote／BudgetLedger             | 受控单次报价与预留查询；账本写入与执行存储原子组合                                                   |
| app：ConfiguredModelDefinitions                              | 运维配置的首个默认模型／连接／绑定；作用域内成员仍须通过实时应用／任务策略                           |
| app：ExistingModelAccessPolicy                               | 独立模型领域权限，复用当前账号、成员、注册任务、主体动作、应用／绑定和连接状态；不把模型当作文章资源 |
| app：CompatibleChatProviderAdapter                           | 兼容聊天正文、文本结果、finish_reason、用量与版本化配置的 token 价格估算                             |
| app：PinnedHttpConnectionRuntime                             | 固定 DNS 解析后的 IP、TLS 原主机校验、SecretRef 按需解析、有界 HTTP/1.1 响应，不隐式重试或跟随重定向 |
| app：JdbcModelExecutionStore                                 | 可靠受理、原子预算、调用／尝试快照、耐久事件与终态；实现三个可替换存储端口                           |
| app：NewModelCallService／NewModelController                 | 独立认证 HTTP 路径，准备最终正文后记录用户明确同意；请求体不能提供主体、凭据、地址或报价             |

`InvocationCoordinator`、控制面入口和 `BudgetService` 保持普通组合类；网关、策略及存储使用接口，快照使用 record，状态使用
enum。 最小 `ModelExecution` 是 Invocation 与单个 Attempt 的联合查询快照，不含原始提示词或 SDK 对象。 原先泛型
Invocation／Attempt 声明保留，未来多尝试、Job／Run 关联再扩展其持久化映射。

## 输入、结果与支持范围

本阶段只支持 SYSTEM／USER／ASSISTANT 文本消息、temperature 和 maxOutputTokens，至少有一条 USER 消息。 受控配置固定模型名，默认最多
128 条消息、16 KiB 完整协议正文、2048 输出 tokens；输入／输出上限可在明确范围内配置。
消息、文本部件、请求、结果和配置集合使用不可变副本；基础选项、固定版本、费用／用量完成结构校验。

工具、TOOL 消息、多模态产物、结构化输出、streaming、其他网关或未知协议特性明确拒绝，不静默转换为可用能力。 模型返回工具请求、非
stop／length 终止原因、缺失正文或非法用量时不发布伪造文本结果。 适配器是明确的兼容协议子集：`POST` 配置中的完整聊天路径，发送
model／messages／stream=false／max_tokens； 只读取一个 choices 的 message.content、finish_reason 和可选
prompt_tokens／completion_tokens。 供应商必须通过自己的契约测试；不同供应商的参数名称、收费、限额和特性不能由“兼容”推断。

最终文本及用量存入结果和终态事件；耐久工作表保存版本化文本输入、原始授权上下文、执行期限及同意引用，供未派发工作在期限内恢复。
不持久化 API Key、会话 Token、SDK 对象或 Java 回调；已派发工作不会自动重发。工作正文包含历史消息，应按聊天数据配置访问、备份及保留策略。
独立模型入口不写文章或创建会话，不接入知识库／业务来源，也不把任意客户端来源引用当作已授权资料。会话与历史组合由 [最小聊天服务](MINIMUM_CHAT_SERVICE.md)
提供。

## 受理、幂等与预算

- HTTP 受理不等待本机准入。数据库未完成工作容量为 threads + queue-capacity，满载或本实例没有有效 Worker
  租约时拒绝新受理；同键重放仍可查询原执行。 Worker 取得本机并发许可后才认领工作，数据库启动速率窗口跨重启保留，排队时间计入执行总期限。
- 幂等身份固定完整 ExecutionScope、model.generate 和客户端 Idempotency-Key；请求摘要由服务端生成，覆盖实际协议正文、能力／绑定／连接版本、期限和流式选项。
- 同键同输入返回原 executionId；同键不同输入返回 IDEMPOTENCY_CONFLICT。重放查询不重新调用模型、不重复预留预算。
- 每个作用域须由运维显式开通 budget 行。当前账本按租户／空间／主体／主体种类隔离，不隐式创建额度，也不声明已实现租户总账／所有预算层级。
- 接受新调用时锁定权威预算行，核对币种、启用状态、limit - reserved - spent，预算预留、ACCEPTED 记录、受理事件和工作正文在
  REQUIRES_NEW / READ_COMMITTED 同一事务提交；工作表写入失败会一并回滚。
- 只有受理提交成功才返回 AcceptedExecution／HTTP 202。受理不等于生成成功或业务保存。
- 认领、RUNNING、派发及终态写入校验实例租约和任务租约 token。失联且未派发的 RUNNING 可以回到 ACCEPTED，保持原
  executionId／attemptId 并追加恢复事件。 外发前独立审计提交，再次检查当前策略，并先保存 dispatched 标记；该标记表示
  **可能已经发送**，不证明供应商已接收。
- 终态、最终结果、终态事件和费用状态在同一数据库事务提交。提交失败不把本地返回值当作成功结果；无权威终态时保留受理／运行状态待核对。

预算报价是运维配置的单次预留上限。适配器以文本字节／消息余量和输出限制估算是否落在报价内，仍需供应商正式分词及价格验证。
当前费用是供应商用量乘以已配置的单 token 价格并向上保留 8 位小数，属于估算， **不等于最终供应商账单**。
实际估算超过预留时仍记录完整已发生费用，阻止后续超额度受理，不把账本截断为预留值。

确认未发送的失败释放预留。已可能发送的失败、取消、超时、未知用量或币种不符保持 PENDING_RECONCILIATION，继续占用预留，不记成零费用。
已确认用量的成功调用结算为 SETTLED。本阶段没有人工费用核对命令、定时对账或自动重试；再次生成须新键，可能再次收费。

## 授权、外发与网络边界

HTTP 入口遵循现有 @PreAuthorize 和认证过滤规则，身份仍由 ExistingIdentityAdapter 交叉核对会话／Token／稳定用户 ID。
ExecutionContextFactory 为当前应用／绑定注册任务，客户端只选租户、空间和文本。
模型使用权限与资料权限分属不同领域，来源为空时不依赖文章权限；账号、成员、任务、应用／绑定或连接撤销后停止新调用和结果查询。
查询使用本次认证的新上下文，不复用原执行的已过期期限；事件读取前后均重新核对，完整作用域不匹配不返回结果。

请求必须 `externalTransferConfirmed=true`，表示用户明确确认该默认模型的外发操作。
界面接入前必须向用户展示实际目的地与用途，再发送确认字段；本阶段没有替旧前端自动确认。 服务端按最终协议正文、实际目的地、用途
model.generate 和任务保存精确同意，并在调用边界由 ExistingEgressPolicy 重新核对。
此字段不能替代受控连接、用途规则、当前应用策略或同意内容绑定。来源为空仍检查完整消息摘要和外发权限。

生产只允许 HTTPS 的受控完整 endpoint，无凭据／查询／片段；host 固定为运维配置允许集合。 运行时核对全部解析地址拒绝回环、私网、链路本地、多播等，再连接选定
IP，TLS 仍以原主机校验证书，避免检查与实际连接再次解析 DNS。 不使用系统代理、自动重定向、协议重试或供应商 SDK 默认重试。凭据使用
`arte.ai-new.model.api-key` 的编译配置；为空时，在发送时读取 `secret-env` 指定的环境变量。SecretRef 不含明文，初始化接口也不返回密钥。
响应最多 1
MiB，头部／单行有界，只接收 identity 编码、明确长度、chunked 或关闭连接定界；不提供完整通用 HTTP 客户端特性。 协议适配保留在
app，避免把 JSON、SDK、Spring 或数据库依赖导入 ai-new。

数据库、DNS、TLS、socket 是专用工作线程上的阻塞 I/O，不能声明已经非阻塞。 工作总期限取原任务期限与提交起算的 timeout 较早值（HTTP
新入口为 90 秒）；连接／读等待最多 30 秒且受剩余期限限制。 合作式取消会在检查点停止；阻塞 DNS／系统调用不能保证立即中断，许可一直持有到实际
Java 工作退出。 HTTP 断线不自动取消；显式 cancel 返回 CANCELLING 只代表停止请求，发送后的终态可能是 OUTCOME_UNKNOWN。
取消标记写入数据库，由当前执行者读取；未派发排队任务可以确认 CANCELLED 并释放预算。运行中取消或到期通过
ExecutionCheckpoint.onStop 关闭已注册 socket， 许可和 future 仍等真实工作退出。不能保证立即中断 DNS，也不宣称供应商已取消请求；不提供暂停／继续。

## 事件、恢复与保留

本阶段非流式事件为 ACCEPTED、RUNNING、终态，序号单调；确认未启动的工作可能没有 RUNNING 事件。 GET events 使用 exclusive after
游标、最多 100 条，数据来自数据库，不调用供应商。 没有 SSE、逐 Token 输出、游标归档／过期清理或供应商续传。后续流式输出须按批次耐久提交，再使用
base 背压流通知订阅者。

JdbcModelWorkQueue 使用数据库时钟和单活 Worker 租约；失去租约的旧进程不能继续领取、派发或写入结果。DurableModelWorker
周期续约和扫描， 期限内且未派发的工作重新排队，恢复时重新校验输入指纹、定义、当前权限与原始外发同意；授权上下文与执行期限分别保存，不改写已授权任务身份。
已派发但失联的工作标记 OUTCOME_UNKNOWN，保留 PENDING_RECONCILIATION 预算；不自动重发。升级前缺少工作正文的遗留执行，未派发转
INTERRUPTED 并释放预留，已派发转未知结果。

停机先标记 draining，拒绝新受理／认领，保持续约并限时等待已开始的工作；宽限期结束后请求停止并关闭已注册 I/O，再释放实例租约。
未认领工作仍保存在数据库，下次启动在原期限内恢复。默认 lease-duration=PT30S、poll-interval=PT0.5S、shutdown-grace=PT10S， 均由
app.properties 编译到 application-ai-new.yml。部署仍为 single-instance；同一数据库有第二个进程时只允许一个 Worker
活跃，备用进程拒绝新受理， 这不是多实例负载均衡方案。恢复事件保持执行内单调序号。
幂等、结果、事件、任务和同意记录暂不自动清理；生产启用前须确定保留／备份策略，不能随意删除仍需去重／对账的记录。

## 部署与请求示例

### 当前项目默认配置

`arte-ai-new/src/main/resources/application-ai-new.yml` 与已有 `application-core.yml`、`application-ai.yml` 一样， 由主应用的
`spring.profiles.include: core, ai, ai-new` 激活并加载，避免与 `arte-app` 的根 `application.yml` 重名。 所有新配置使用
`@...@` 占位符，Maven 构建时从
`backend/profile/app.properties` 替换；该 properties 文件不直接作为 Spring 运行时配置加载。

当前默认启用安全桥接、单实例执行支撑、模型和聊天。默认模型为 DeepSeek-V4.1-Flash，API 标识
`deepseek-flash`，接口为 `https://api.deepseek.com/chat/completions`，使用非思考模式（`reasoning-effort=none`）。
默认作用域 `personal-1/workspace-1` 对应账号 ID=1，用户名可以是 `zhangsc` 或其他名称；与实际成员不符时修改配置。
私有产物目录为工作目录下的 `data/ai-new/private-artifacts`，4 个执行线程、32 个队列位置、每分钟最多启动 60 次。 当前正文上限
16384 字节、聊天文本上下文上限 8192 字节、最多 32 轮历史、快照有效期 10 分钟、输出上限 2048 token。

价格按 [DeepSeek 官方价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/) 的高峰未命中缓存价格配置： 输入 2
元／百万 token、输出 8 元／百万 token，币种 CNY，单次预算预留上限 0.10 元。 当前固定单价估算未区分缓存命中或峰谷时段；实际费用以供应商账单为准。数据库预算行须使用相同币种。

`arte.ai-new.model.api-key` 保持为空。填写后重新构建并重启后端，或保留空值并设置环境变量
`ARTE_NEW_MODEL_API_KEY`。尚未填密钥时仍可启动服务、查询初始化和管理会话，模型外发会因凭据缺失失败。 模型目录、初始化及错误响应不返回该密钥。

配置加载不会自动执行 DDL、建立成员关系、授予应用／连接／外发许可或创建数据库预算额度；这些数据仍按下方步骤准备。

对于账号 ID=1 及 `personal-1/workspace-1`，在实际连接的数据库准备好上述表后，可手动执行
[`arte-ai-new-deepseek-admin-dml-mysql.sql`](../arte-app/scripts/arte-ai-new-deepseek-admin-dml-mysql.sql)。 脚本补齐空间成员、AI
使用与外发应用许可、固定 DeepSeek 连接和用途许可，并初始化 10 元 CNY 的应用侧累计预算上限。 脚本按稳定用户 ID
和正常账号状态匹配，不要求用户名为 `admin`；文件名中的 `admin` 沿用最初的初始化命名。
已有记录不会被覆盖，包括撤销的权限、已有额度及消耗；末尾查询会显示当前状态。执行后刷新聊天页面即可，无需重启后端。
若当前用户、租户、空间、模型绑定版本或应用 ID 与默认值不同，先按实际配置调整脚本；预算／连接键也须用相应 Java 帮助方法重新生成。

运行数据库脚本、授权数据及预算仍须按部署环境准备，应用启动不会自动执行这些脚本。准备顺序：

1. 按 [身份接入说明](../arte-app/SECURITY_BRIDGE.md) 部署安全表及个人作用域映射。
2. 按 [公共执行说明](../arte-base/MINIMUM_EXECUTION_SUPPORT.md) 部署审计／产物表，设置私有目录和 single-instance 模式。
3. 部署 `arte-app/scripts/arte-ai-new-model-ddl-mysql.sql` 和 `arte-app/scripts/arte-ai-new-work-ddl-mysql.sql`，不修改旧
   AI 表。 升级时先停止旧版本，再执行工作表脚本，最后构建／启动新版本；缺少工作表会明确启动失败。
4. 为实际 tenant／workspace／principal 开通预算，登记默认连接及当前用途／应用策略；这些记录不自动放行。
5. 配置真实兼容 endpoint、模型、明确版本、价格与环境变量 SecretRef，再开启新模型入口。

配置示例（域名、模型、价格和额度均须替换为已验证配置）：

```yaml
arte:
  security:
    bridge:
      enabled: true
  execution:
    support:
      enabled: true
      mode: single-instance
      tenant-id: personal-1
      artifact-directory: /var/lib/arte/private-artifacts
      threads: 4
      queue-capacity: 32
      starts-per-minute: 60
  ai-new:
    model:
      enabled: true
      tenant-id: personal-1
      workspace-id: workspace-1
      version: v1
      application-id: ai-new-model
      endpoint: https://provider.example/v1/chat/completions
      model-name: verified-model
      secret-env: ARTE_NEW_MODEL_API_KEY
      currency: USD
      maximum-call-cost: 1.00000000
      input-token-price: 0.00000100
      output-token-price: 0.00000200
      max-input-bytes: 16384
      max-output-tokens: 2048
```

默认引用为 `ai-capability/default-model/v1`、`ai-binding/default-model/v1`、`ai-connection/default-model/v1`
；修改模型、价格或协议配置应同时递增 version。 应用策略须包含 ai-new-model／default-model 的 resource.ai_process 和
resource.egress；用途规则固定 model.generate 与配置连接版本。 连接的 origin 仅协议／主机／端口，例如
`https://provider.example`。 部署代码使用以下公开帮助方法计算一致键，无需复制序列化实现：

```java
String connectionKey = JdbcSecurityRepository.connectionKey(
        ResourceRef.saved("ai-connection", "default-model", "v1"));
String budgetKey = JdbcModelExecutionStore.budgetKey(
        new ExecutionScope("personal-1", "workspace-1", new PrincipalRef("1", PrincipalType.USER)));
```

按上述键显式创建 arte_security_connection、arte_security_egress_rule 和 arte_ai_new_budget 的启用记录；预算初始
reserved／spent 为零，币种与报价一致。 上述帮助方法不是授权接口，实际成员和账号仍须由已部署数据核对。

已登录客户端提交：

```http
POST /api/ai-new/model/generate
Idempotency-Key: client-operation-1
Content-Type: application/json

{
  "tenantId": "personal-1",
  "workspaceId": "workspace-1",
  "messages": [{"role": "USER", "text": "请解释这段代码的作用"}],
  "options": {"temperature": 0.5, "maxOutputTokens": 256},
  "externalTransferConfirmed": true
}
```

202 返回 executionId、executionKind、initialState、statusUri、eventsUri；之后使用相同登录会话查询：

- `GET /api/ai-new/model/executions/{id}?tenantId=personal-1&workspaceId=workspace-1`
- `GET /api/ai-new/model/executions/{id}/events?tenantId=personal-1&workspaceId=workspace-1&after=-1&limit=50`
- `POST /api/ai-new/model/executions/{id}/cancel?tenantId=personal-1&workspaceId=workspace-1`

查询地址不携带访问凭据；scope 选择仍须通过当前成员和绑定授权。 使用稳定错误信封和相应
400／403／404／409／429／503／504；存储提交结果不明时返回未知错误，不向客户端回显远端正文、提示词或凭据。 本阶段没有改变旧前端返回类型和调用地址。

## 验证

```bash
# backend 目录；协议测试启动本机回环 HTTP 服务，不访问真实供应商。
mvn -o -pl arte-app -am -Dmaven.compiler.proc=full \
  -Dtest='MinimumModelCallTest,NewModelIdentityIntegrationTest,NewModelConfigurationTest,ModelContractsTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

测试使用 H2 的真实事务、生产 DDL（仅去掉 MySQL 引擎／字符集声明）、真实 JDBC 审计、真实有界执行器与准入，以及本地 HTTP 协议服务。
覆盖 JSON 输入／202 回执、当前身份与同意、撤销、实际协议映射、结果重建、事件重放、幂等冲突、同时受理不重复预留、预算不足、外层回滚、终态事务回滚、
审计失败、发送后未知、缺失用量、运行取消持有许可、作用域隔离、生产回环拒绝、重定向／大响应拒绝、显式恢复与默认关闭。 仍需真实
MySQL 并发与精度、供应商兼容性／真实费用、生产 TLS／DNS／出口、跨节点故障、负载及保留策略验证。

聊天服务为耐久提交派生 `chat:<turnId>` 模型幂等键；独立模型 HTTP 入口保留并拒绝该前缀，防止调用方占用聊天关联的受理身份。
