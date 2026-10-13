# 公共空间 HTTP：第 3 步第 4 批

首批共 10 个端点：`com.arte.app.web.controller.platform.WorkspaceController` 提供空间、偏好和命令回执；同包的
`WorkspaceAccessController` 提供单项／批量权限检查。路径使用完整主体与操作，便于浏览器网络面板识别。 两个 Controller 均调用
`ReactiveWorkspaceOperations`，身份／授权／幂等／事务由已装配的公共服务负责。 本次补充 core 的
`DefaultCommandReceiptQueryService`，复用现有回执 Store、身份与治理门控，不增加表或另一套权限算法。

## 启用和接口

沿用 PLATFORM_WORKSPACE.md 的身份核对、DDL 和三项 enabled 配置。`arte.platform.workspace.enabled=true` 同时启用已装配服务和本批
Controller；关闭时不注册空间 HTTP。Controller 路径为 `/workspace`，部署 `context-path=/arte` 时完整地址如下。

所有操作为 POST，使用现有登录 Bearer Token。类级 `@PreAuthorize("isAuthenticated()")` 满足现有入口安全扫描；公共服务在数据库工作线程
重新核验当前账号与目标资格，旧 RBAC 的 admin 不绕过空间授权。

| 完整 URL                                         | 请求体                                                        | 响应                                                   |
|--------------------------------------------------|---------------------------------------------------------------|--------------------------------------------------------|
| /arte/workspace/initializePersonalWorkspace      | 无                                                            | ResultContext<CommandReceipt<PersonalWorkspaceView>>   |
| /arte/workspace/listAccessibleWorkspaces         | current、size，缺省 1／20，size 最大 100                      | PageView<WorkspaceSummary>，total 仅包含当前可访问空间 |
| /arte/workspace/getWorkspace                     | scope                                                         | ResultContext<WorkspaceView>                           |
| /arte/workspace/createWorkspace                  | tenantId、name、description                                   | ResultContext<CommandReceipt<WorkspaceView>>           |
| /arte/workspace/renameWorkspace                  | scope、expectedVersion、name、description                     | ResultContext<CommandReceipt<WorkspaceView>>           |
| /arte/workspace/getWorkspacePreference           | 无                                                            | ResultContext<PreferenceView>                          |
| /arte/workspace/updateWorkspacePreference        | expectedPreferenceVersion、startupScope、lastScope、favorites | ResultContext<CommandReceipt<PreferenceView>>          |
| /arte/workspace/access/checkWorkspaceAccess      | scope、resourceType、action                                   | ResultContext<AccessResponse>                          |
| /arte/workspace/access/batchCheckWorkspaceAccess | requests，1～100 项 CheckRequest                              | ResultContext<List<AccessResponse>>，与输入逐项对应    |
| /arte/workspace/getWorkspaceCommandReceipt       | operationId                                                   | ResultContext<CommandReceipt<?>>，原操作的公开结果     |

初始化、创建、改名、偏好更新必须携带 `Idempotency-Key` 请求头。其校验沿用公共 CommandMeta：非空、最长 256、不能包含控制字符或首尾空白。
操作键由客户端为一次用户意图生成，服务端不得替换；结果不确定时按原键和原业务字段重试。相同键不同业务字段返回 409，不自动换键重发。
本人初始化不接收 tenantId／workspaceId／subjectId／角色。初始化回执提供真实租户、默认空间和偏好，前端以这些 ID 或目录结果定位后续操作。

`scope` 为 `{tenantId, workspaceId}`，只是定位信息，不是授权声明。请求 DTO 不绑定 TrustedActor、owner、角色、默认空间关联或权限版本。
名称最长 100，描述最长 1000；公共命令继续校验标识、名称规范化和版本。修改空间同时指定名称和描述，description 缺省／null 表示空描述。
偏好更新为完整目标值，必填 expectedPreferenceVersion 和 favorites；startupScope／lastScope 缺省或 null 清空，空 favorites
清空收藏，最多 100 项。 系统 defaultScope 不属于更新字段。偏好不改变其他浏览器标签的当前空间，也不授予访问资格。

