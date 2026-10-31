# 模型与公共 ACL 管理 HTTP：第 3 点第 4 批

日期：2026-10-31。

本批开放 14 个 POST 端点：模型身份／版本管理 5 个、公共 ACL 管理与检查 7 个、显式 ACL 清单管理 2
个。复用前三批应用服务、当前身份核验、同库治理门控、CAS、幂等回执、审计和 Outbox。不开模型连接／密钥编辑、前端页面、文章、成员治理或跨空间复用。

## 装配与身份

- `ResourceAccessController`：公共身份、持久化及空间三项 enabled 同时为 true；不要求 AI 开启。没有受信领域描述的资源仍默认拒绝。
- `ModelResourceController`：再要求 `arte.ai-new.enabled=true`；与已有模型管理和 ACL 运行适配一起装配，FIXED／DATABASE 均可使用。
- 两个 Controller 均声明 `@PreAuthorize("isAuthenticated()")`。新路径进入既有受保护 URL 分类，使用现有 Bearer 登录，不增加匿名或
  RBAC admin 绕过。
- `PlatformAuthenticatedExecutor` 复用原空间适配的最小身份复制规则：仅捕获会话账号
  ID、名称、创建时间；订阅后在有界数据库线程重新核验当前账号与稳定主体，再执行完整同步应用服务调用。原
  `ReactiveWorkspaceOperations` 委托同一实现，保留现有构造器和事务语义。
- 请求定位信息不授予权限。新 HTTP DTO 拒绝未声明字段；不接受 actor、owner、createdBy、成员代次、发布快照、凭据或客户端 traceId。
  `Scope`／`DefinitionRef` 沿用公共值对象的标识校验。

以下完整 URL 假定 `context-path=/arte`。全部成功响应为 HTTP 200、`ResultContext<T>`；分页数据也位于 `data`，采用
`PageResult` 的 current、size、total、records，不改变旧空间列表的 PageView 格式。枚举请求及新视图中的
source、state、visibility、role 使用表中大写名称；ResourceRef、Scope、DefinitionRef 等公共值对象沿用现有 JSON 格式。

所有写入必须携带原样保留的 `Idempotency-Key`，非空、最长 256、无首尾空白或控制字符。每次变更使用新键；不确定结果用原键及原字段重试，同键不同业务字段为
409。服务端生成 CommandMeta.traceId。成功回执保留 operationId、status、delivery、value、resultVersion 及原时间；HTTP 只转换公开
value，不重执行命令。

## 模型资源与发布版本

前缀：`/arte/ai-new/model-resource`。

| 端点                           | 请求体                                                                         | data                     |
|--------------------------------|--------------------------------------------------------------------------------|--------------------------|
| /createModelResource           | scope、name                                                                    | CommandReceipt<Resource> |
| /getModelResource              | scope、resourceId                                                              | Resource                 |
| /listModelResourceVersions     | scope、resourceId、current 可选、size 可选                                     | PageResult<Version>      |
| /associateModelResourceVersion | scope、resourceId、expectedVersion、binding、source、catalogRevision（按来源） | CommandReceipt<Version>  |
| /disableModelResource          | scope、resourceId、expectedVersion                                             | CommandReceipt<Resource> |
| /getModelAclScope              | scope                                                                          | AclScope                 |
| /updateModelAclScope           | scope、expectedVersion、source、bindings（完整精确清单）                       | CommandReceipt<AclScope> |

创建使用当前 `model:create` 空间资格；资源 ID、WORKSPACE 归属、创建者、初始私有 ACL 由服务端生成。创建不要求预算，不自动关联发布或切换执行规则。查询／版本列表要求
`model:read`；停用要求 `model:disable`；关联要求 `model:publish` 和当前平台 `aiConfiguration:manage`／admin 操作资格。

版本关联 source 为 FIXED 或 DATABASE。FIXED 必须省略／清空 catalogRevision；DATABASE 必须指定已存在的正整数精确修订号，不使用
latest 或当前 head 代替。binding 必须为精确 binding 类型。expectedVersion 是模型管理版本；回执 resultVersion 仍是管理版本，Version
本身记录精确发布引用。

Resource 返回 resourceId、scope、name、createdBy、state、bindingId、version、createdAt。Version 只返回 resourceId、精确
binding／capability／connection／rate 引用、providerId、remoteOperation、contextWindowTokens、原定义
source／catalogRevision／releaseRef、linkedBy／linkedAt。 **不输出 endpoint、credential／SecretRef、内部 target、完整发布快照或
ModelAccessProof。** 读取模型元数据不表示可以读取凭据；连接配置的编辑继续走既有受限配置管理路径。

