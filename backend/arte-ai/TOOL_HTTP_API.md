# AI TOOL 管理 HTTP API

更新：2026-10-19，S2-07。本接口提供本地目录、发布和当前空间内本人绑定管理。需求见
[需求及设计](../../ARTE%20AI%20TOOL%20需求及设计.md)
，实现与验证见 [实施记录 §8](TOOL_IMPLEMENTATION.md#8-s1-08管理-api-与现有当前授权)。

S2-02 已新增内部本地只读网关，但本文件仍只有管理接口；没有新增 invokeTool 或绕过耐久受理的同步调用路由。
网关装配及后续接线边界见 [实施记录 §10](TOOL_IMPLEMENTATION.md#10-s2-02本地只读-tool-网关)。

仅当 `arte.ai-new.enabled=true` 和 `arte.ai-new-tool.enabled=true` 时注册。复用现有认证、语言和 `ResultContext`； 外部路径前缀为
`/arte/ai-new/tool`，Controller 路径不包含应用上下文 `/arte`。 启用前须具备现有公共身份／持久化／空间基础、同一物理
`appDataSource` 及相应表，缺少宿主授权时启动失败。 本次未连接或部署业务数据库，TOOL 仍默认关闭，未登记生产工具或提供前端页面。

S2-03 的内部 `ToolAdmissionService` 已由 S2-04 接入 TOOL Worker 链路：同库可靠受理 → 原固定快照／当前资格 → FREE
原子准备 → ToolGateway → 结果字节 → 终态／事件 → Worker ACK。通用协调器按权威 kind 路由，原生成 submit 语义不扩展为 TOOL。
`arte.ai-new.enabled`、`arte.ai-new-tool.enabled`、`arte.ai-new-execution.enabled` 三开关及显式宿主 `ToolAdmissionAccess`、
`ToolExecutionAccess` 齐备时才装配；只启用 TOOL 不需要模型网关／费率／预算配置。worker-enabled 仍独立控制自动消费。 公开 TOOL
提交仍未开放；S2-05 查询／快照／事件复用通用 Invocation HTTP，新增 `/ai-new/tool/cancelToolInvocation`。 ToolInvocationAccess
复核当前读取／控制资格，公共平台已有稳定身份／空间／RBAC 适配；缺端口时拒绝工具访问。 新调用的真实受理／资源权限及领域适配仍须由组合模块提供，不能直接绑定客户端
ExecutionContext。 本批不部署数据库或开启
TOOL。范围与验证见 [TOOL 实施记录 §13](TOOL_IMPLEMENTATION.md#13-s2-05查询取消与本地恢复)。

S2-06 实施同步：分类调度默认关闭。显式部署 `scripts/arte-ai-dispatch-scheduling-migration.sql`，停止旧受理／消费者并排空旧
DISPATCH 后， 统一启用 `arte.ai-new-execution.scheduling.enabled`，并使 concurrency 与类别槽位总数一致。生产新增 TOOL
受理要求该门槛， Worker 按类别／空间轮转领取，并在数据库仲裁类别、空间和提供者许可；原生成预算／模型限流保持。
队列准入、原排队期限、取消／终态释放及同构清单门控已接入，OUTCOME_CHECK 仅保留槽位，不冒充已有业务核对。
详见 [TOOL 实施记录 §14](TOOL_IMPLEMENTATION.md#14-s2-06分类容量与排队)。未执行业务数据库部署、真实多节点或生产性能验收。

## 当前权限

请求都必须带 `scope: {tenantId, workspaceId}`。Scope 是待验证选择，真实 owner、actor 和权限由服务端解析；白名单请求拒绝未知字段。
每次读取重新授权；变更及幂等重放在持久化原事务内再次授权。过期 token 中的角色／操作缓存不能作为当前资格。

| 本文标记 | 菜单操作                                | 公共空间／AI 资格                                              |
|----------|-----------------------------------------|----------------------------------------------------------------|
| LIST     | aiTool:list                             | WORKSPACE_READ、当前 AI READ；绑定仅限本人                     |
| WRITE    | aiTool:invoke                           | WORKSPACE_AI_USE、当前 AI INVOKE；工具 requiredScopes 必须满足 |
| DISABLE  | aiTool:invoke                           | WORKSPACE_READ；DATABASE 模式下关闭空间 AI 后仍可停用本人绑定  |
| MANAGE   | aiTool:manage 与 aiConfiguration:manage | WORKSPACE_READ；全局目录治理，空间所有者不自动获得             |

DATABASE 模式读取现有空间 AI enabled 与公共 AI 使用资格，派生 `ai:read`／`ai:invoke`，不查询模型、费率或预算。 FIXED
模式使用与稳定主体、账户、类别、Scope 匹配的启用 grant，其 scopes 决定资格，现有启动校验保持不变。 既有 `admin`
操作标记不绕过账户、租户或空间规则；业务资源权限仍须在实际执行边界检查。

## S2-07 目录与默认配置

已新增 `/AI/Tools` 目录与本人配置页，管理入口总数为 17。页面不提供公开工具调用；S2-08 才接入调用预览／提交／结果，S2-09
为只读集成验收。

`listTools` 新增可选 `query`（名称／ID／用途，最多 200 字符）、`tag`（精确标签，100）、`providerId`（精确提供者 ID，256）、`state`。
筛选在服务器有界候选页执行，并与主体、Scope、managed 和 provider 一起绑定游标；records 为空仍可能有 nextCursor，条件变化须重启分页。

新增 `POST /ai-new/tool/prepareDefaultBinding`，需 LIST 和 WRITE 资格及 `Idempotency-Key`，正文
`{scope, tool, configuration}`。 不接受客户端 owner、默认身份、版本策略、绑定 ID 或执行上下文；返回与 saveBinding 相同的
BindingCommand 包装。 默认身份由真实 owner＋稳定 toolId 派生；缺失才创建 PINNED 修订。配置 `{}` 继承工具非敏感默认值；缺必填项返回
422，填写业务字段并用新键提交修正意图。 已有默认绑定直接返回当前指针，不覆盖改名／配置／升级或恢复启用；同键返回原始回执，页面随后
getBinding 读取当前内容。 命令、防重、指针、修订和审计使用原同库事务；没有新增表、工具执行、预算初始化或隐式默认授权。

Schema 响应追加 `documentJson`，绑定 settings 和升级 proposed 追加 `configurationJson`。这是对应标准 JSON
值的精确字符串，不是另一层业务配置。 保留原 document／configuration 字段兼容已有客户端；浏览器编辑／展示精确数值使用原文，提交仍是标准
JSON configuration 对象，禁止 double 归一化。 配置字段应与 Schema 类型保持一致，secret 引用仍只按现有协议处理。

`previewBindingUpgrade` 现允许未修改内容的只读预览，返回当前有效交集；相同基准按已有绑定生命周期校验，变更基准按新绑定校验。
实际 saveBinding／upgradeBinding 仍拒绝 NO_CHANGE；预览不写库、不执行工具、不授予调用资格。新 ENSURE_DEFAULT 命令审计写
tool-binding-receipt v2，部署兼容见实施记录 §15。

## S2-05 查询与停止

状态、结果、批量快照、单页事件和 SSE 使用 `/ai-new/invocation`
既有接口，详见 [执行查询与停止](INVOCATION_HTTP_API.md#tool-查询与停止s2-05)。 TOOL 当前读取额外要求 `aiTool:list`；停止要求
`aiTool:invoke`，两者均受本人归属及当前空间 READ 约束，不读取模型或预算目录。 新增 `POST /ai-new/tool/cancelToolInvocation`
，正文 `{scope, invocationId}`，必须提供独立 `Idempotency-Key`。 该入口使用 `ai:read` 上下文及独立控制资格，空间 AI
已关闭时仍可停止已有工具；不需要 aiTool:list，不能以此停止生成。 与通用查询相同，该收尾入口不依赖 TOOL
新调用开关；当前授权端口缺失时拒绝，不提供默认放行。受理与真实执行端口依旧显式提供。

返回 ACCEPTED／202 只表示取消命令已保存，同键重放返回原回执；终态只以调用查询为准。未执行取消没有 ToolResult，运行中取消可能为
UNKNOWN。 默认绑定、公开调用提交及试用页面仍待后续，不能把本入口当作 invoke API。原 16 个管理接口范围不变。

新调用开关关闭时仍须保持通用执行装配可用；本批未改变至少需要一种派发器的启动约束。

## 请求与分页约定

- 全部 JSON POST。以下“额外字段”之外均须包含 scope；引用采用 `{type,id,version}`，不接受任意类型名或执行器定位。
- 七个写入口必须提供 `Idempotency-Key`
  ：refreshLocalCatalog、publishTool、setToolState、saveBinding、prepareDefaultBinding、setBindingState、upgradeBinding；最长
  256 字符。原因 reason
  必填，最长 1024 字符，不写日志。
- expectedVersion 是十进制字符串。初次发布／新建绑定使用 `"0"`；已有对象必须使用当前版本，不能使用 JSON 数值或 latest。
- 分页参数 cursor 可空、size 默认 20、范围 1—100；响应 data 为 `{records,nextCursor,scanned,exhausted}`。 scanned
  是本次候选扫描量；可见性过滤后 records 可空但仍有 nextCursor。按 nextCursor 继续，不能由空列表推断结束。
- 游标绑定主体、Scope、操作和筛选条件；切换条件后重新查询。游标不授予权限，不提供跨页快照或 total。
- 配置与 Schema 是标准 JSON；保留精确十进制和显式 null，禁止重复键、深度超限或过大值。客户端仍须使用合适的 JSON 数值处理方式。
  读取上限为深度 24、节点 8192、单对象字段／单数组元素 256、字符串读取累计 1,000,000 字符、数字有效位／scale 绝对值 128；配置另限
  1,000,000 UTF-8 字节，并受工具 Schema 和服务器更低上限约束。

## 接口清单

| POST 后缀             | 权限                                    | 额外字段与返回                                                                                                     |
|-----------------------|-----------------------------------------|--------------------------------------------------------------------------------------------------------------------|
| listTools             | LIST；managed=true 时 MANAGE            | provider 固定引用可选、managed 默认 false、cursor／size；返回版本目录页                                            |
| getTool               | LIST；managed=true 时 MANAGE            | tool 固定引用、managed；返回工具及 input／output／configuration Schema，只有管理详情含默认配置                     |
| listProviders         | MANAGE                                  | cursor／size；返回提供者固定引用及 kind，无内部适配器／连接地址                                                    |
| refreshLocalCatalog   | MANAGE                                  | reason；完整刷新全部本地来源，返回原始命令与当前节点状态                                                           |
| validateTool          | MANAGE                                  | tool、review；只读预检，返回 valid、issues、contractDigest、discoveryChecksum、checkedAt                           |
| publishTool           | MANAGE                                  | tool、expectedVersion、reason、review；返回发布命令回执                                                            |
| setToolState          | MANAGE                                  | tool、action、expectedVersion、reason、existingBindingsUntil 可选；返回状态命令回执                                |
| getToolHistory        | MANAGE                                  | tool、cursor／size；返回治理审计页，不返回审核示例原文                                                             |
| listBindings          | LIST                                    | cursor／size；返回本人在该空间的绑定页                                                                             |
| getBinding            | LIST                                    | bindingId；返回当前指针及完整修订                                                                                  |
| getBindingRevision    | LIST                                    | revision 固定 binding/rN 引用；返回本人不可变修订                                                                  |
| saveBinding           | WRITE                                   | bindingId 新建时省略、expectedVersion、reason、settings；返回创建／保存回执                                        |
| prepareDefaultBinding | LIST 与 WRITE                           | tool、configuration；缺失才创建当前本人默认配置，不覆盖既有选择                                                    |
| setBindingState       | enabled=true 为 WRITE，false 为 DISABLE | bindingId、expectedVersion、reason、enabled；返回新修订命令回执                                                    |
| previewBindingUpgrade | WRITE                                   | bindingId、expectedVersion、settings；返回 valid、changes、compatible、proposed、digest，不写库                    |
| upgradeBinding        | WRITE                                   | bindingId、expectedVersion、reason、settings、expectedPreviewDigest、acceptIncompatible 默认 false；重算后确认升级 |
| getBindingHistory     | LIST                                    | bindingId、cursor／size；返回本人管理审计页                                                                        |

普通目录展示已发布过且当前 requiredScopes 满足的版本，包括废弃／停用的生命周期提示，不展示草稿。
`permitsNewBinding` 是生命周期判断，不代表已具备实际调用资格；执行还须满足独立受理及实际资源授权。

`setToolState.action` 只支持 REENABLE／DEPRECATE／DISABLE；PUBLISH 必须使用独立审核入口。 DEPRECATE 可带 UTC Instant 格式的
existingBindingsUntil；其他约束按生命周期模型验证。 管理历史回执中的状态只是原始决定，不能当作当前状态或执行凭据。

`refreshLocalCatalog` 读取来源时不持有数据库事务，提交时重新授权并原子写入目录事实与审计。固定版本不覆写、新版本仍为草稿，不自动发布。
响应字段 delivery／commandId／result／checksum／providers／schemas／tools／recordedAt 表达原始命令，
nodeState／nodeDiscoveryHealthy／nodeChecksum 表达当前节点观察；重放不重新读取来源，也不恢复本节点健康。 来源失败可耐久保存拒绝；SQL
故障不会保存假拒绝，节点忙返回 409，可同键重试。失败保留旧视图并标记不健康。

## 审核与绑定输入

发布审核 `review` 结构：

```json
{
  "examples": [{"input": {}, "output": {}}],
  "riskEvidence": "风险审核记录说明",
  "minimumApproval": "NONE"
}
```

示例须匹配工具真实 Schema，1—8 对，每份 input／output 不超过 32 KiB 标准 JSON；这里的空对象仅演示形状，不是通用有效示例。
如声明业务／远端幂等能力须提供 idempotencyEvidence；如审核兼容边，提供 compatibleFrom 固定工具引用与 compatibilityEvidence。
minimumApproval 使用 NONE／REQUESTER_CONFIRMATION／INDEPENDENT_APPROVAL，不能低于工具风险要求。 预检不调用业务
invoke、不探测远端，不替代实际发布事务再次检查。

绑定 `settings` 完整表达一份修订，不是字段 patch：

```json
{
  "tool": {"type": "tool", "id": "text.countCodePoints", "version": "v1"},
  "versionPolicy": "PINNED",
  "name": "我的文本统计",
  "enabled": true,
  "configuration": {},
  "secrets": {}
}
```

示例中的工具须先由本地来源登记并发布。versionPolicy 为 PINNED／FOLLOW_COMPATIBLE；FOLLOW_COMPATIBLE 只沿已审核兼容链选择。
省略 policy／networkOrigins 时沿用工具默认限制；若传 policy，需完整提供
timeoutMillis、maxAttempts、retryBackoffMillis、maxInputBytes、maxOutputBytes、approvalMode。
服务器／工具限制与绑定约束求交集，不能扩大权限或网络范围。配置按既有完整配置层规则与默认值合并，对象递归合并、数组／标量／null
替换。 当前本地工具拒绝非空 secrets；常见凭据字段也会拒绝，此辅助检查不代表能够识别所有秘密。

新建 saveBinding 省略 bindingId、expectedVersion=`"0"`；服务端分配稳定 ID。保存不得改变工具基线，基线变化必须走升级流程。 升级先
previewBindingUpgrade，展示差异后将 digest 作为 expectedPreviewDigest 原样提交，settings 保持相同；不兼容需明确
acceptIncompatible=true。 如果版本、实际方案或服务器限制改变，返回冲突，重新预览。预览方案是管理提示，不是调用授权。

## 回执、错误与联调顺序

成功返回 HTTP 200 和 ResultContext。命令 data 中 delivery 为 APPLIED／REPLAYED，分别表示首次回执／重放；
outcome／rejection（刷新为 result）表示首次实际决定。首次保存拒绝也可能 delivery=APPLIED，必须同时判断 HTTP 状态和原始决定。
重放前仍先验证当前权限，撤权后不能凭旧幂等键读取成功。

| HTTP 状态 | 典型含义                                                 |
|-----------|----------------------------------------------------------|
| 400       | 请求／引用／版本格式错误、未知字段、缺幂等键、JSON 越界  |
| 401／403  | 未认证／当前资格不足                                     |
| 404       | 不存在或越过本人绑定归属，避免泄露他人版本               |
| 409       | 版本／幂等冲突、计划变化、无变化、当前状态不允许或刷新忙 |
| 422       | 配置不合 Schema、策略／网络约束冲突、本地不支持 Secret   |
| 503／504  | 依赖／数据库不可用或管理期限耗尽；不返回原始 SQL／异常   |

validateTool 与 previewBindingUpgrade 的领域预检失败使用 HTTP 200 + valid=false；传输、当前授权或基础设施错误仍使用相应错误状态。
命令领域拒绝的 data 保留安全原始回执；数据库错误不伪造回执。消息遵循现有语言设置，错误码使用现有体系及 AI_TOOL_* 扩展。

建议本地联调顺序：登记真实来源／适配器 → 管理刷新 → managed 目录读取草稿 → 审核预检 → 发布 → 普通目录读取 → 新建本人绑定 →
修订／历史读取 → 升级预览／确认 → 停用。

当前没有 invokeTool、ensureDefaultBinding、resolveToolSet、审批、工具聊天等接口。 提供者单独 syncProvider／setProviderState
须配合后续可变治理及远端接入实现；当前完整刷新不提供任意 URL 或客户端注册适配器的入口。

## 分类调度拒绝（S2-06）

受理队列满：HTTP 429，code=205056（AI_DISPATCH_QUEUE_FULL），Retry-After: 5。尚未受理，不产生 Invocation／Attempt 或费用预留；
查询原幂等身份后使用同一业务键重试，不靠换键规避配额。相同请求已经受理时返回原回执，不重复占用队列。 调度权威、清单或配置不满足执行条件时：HTTP
503，code=305003（AI_DISPATCH_UNAVAILABLE），不降级为无限并发。 当前仍没有公开 TOOL 提交 API；以上是内部受理错误及现有 HTTP
错误适配的契约，后续提交入口复用它，不新增隐式调用路径。