## 请求示例

```http
POST /arte/workspace/renameWorkspace
Authorization: Bearer <token>
Idempotency-Key: 8df35bf5-46c0-43e8-a07c-42c0f1774892
Content-Type: application/json
```

```json
{
  "scope": {
    "tenantId": "<初始化或目录返回的ID>",
    "workspaceId": "<目录返回的ID>"
  },
  "expectedVersion": 0,
  "name": "我的学习空间",
  "description": "用于整理文章和学习资料"
}
```

expectedVersion 取当前 WorkspaceView.version；偏好更新取 PreferenceView.version。成功回执保留
operationId、status、delivery、value、 resultVersion、acceptedAt 和 completedAt；重放返回原回执，delivery
表示返回原结果。枚举和时间使用项目现有序列化规则，不由前端推算 ID 或角色。

## 权限检查

以下请求可发送到 checkWorkspaceAccess；批量请求使用 `{"requests":[...上述请求...]}`，保留输入顺序和重复项。

```json
{
  "scope": {"tenantId": "<租户ID>", "workspaceId": "<空间ID>"},
  "resourceType": "workspace",
  "action": "workspace:update"
}
```

首批只接受 tenant／workspace 根目标，resourceId 和 purpose 由服务端构建，不接受客户端自报归属或授权凭据。 tenant 目标的
scope 必须只有 tenantId，例如检查 `tenant:workspace:create`；workspace 目标必须提供完整 scope。
`article:create` 等创建资格在 workspace 根目标上检查；已有文章或模型的正文／使用 ACL 尚未接入，不能传业务资源 ID 或借此取得权限。

返回 data 为 scope、resourceType、action、allowed、reason、checkedAt，不返回 Principal、AuthorizationVersions、VerifiedScope 或
VerifiedAccess。 普通拒绝仍为 HTTP 200／success=true／allowed=false；不可见或不存在均为 reason=NOT_FOUND，合法格式的未知动作返回
UNKNOWN_ACTION。 权威存储不可用为 HTTP 503；批量中任一权威失败使整个批次失败，不返回残缺允许结果。参数格式错误为 400。 reason
使用稳定字符串，时间／枚举延续项目序列化。前端以 allowed 控制按钮展示，执行命令仍由后端重新授权；checkedAt 不表示授权有效期。
允许检查成员或归档动作，不代表相应管理端点已经实现，前端还须按已交付功能开放入口。

## 命令回执查询

```json
{"operationId": "<原成功回执返回的operationId>"}
```

发送到 getWorkspaceCommandReceipt，无须 Idempotency-Key。operationId 是服务端返回的操作 ID，与客户端 operationKey 不同。
若整个响应丢失、还不知道 operationId，继续用原 Idempotency-Key 和原业务字段调用原命令，不能生成新键重复提交。

仅允许原操作主体查询，服务在同一事务内取得主体及结果范围门控，再重检当前身份和所有历史结果引用的空间读取资格。
支持个人初始化、空间创建、空间改名、偏好更新这 4 种已实现命令的成功回执；查询保留原 value、版本、时间和 operationId，delivery 为
REPLAYED， 不执行原命令、不新增回执、审计或事件。历史 currentRole 等字段是当时结果，不能当作当前权限；刷新当前信息应调用
getWorkspace／权限检查。 个人初始化另核验当前受保护的个人租户、默认空间关联及两级
OWNER；偏好结果的启动、最近访问和收藏引用均按当前资格重检，即使当前偏好已清空。 任一引用已不可见时整份回执返回
404，不泄露旧空间引用；他人回执及不存在回执也为 404。权威故障、未知结果 Schema、损坏关联或非成功状态为 503。 回执响应不返回
CommandIdentity、operationKey、请求摘要或存储信封。后续异步命令的 PENDING／失败结果需随相应功能另行接入，不能用本接口推断其完成。

## 错误与追踪