版本列表默认 current=1、size=20，size 最大 100；保留原存储按 binding key 的稳定顺序，不承诺版本字符串的时间先后或 latest 解析。

关联示例（需 `Idempotency-Key`）：

```json
{
  "scope": {"tenantId": "<tenant>", "workspaceId": "<workspace>"},
  "resourceId": "<create 返回的资源 ID>",
  "expectedVersion": 0,
  "binding": {"type": "binding", "id": "text", "version": "v1"},
  "source": "DATABASE",
  "catalogRevision": 1
}
```

## 公共资源 ACL

前缀：`/arte/resource/access`。统一 target 结构为：

```json
{
  "scope": {"tenantId": "<tenant>", "workspaceId": "<workspace>"},
  "resourceType": "model",
  "resourceId": "<稳定管理资源 ID>"
}
```

resourceType 和 resourceId 只是定位；资源租户由 scope 派生，真实归属和领域状态仍由服务端重新核验。不接受客户端资源描述或通过指定另一个空间扩大权限。

| 端点                      | 请求体                                                         | data                                        |
|---------------------------|----------------------------------------------------------------|---------------------------------------------|
| /getResourceAccess        | target                                                         | resource、ownership、visibility、aclVersion |
| /listResourceGrants       | target、current 可选、size 可选                                | PageResult<Grant>                           |
| /grantResourceAccess      | target、expectedAclVersion、recipient、actions、expiresAt 可选 | CommandReceipt<GrantResult>                 |
| /revokeResourceGrant      | target、grantId、expectedAclVersion、expectedGrantVersion      | CommandReceipt<Void>                        |
| /changeResourceVisibility | target、expectedAclVersion、visibility、sharedGrants           | CommandReceipt<Access>                      |
| /checkResourceAccess      | target、action                                                 | target、action、allowed、reason、checkedAt  |
| /batchCheckResourceAccess | requests：1～100 项检查请求                                    | 逐项 CheckResult，保留顺序及重复项          |

`getResourceAccess` 与授权列表要求当前 `resource:access:manage`。新增 core `ResourceAccessService.getAccess`
在同一治理事务取得当前访问模式和 ACL 版本，供客户端刷新 CAS；HTTP 不直接读取 Store。列表默认 1／20，size 最大 100，公开 Grant
包含 id、resource、显式 recipient 类型、action、state、expiresAt、source、version，不返回内部成员代次、issuedBy、sourceOperationId
或授权版本证明。

recipient 请求只接受：

| kind       | 额外字段                          | 语义                                                 |
|------------|-----------------------------------|------------------------------------------------------|
| SUBJECT    | subjectId                         | 向当前有效成员的稳定主体 ID 授权，不能使用登录名代替 |
| SPACE_ROLE | role：OWNER／ADMIN／MEMBER／GUEST | 向 target.scope 内精确匹配角色授权，不隐式继承       |
| SPACE      | 无                                | 向 target.scope 全部当前有效成员授权                 |

角色和空间 recipient 的 scope 由 target.scope 派生，请求不能另传空间或成员代次。互斥字段、未知 kind／role、重复动作拒绝。actions
必须为 1～100 个明确动作，不接受通配符；调用者仍须持有待授动作和相应 ACL 管理／分享资格。权限、分享政策、当前成员资格和期限均由公共服务在原事务内检查。

visibility 为 EXPLICIT 或 SPACE_SHARED。SPACE_SHARED 必须提供明确 sharedGrants，每项包含 recipient、actions 和可选
expiresAt，不能使用 SUBJECT；EXPLICIT 必须提交空列表。关闭共享只撤销模式维护的来源，不删除独立主体授权。revoke 同样只撤销指定
grantId，保留其他独立允许来源。

直接授权示例（需 `Idempotency-Key`）：

```json
{
  "target": {
    "scope": {"tenantId": "<tenant>", "workspaceId": "<workspace>"},
    "resourceType": "model",
    "resourceId": "<model resourceId>"
  },
  "expectedAclVersion": 0,
  "recipient": {"kind": "SUBJECT", "subjectId": "<稳定主体 ID>"},
  "actions": ["model:read", "model:use"]
}
```

先调用 getResourceAccess 取得当前 aclVersion，后续写入不得靠猜测递增。expectedGrantVersion 取公开授权记录的
version。权限检查只返回展示结果；普通拒绝为 200／allowed=false，成员资格缺失隐藏为 NOT_FOUND，权威故障为整批
503，不返回残缺允许项。检查不暴露 AuthorizationVersions，也不授予最终写入资格。

## 显式切换与兼容