`PlatformExceptionHandler` 只处理 WorkspaceController 与 WorkspaceAccessController，优先于旧全局 Advice。同步绑定／命令错误和
Mono 异步错误均按 HTTP 语义返回， 目录错误仍为 PageView，其他错误为 ResultContext；不使用同步 ResultContext.wrap 捕获响应式错误。

| code                                                                                                        | HTTP 状态               |
|-------------------------------------------------------------------------------------------------------------|-------------------------|
| PLATFORM_INVALID_ARGUMENT／PLATFORM_UNKNOWN_ACTION                                                          | 400                     |
| PLATFORM_UNAUTHENTICATED                                                                                    | 401                     |
| PLATFORM_ACCESS_DENIED／PLATFORM_POLICY_DENIED                                                              | 403                     |
| PLATFORM_NOT_FOUND                                                                                          | 404，不存在与不可见一致 |
| PLATFORM_VERSION_CONFLICT／PLATFORM_IDEMPOTENCY_CONFLICT／PLATFORM_IDENTITY_CONFLICT                        | 409                     |
| PLATFORM_MEMBER_ROLE_CONFLICT／PLATFORM_OWNER_TRANSFER_REQUIRED                                             | 409                     |
| PLATFORM_SPACE_READ_ONLY／PLATFORM_SPACE_CLOSING／PLATFORM_TENANT_INACTIVE／PLATFORM_CAPABILITY_NOT_ENABLED | 409                     |
| PLATFORM_AUTHORITY_UNAVAILABLE                                                                              | 503                     |
| PLATFORM_INTERNAL_ERROR                                                                                     | 500                     |

错误响应 `success=false`，code 为上述稳定字符串；desc 不回传 SQL、请求内容或底层异常消息。所有 Advice 错误响应提供
X-Trace-Id。 非分页错误的 data 包含 traceId 与 retryableWithSameKey，后者仅在 AUTHORITY_UNAVAILABLE 时为
true，表示可按原键有限重试，不表示必须自动重试。 若失败发生在安全过滤器、尚未进入 Controller，仍沿用现有认证入口响应；本批不改变全站认证失败格式。

写命令 traceId 使用服务端 MDC 标识，缺少时生成，并保存到请求属性以跨越 MVC 异步分发；不信任客户端自报 traceId。 旧 WebAspect
仅对 platform Controller 保留原异常和最小方法日志，继续清理旧线程上下文，其他 Controller 仍按原处理方式运行。 请求正文和
Authentication 不进入该分支日志。

## 范围和验证

首批不开放邀请、成员治理、资源 ACL、归档、关闭或组织治理。不存在“切换空间”写接口，前端在每个标签保存选择，后续请求携带 scope。
不迁移旧文章、标签、模型、会话或预算归属，不切换 ainew 授权模式。

WorkspaceHttpIntegrationTest 通过 MVC、实际 WebAspect、真实身份与 MyBatis／core 服务验证全部 10 个端点、分页、偏好清空、幂等与版本冲突、
参数错误、账号停用、越权隐藏、权限批量顺序／上限、历史回执不重复写入、撤权后的回执拒绝以及权威故障／过载 503。
DefaultCommandReceiptQueryServiceTest 覆盖已交付命令的只读结果、当前读取权限、历史引用撤权、旧身份快照与损坏回执拒绝。
PlatformExceptionHandlerTest 覆盖全部 16 种公共错误映射和未知错误脱敏。 WebSecurityConfigTest 同时检查空间接口与已有
ainew 接口的安全扫描兼容性。默认使用隔离 H2，不连接业务数据库。

2026-10-10 验证记录（JDK 21）：本轮新增 HTTP 场景 6 项、core 回执查询 6 项；连同公共契约／默认服务／授权／事务门控回归 156 项，
HTTP／安全／身份会话／应用服务装配／响应式适配／旧 Web 切面／异步认证回归 84 项，共 240 项通过，无失败、错误或跳过。 数据库接口闭环本次使用隔离
H2；没有连接业务数据库，也没有重跑真实 MySQL／Redis 或完整旧 AI 套件。 测试通过显式加载本地 Mockito Java agent
运行，避免受当前执行环境的自动 attach 限制；不修改项目测试配置或生产启动参数。