getModelAclScope 和 updateModelAclScope 同时要求当前 `workspace:policy:write` 与当前平台 `aiConfiguration:manage`
／admin。模型读者、普通分享者或仅持有 model:publish 的用户不能读取／更新空间切换清单。

未切换时查询返回 migrated=false、version=0、source=null、bindings=[]，不建行。updateModelAclScope 首次 expectedVersion=0
显式激活；后续携带当前清单版本，完整替换精确绑定集合（最多 256，重复拒绝）。source 必须匹配当前部署模式且不能修改已有清单来源。每个绑定必须已有同主空间的
PUBLISHED 模型映射；DATABASE 还须在启用的空间 AI 预设内。

```json
{
  "scope": {"tenantId": "<tenant>", "workspaceId": "<workspace>"},
  "expectedVersion": 0,
  "source": "DATABASE",
  "bindings": [{"type": "binding", "id": "text", "version": "v1"}]
}
```

创建、关联、授权、共享或停用不会自动切换空间。未迁移范围继续原 Grant／空间 AI 配置行为；显式切换后，模型 ACL 拒绝不能回退旧
Grant。公共模型的停用由已迁移执行路径复核，未迁移的旧配置仍由原规则控制。清单 bindings=[] 表示拒绝全部，仍为
migrated=true；不提供删除／退回 LEGACY 接口。

迁移清单继续只是运行候选与来源控制，具体用户 model:use 仍由公共 ACL 判定。模型共享不转移执行归属，不授予预算或凭据。模型与清单都不得通过
HTTP 抢占其他空间的精确绑定映射。

原空间／控制面／模型发现和执行请求格式保留；既有 WebAspect 对 platform Controller 仅做方法级日志并保留异常，不记录请求体或
Authentication。旧 getWorkspaceCommandReceipt 保留已有白名单，不开放新模型／ACL schema；本批命令通过原键重试原接口取得当前仍有权查看的回执，不能从
Store 直接导出历史结果。

## 错误、部署与验收

新 Controller 加入现有 PlatformExceptionHandler，同步绑定错误和 Mono 异步错误使用同一 ResultContext。参数错误 400、无效会话
401、权限／政策拒绝 403、不存在或不可见 404、CAS／幂等冲突 409、权威故障／队列过载 503；响应含服务端 X-Trace-Id，不回传
SQL、请求内容、凭据或底层异常消息。安全过滤器在进入 Controller 前的拒绝保持全站原契约。管理状态等其他 409／500 情况继续沿用公共错误映射。

本批不增加 DDL，不修改旧表或业务数据。部署继续按 [第三批说明](MODEL_ACL_EXECUTION.md)先部署四张模型相关表及公共基础表、升级全部
API／Worker 并排空旧二进制，再由有资格的管理者明确激活空间；API 开放不代表已经迁移任何空间。

`ModelManagementHttpIntegrationTest` 使用真实 MVC 绑定、Bean Validation、旧 Web 切面、RBAC、Mapper
和公共／模型应用服务，覆盖全部管理端点、视图去敏、私有／共享／关闭共享、主体／角色、重加入代次、越权与跨空间／租户、停用、精确发布来源、参数／幂等／CAS、并发胜出、回执重新授权、账号生命周期、严格请求字段、批量检查顺序及整批故障、过载、AI
关闭下公共接口独立装配。

`ModelAclHttpRuntimeIntegrationTest` 使用生产 Spring 装配和方法安全，从 HTTP 创建／关联／激活模型，再进入真实聊天受理和原子派发存储；通过
HTTP 撤权验证受理全部回滚、派发没有 Attempt／预算预留、外发前拒绝，并验证未迁移兼容和激活后无旧 Grant 兜底。使用禁止真实网络的模型
Gateway。

验收使用 JDK 21、显式 Mockito javaagent、隔离 H2 与一次性 MySQL 8.4；MySQL
逐用例创建／删除随机数据库。相关旧空间、控制面、身份、异步认证、公共契约和事务门控一并定向回归。未连接业务库、重启实际节点、自动激活空间、进行浏览器／多节点／容量压测或调用真实供应商；这些运行环境事项不由接口测试替代。

最终串行定向回归共 455 项：core 公共契约／空间／治理事务 185 项、AI 控制面 33 项、app 身份／安全／异步适配／空间
HTTP／持久化／ACL／模型及新管理接口 237 项。454 项通过，1 项 MySQL 当前读专项在 H2 跳过，无失败／错误。新增 HTTP 专项 28
项与第三批事务竞争专项 16 项在一次性 MySQL 8.4 共 44 项全部通过，无跳过；其中包含 H2 跳过的迁移行幻读竞争。临时随机库由用例清理，容器验收后删除。
`git diff --check` 通过。
