# ARTE 配置管理优化 C01 基线与实施契约

日期：2026-11-05。状态：C01 完成；C02～C12 尚未实施。

本文落实[实施计划](ARTE%20配置管理优化实施计划.md)的 C01。第 1～2 节记录当前事实，第 3～10 节为已采纳方向的实施契约，第 11～12
节记录验收与交接。拟新增接口和规则均明确标为目标，不代表已经可调用。

## 1. 核对范围与工作区基线

- 工作区：`/Users/cylj126/Workspace/my_project/arte_dev`；分支 `dev_refactor`；核对起点 HEAD：
  `5afef9b045e876ab4ac843bc7598fa606ce05f45`。实际依据为当时工作区源码，不能仅用 HEAD 重建未提交状态。
- 检查祖先目录及仓库内 `AGENTS.md`，本次未发现适用文件；未使用子代理。
- 开始时有 30 条工作区状态记录：既有用户手册修改、一组暂存后删除的旧持久化/配置/测试文件、
  `backend/profile/common.properties` 修改，以及上轮新建的实施计划。保留这些状态，不恢复、不清理、不暂存或提交。
- 未读取配置文件中的凭据值；未连接业务数据库、ES、Redis 或外部模型供应商。
- 本批交付范围为本文及实施计划状态/差距修订，不修改业务代码、权限安装、schema 或业务数据。
- 顶层设计和既有模型配置文档用于确定领域边界；“已实现”只根据当前默认服务、HTTP 和前端链路判断，不根据文档愿景推定。

### 1.1 关键源码证据

| 编号 | 当前源码/设计                                                                                                                                                                                                                                                                                                                                                                                                                 | 用途                                                               |
|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| S01  | [租户入口](frontend/src/pages/Environment/Tenants/index.tsx)、[政策页](frontend/src/pages/Workspace/Policies/index.tsx)                                                                                                                                                                                                                                                                                                       | 当前租户页复用政策页、公共空间选择及两类政策混排                   |
| S02  | [空间页](frontend/src/pages/Environment/Workspaces/index.tsx)、[详情表单](frontend/src/features/workspace/WorkspaceDetailForm.tsx)、[政策弹窗](frontend/src/features/workspace/WorkspacePolicyDialog.tsx)                                                                                                                                                                                                                     | 当前主从布局、局部目标、无 URL 定位、混合表单及政策弹窗            |
| S03  | [模型页](frontend/src/pages/AI/Models/index.tsx)、[模型详情](frontend/src/features/ainew/ModelManager.tsx)                                                                                                                                                                                                                                                                                                                    | 管理空间、三页签、平台资格展示缺口及使用跳转                       |
| S04  | [空间目录状态](frontend/src/features/workspace/useWorkspace.ts)、[政策候选](frontend/src/features/workspace/usePolicyDirectory.ts)                                                                                                                                                                                                                                                                                            | 公共/治理目录、自动初始化、最近空间及目录候选边界                  |
| S05  | [AI 导航](frontend/src/features/ainew/aiNavigation.ts)、[配置通知](frontend/src/features/workspace/aiConfigurationEvents.ts)                                                                                                                                                                                                                                                                                                  | 路由定位意图、离开阻止和失效通知                                   |
| S06  | [工作空间 HTTP](backend/arte-app/src/main/java/com/arte/app/web/controller/platform/WorkspaceController.java)、[响应式适配](backend/arte-app/src/main/java/com/arte/app/platform/workspace/ReactiveWorkspaceOperations.java)                                                                                                                                                                                                  | 当前真实空间/政策/偏好/回执端点                                    |
| S07  | [租户 API](backend/arte-core/src/main/java/com/arte/core/api/workspace/TenantService.java)、[成员 API](backend/arte-core/src/main/java/com/arte/core/api/workspace/MembershipService.java)、[默认服务装配](backend/arte-core/src/main/java/com/arte/core/application/workspace/DefaultWorkspaceServices.java)、[应用装配](backend/arte-app/src/main/java/com/arte/app/platform/workspace/PlatformWorkspaceConfiguration.java) | 接口契约与默认实现/装配的差距                                      |
| S08  | [角色预设](backend/arte-core/src/main/java/com/arte/core/application/access/DefaultRolePermissions.java)、[动作登记](backend/arte-core/src/main/java/com/arte/core/application/access/DefaultActionRegistry.java)、[展示资格 HTTP](backend/arte-app/src/main/java/com/arte/app/web/controller/platform/WorkspaceAccessController.java)                                                                                        | 角色与动作矩阵、展示资格不授予权限                                 |
| S09  | [政策服务](backend/arte-core/src/main/java/com/arte/core/application/workspace/DefaultWorkspacePolicyService.java)、[政策字段](frontend/src/features/workspace/PolicyRulesFields.tsx)                                                                                                                                                                                                                                         | 完整政策的治理资格、保存时上限检查、当前无效果预览接口             |
| S10  | [AI 治理服务](backend/arte-app/src/main/java/com/arte/app/platform/ai/WorkspaceAiGovernanceService.java)、[AI HTTP](backend/arte-app/src/main/java/com/arte/app/web/controller/platform/WorkspaceAiConfigurationController.java)、[前端契约](frontend/src/services/platform/ai.ts)                                                                                                                                            | 模型候选、独立规则、上限与提额资格                                 |
| S11  | [数据库 AI 控制](backend/arte-app/src/main/java/com/arte/app/platform/ai/DatabaseAiControlService.java)                                                                                                                                                                                                                                                                                                                       | 当前 RBAC 的 `admin` / `aiConfiguration:manage` 判定、已有账户调整 |
| S12  | [模型列表 HTTP](backend/arte-app/src/main/java/com/arte/app/web/controller/platform/ModelDraftController.java)、[请求 DTO](backend/arte-app/src/main/java/com/arte/app/web/platform/ModelDraftPayload.java)、[模型服务](frontend/src/services/platform/model.ts)                                                                                                                                                              | 当前列表只支持 scope/cursor/size，不支持名称/状态/归属筛选         |
| S13  | [使用准备 DTO](backend/arte-ai/src/main/java/com/arte/ainew/pojo/model/ModelUsagePreparation.java)、[使用准备 UI](frontend/src/features/ainew/ModelUsagePreparationPanel.tsx)、[模型授权 HTTP](backend/arte-app/src/main/java/com/arte/app/web/controller/platform/ModelSharingController.java)                                                                                                                               | 当前单一阻塞原因及模型授权专用成员候选，不是通用成员治理           |
| S14  | [租户存储](backend/arte-core/src/main/java/com/arte/core/spi/workspace/TenantStore.java)、[租户成员存储](backend/arte-core/src/main/java/com/arte/core/spi/workspace/TenantMembershipStore.java)、[邀请存储](backend/arte-core/src/main/java/com/arte/core/spi/workspace/InvitationStore.java)、[组织创建政策](backend/arte-core/src/main/java/com/arte/core/spi/workspace/TenantProvisioningPolicy.java)                     | 独立租户候选扫描、邀请持久化及组织创建资格缺口                     |
| S15  | [回执查询](backend/arte-core/src/main/java/com/arte/core/application/workspace/DefaultCommandReceiptQueryService.java)、[回执序列化](backend/arte-app/src/main/java/com/arte/app/platform/persistence/PlatformJson.java)                                                                                                                                                                                                      | 回执只支持既有命令类型，不能直接查询所有未来命令                   |
| S16  | [基础 DDL](backend/arte-app/scripts/arte-platform-workspace-ddl-mysql.sql)、[顶层设计](ARTE%20顶层需求及设计.md)、[模型配置设计](ARTE%20AI%20模型配置需求及设计.md)                                                                                                                                                                                                                                                           | 已有 schema 与预定义但尚未交付的组织/邀请能力边界                  |

## 2. 当前能力与差距

“有接口”不等于“有实现”；“有存储”不等于“治理命令可用”。下表的 HTTP 均为完整 `/arte` 前缀下的路径。

| 能力                             | 核心/服务当前状态                                                                                                          | HTTP 当前状态                                                                                      | 前端当前状态                                             | 后续批次                                   |
|----------------------------------|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|----------------------------------------------------------|--------------------------------------------|
| 本人个人租户/默认空间初始化      | 已有默认实现及持久化                                                                                                       | `/workspace/initializePersonalWorkspace` 已有                                                      | 已接入；公共目录在偏好缺失时可触发初始化                 | C02 保留使用侧初始化，配置读取移除该副作用 |
| 空间浏览/读取/创建/改名          | 已有默认实现                                                                                                               | `/workspace/browseAccessibleWorkspaces`、`getWorkspace`、`createWorkspace`、`renameWorkspace` 已有 | 已接入；浏览仅名称搜索，无租户过滤                       | C03、C05                                   |
| 空间偏好和当前使用选择           | 偏好服务已有；当前选择在浏览器                                                                                             | 读写偏好端点已有                                                                                   | 公共目录与配置目录分离不彻底                             | C02                                        |
| 独立租户目录                     | 没有按主体浏览租户的核心查询/SPI                                                                                           | 没有                                                                                               | 从已加载空间归纳租户                                     | C03                                        |
| 租户详情/概览                    | `TenantView` 和 `TenantService.getTenant` 为已有契约，未找到默认租户服务实现/装配                                          | 没有独立详情端点                                                                                   | 仅政策响应附带名称/类型                                  | C03、C04                                   |
| 租户基本信息维护                 | `Tenant` 有名称/版本；无专用更新命令或动作                                                                                 | 没有                                                                                               | 没有                                                     | C10                                        |
| 组织租户创建                     | `createOrganization`、命令与 `TenantProvisioningPolicy` 为契约；未找到默认服务和生产资格装配                               | 没有                                                                                               | 没有                                                     | C10，不能仅补控制器                        |
| 租户成员治理                     | `TenantService` 有列表、改角色、移除和交接契约；成员存储已有，应用服务未接通                                               | 无通用治理端点                                                                                     | 无通用治理页                                             | C11-A                                      |
| 租户邀请/接受                    | 无独立租户邀请对象、API、存储和默认服务                                                                                    | 没有                                                                                               | 没有                                                     | C11-A 新增，不伪造空间来承载               |
| 空间成员/邀请                    | `MembershipService`、命令、`WorkspaceInvitation`、`InvitationStore` 为契约；未找到默认成员服务、app 邀请存储实现或安装 DDL | 没有通用成员/邀请端点                                                                              | 没有通用管理/接收流程                                    | C11-B 全链路补齐                           |
| 租户/空间政策                    | 已有默认读写实现；读取完整政策也要求对应政策治理资格                                                                       | `getTenantPolicy`、`getWorkspacePolicy`、两类 update 已有                                          | 租户页两个页签；空间页混合政策弹窗                       | C04、C05、C09                              |
| 政策效果/草稿预览                | 保存时有上限检查；无对照/预览查询契约                                                                                      | 没有                                                                                               | 只展示部分上限与本地字段差异                             | C09                                        |
| 平台连接、方案、默认模板         | 数据库控制服务已有，独立平台 RBAC                                                                                          | `/workspace/ai/getConnections`、`getModelPlans`、发布/启停/默认模板端点已有                        | 已接入；父页固定显示三页签，无单独安全能力查询           | C06                                        |
| 模型创建、草稿、凭据、发布、授权 | 已有真实服务和精确版本门控                                                                                                 | `/ai-new/model-resource`（含 publishModel）、`model-draft`、`model-sharing` 下已有对应端点         | 已接入；详情较长                                         | C06、C07                                   |
| 模型列表筛选                     | 只按完整管理 scope 和游标                                                                                                  | `listModelResources(scope,cursor,size)`                                                            | 名称/状态/归属服务端筛选尚未提供                         | C06                                        |
| 空间 AI 与预算规则               | 已有独立保存及 canManage/canRaiseBudget/approvedCaps                                                                       | `/workspace/ai` 的读取、首次准备、模型选择、规则端点已有                                           | 同一长表单，互斥编辑，费率主要显示 ID                    | C05、C08                                   |
| 本人使用准备与诊断               | 已有准备服务；DTO 为状态、单一 reason、预算引用、approvedLimit、allowedActions                                             | `getModelUsagePreparation`、`prepareModelUsage` 已有                                               | 已有单原因提示，无完整检查清单                           | C07                                        |
| 跨页模型→空间定位                | 生成路由及精确版本核验辅助函数已有                                                                                         | 无新增接口才能定位的硬性前置                                                                       | 模型页发送参数，空间页不消费，现有测试还固化了“忽略 URL” | C06 修改消费端及旧行为测试                 |
| 历史回执查询                     | 查询器/序列化白名单仅覆盖既有一部分命令                                                                                    | `/workspace/getWorkspaceCommandReceipt` 已有                                                       | 各业务另存原操作并同键确认                               | C02 适配已有覆盖；C10/C11 扩展             |

另外两个具体限制：

1. 模型页虽然把所选管理空间保存在局部 state，仍挂载公共目录；其加载路径可初始化个人空间或回退公共当前空间。C02
   必须同时隔离“加载”和“选择”，不能只改下拉框的 onChange。[S03、S04]
2.
独立治理目录当前先读取本人偏好，缺失即返回，不能据此承载“尚无个人默认关联但有组织资格”的完整目录。新受权目录读取不应依赖个人空间已初始化。[S04]

## 3. 页面职责与对象字段

### 3.1 页面职责

| 页面/区域     | 固定职责                                         | 保存范围                                 | 主要布局/动作                               |
|---------------|--------------------------------------------------|------------------------------------------|---------------------------------------------|
| 租户配置      | 租户概览、受权空间、租户政策、基本信息及成员治理 | tenantId                                 | ViewportPage + MasterDetailLayout；默认概览 |
| 工作空间配置  | 空间概览、AI 模型启用、预算规则、空间政策和成员  | tenantId + workspaceId                   | 同布局；顶部显式进入此空间                  |
| 模型管理      | 固定管理空间中的资源、草稿、凭据、发布版本与授权 | managementScope + resourceId             | 左侧管理空间/服务端筛选；右侧分阶段详情     |
| 平台连接/方案 | 可复用连接、受审方案、费率、默认模板             | 当前平台账号，非所选空间                 | 模型页内按平台资格出现，不增主菜单          |
| 使用条件      | 目标执行空间中的配置条件与本人条件               | executionScope + 精确模型版本 + 当前主体 | 模型详情检查，治理操作跳转空间页            |
| 聊天          | 明确选择与应用、运行参数、本人预算查看/准备      | 当前聊天空间和本人会话                   | 不挂载完整治理编辑器                        |

空间政策编辑器的主要入口为工作空间详情。租户页不复制可写空间政策；租户关联空间提供配置跳转。模型和聊天中的关联视图均不复制空间完整编辑器。

### 3.2 字段语义

| 字段             | 契约                                                                                                |
|------------------|-----------------------------------------------------------------------------------------------------|
| account          | 当前登录账号边界，用于前端上下文/恢复隔离；路由值不能决定服务端身份。与登录账号不一致的任务链接拒绝 |
| tenantId         | 租户页局部目标；只读租户治理不要求选择某个空间                                                      |
| scope            | 工作空间页为配置目标；模型页为固定管理范围；完整空间必须同时有 tenantId/workspaceId                 |
| managementScope  | 模型的固定管理位置，不由列表查询或目标执行空间改变                                                  |
| executionScope   | 检查/准备/执行所用的目标空间；同租户、当前仍可读且逐项核验模型资格                                  |
| chatScope        | 当前使用空间；来源快照仅用于比较，不是许可，也不能被配置页写回                                      |
| model            | `resourceId + binding(type=binding,id,version)`，精确固定引用；禁止 latest 和同名替代               |
| returnTo         | 受控返回意图，限 chat/models/tenants/workspaces；返回对应对象时保留其经核验目标，不接收任意 URL     |
| revision/version | 权威版本用于并发及预览；浏览器失效通知只触发重读，不携带或授予许可                                  |

“个人模型”是产品预设，实际为 WORKSPACE 资源归属 + PERSONAL 凭据；租户模型为 TENANT 归属 + TENANT 凭据。不得新增第三种 USER
归属，不能把 DTO 的 ownershipType 与创建预设 modelKind 混用。[模型配置设计 D04/D05、S12]

## 4. 角色、动作和资格矩阵

### 4.1 租户（默认预设与本轮受权投影）

| 能力                     | OWNER                                 | ADMIN                   | MEMBER                       | GUEST                                      |
|--------------------------|---------------------------------------|-------------------------|------------------------------|--------------------------------------------|
| 独立租户目录/基本摘要    | 本人有效租户                          | 本人有效租户            | 本人有效租户的基本摘要       | 不进入租户独立目录，仅目标空间所需归属标签 |
| 完整租户政策读写         | 有                                    | 有                      | 无                           | 无                                         |
| 创建空间                 | 有                                    | 有                      | 需 membersMayCreateWorkspace | 无                                         |
| 租户成员治理             | 有，OWNER 保护                        | 有，不能更改/授予 OWNER | 无                           | 无                                         |
| 组织 OWNER 交接          | 仅当前 OWNER，目标有效正式成员        | 无                      | 无                           | 无                                         |
| 租户名称维护（新增目标） | 还需独立显式 `tenant:update` 业务资格 | 同左                    | 无                           | 无                                         |
| 组织创建（新增实现）     | 按独立 provisioning 资格              | 同左                    | 同左                         | 同左；不由当前租户角色推导                 |

当前 `tenant:read` 基础动作对有效租户成员返回允许，但顶层设计要求 MEMBER/GUEST 的投影受限。C03 的目录过滤和 DTO
按上表约束，不能直接把 tenant:read=true 解释为完整治理详情权限；不因此修改既有角色预设。

租户名称首轮仅编辑 name（去首尾空白 1～100 字符），不新增描述、归属、托管主体或生命周期编辑。`tenant:update`
是新能力，默认不随程序升级赋予已有角色；C10 用受信资格适配显式授权并叠加
OWNER/ADMIN，登记动作时接通真实服务端判定，不能仅加前端按钮或加入默认允许列表。若实现需要修改公共角色预设，必须另列版本/数据升级，不能在
rolePresetVersion=1 下静默扩权。

个人租户固定托管主体及系统默认空间关系不可编辑，不开放个人租户负责人交接或退出托管关系；个人协作通过空间邀请。组织创建只产生租户、首位
OWNER 和必要政策/回执，不自动创建空间或 AI 账户。组织新租户政策采用版本化受信模板；C10
必须提供配置来源及安装条件，缺失时关闭创建，不能擅用个人模板或猜测默认上限。

### 4.2 工作空间（现有预设保持）

| 能力                   | OWNER                  | ADMIN                                | MEMBER | GUEST                                      |
|------------------------|------------------------|--------------------------------------|--------|--------------------------------------------|
| 已加入空间公开元数据   | 有                     | 有                                   | 有     | 仅目标空间                                 |
| 名称/描述修改          | 有                     | 有                                   | 无     | 无                                         |
| 完整空间政策与 AI 治理 | 有，受租户上限         | 无                                   | 无     | 无                                         |
| 邀请/成员管理          | 有，保护 OWNER         | 仅非治理成员范围，不授予 ADMIN/OWNER | 无     | 无                                         |
| 授予 ADMIN、交接 OWNER | 有，默认空间等另受保护 | 无                                   | 无     | 无                                         |
| 普通 AI 使用基础门槛   | 有                     | 有                                   | 有     | 开关只是上限，当前预设不据此自动获得使用权 |

这些均受当前身份、成员状态、租户/空间生命周期约束；最终模型调用另查资源
ACL、允许执行空间、凭据用途、模型版本和预算。租户角色不隐式成为空间角色，平台管理员也不因此取得各空间内容或配置资格。

### 4.3 模型、平台与菜单

- 模型资源维护操作按服务端返回的动作与精确上下文判断，创建下拉继续复用 `getModelCreationOptions`
  。租户模型创建当前还检查租户政策治理及独立创建资格，不以空间负责人身份替代。
- 模型授权专用 `listTenantModelMembers` 不能复用为通用成员目录：权限、字段及用途均不同。
- 平台配置资格沿用当前受信 RBAC 的 `admin` 或 `aiConfiguration:manage`；新增安全能力端点用于展示，业务端点每次继续查当前资格。平台提额也不能代替目标空间治理资格。
- 复用 EnvironmentTenants、EnvironmentWorkspaces、EnvironmentModels 等菜单。菜单控制入口，业务端点控制动作；新业务动作的安装与菜单分配分别说明，不批量自动赋权。

## 5. 局部状态、导航与恢复契约

### 5.1 局部目标与读取

1. 配置目录使用独立受权读取；必要时只读参考已存在的聊天当前空间作为初始候选，不挂载会初始化/回退公共空间的加载链。
2. 租户页目标不要求 workspaceId。C02 在 C03 之前可暂复用现有空间候选推出租户，但仅保留本地 tenantId/space
   target，并把候选不完整明确作为过渡限制；C03/C04 替换为独立目录。
3. 浏览/搜索/查看/重新读取不保存 startupScope、lastScope 或公共 currentWorkspace，不派发 `arte-workspace-selected`
   ，不初始化个人关联或预算。
4. “进入此空间”完成草稿处理后走已有公共选择服务，重新核验，再更新当前使用状态及既有最近空间规则；未成功切换不得提示成功。收藏/启动设置继续为显式偏好命令。
5. 账号切换卸载旧上下文，读取响应按账号、目标、请求代次校验；跨标签配置通知仅标记失效和重读，不覆盖草稿。

### 5.2 路由与兼容

- 保持既有 `tenantId/workspaceId`、`management-*`、`execution-*`、`chat-*`、`resourceId/bindingId/bindingVersion` 字段。租户页只用
  tenantId；空间页用完整 scope；模型页 scope 始终是管理空间。
- 新目标页签采用 overview/ai/policy/members（members 只在能力接通后开放）；模型页内部兼容现有
  models/connections/plans。显示名称调整不要求修改所有既有 tab 值。
- `account` 若给出必须等于当前账号，缺失仅作为当前登录下的普通定位；所有引用由服务端重查。重复参数、部分空间/模型引用、不合法返回意图拒绝，不默默猜测。
- 菜单无参数进入保留目录空选择；有明确目标的任务链接或刷新，经核验后恢复详情和页签，不自动写配置。模型携带到空间页的精确版本只做定位与提示。
- 模型页旧 `tab=workspace-ai` 转新空间定位；`/AI/Models`、`/AI/Connections`、`/AI/ModelPlans`、`/AI/ModelSharing` 保持兼容。
  `/Workspace/Policies` 按旧上下文适配租户政策/空间政策，不再执行公共空间选择；参数不足时显示局部选择。
- 返回必须再查当前范围；聊天源空间已变化时说明并允许用户明确选择进入目标，不能用旧快照恢复当前聊天。返回聊天后仍重新发现并显式应用精确版本。

### 5.3 状态分类

| 状态                 | 可执行处理                                                   | 离开原则                                         |
|----------------------|--------------------------------------------------------------|--------------------------------------------------|
| clean                | 查询/编辑/导航                                               | 可正常离开                                       |
| dirty                | 保存并继续、明确放弃、留在本页                               | 保存校验失败即停止；浏览器刷新用原生提示         |
| submitting           | 展示具体操作进度                                             | 禁止重复提交和丢失原操作，不以 abort 推定回滚    |
| pending/uncertain    | 优先查询已支持的历史回执；不支持时用户明确触发原请求原键确认 | 只有受权可恢复路径可靠时才允许离开；不得换键重发 |
| conflict             | 保留草稿，读取新版本并展示差异，明确重新提交                 | 不把冲突当结果不确定，不自动覆盖                 |
| recovery-unavailable | 明确受影响操作和诊断，恢复记录可靠性后再处理                 | 不以清空原记录作为通用解锁方案                   |

公共 guard 以账号、局部范围和表单注册能力协调。多个普通草稿只在版本依赖要求下串行保存并更新版本；不能用“保存并继续”隐式批准预算、发布模型、交接负责人或消耗使用调用。
结果已确认但读回失败时保留已确认标记，只重读；既有回执查询白名单外的 AI/模型命令不能擅自调用空间回执接口冒充支持。

## 6. API 变更清单（目标契约，均未在 C01 实现）

继续沿用 POST、现有 ResultContext/CommandReceipt、可信认证适配、严格 DTO
与幂等命令约定；以下路径为本轮约定的目标命名。新增能力分批安装，旧接口字段扩展保持兼容，新增筛选字段缺省不改变旧行为。

| 批次    | 目标接口/变更                                             | 请求要点                                                                                                                                                                                                                                | 返回/关键约束                                                                                               |
|---------|-----------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| C03     | `/arte/workspace/browseAccessibleTenants`（新增）         | cursor、size 1～100/默认20、search≤100、可选 type                                                                                                                                                                                       | records 为受权 TenantSummary，nextCursor、exhausted；无空间也可返回，不默认总数                             |
| C03     | `/arte/workspace/getTenant`（新增适配及实现）             | tenantId                                                                                                                                                                                                                                | id/name/type/state/currentRole/version，安全 viewLevel 与 actions；MEMBER 只基本摘要                        |
| C03     | `/arte/workspace/getTenantCreationOptions`（新增）        | 无客户端主体/租户角色                                                                                                                                                                                                                   | organizationAllowed、能力是否开放、安全原因；仅展示资格，提交重查                                           |
| C03     | 扩展 `browseAccessibleWorkspaces`                         | 可选 tenantId；原 cursor/size/search 保留                                                                                                                                                                                               | 服务端按租户限制候选并逐项授权；不返回仅有租户治理权但未加入的其他空间                                      |
| C06     | `/arte/workspace/ai/getConfigurationCapabilities`（新增） | 无空间、无客户端资格布尔                                                                                                                                                                                                                | 功能开放状态与 manageConnections/managePlans/manageDefaultTemplate；无目录内容/密钥                         |
| C06     | 扩展 `listModelResources`                                 | 固定 scope，新增 search、modelKind、state 可选条件                                                                                                                                                                                      | 在服务端扫描/授权/过滤，游标绑定完整筛选；继续支持空页有后续                                                |
| C07     | `/arte/ai-new/model-resource/getModelUsageChecks`（新增） | scope 为执行空间，resourceId、精确 binding                                                                                                                                                                                              | 空间条件和本人条件两组检查；状态 PASS/BLOCKED/UNKNOWN/NOT_APPLICABLE，安全原因及 allowedActions，无业务写入 |
| C08     | 扩展 `listBudgetRateOptions`                              | scope、cursor；可选精确 rate 定位                                                                                                                                                                                                       | 返回精确费率、币种、单价、安全显示名及可读关联；关联分页/完整性标记不能省略                                 |
| C08     | `/arte/workspace/ai/previewBudgetRuleChange`（新增）      | scope、当前 workspace/rule 版本、完整拟保存 rules                                                                                                                                                                                       | 差异、可信额度上限及安全影响说明；不修改账户；不返回其他主体账本/额度                                       |
| C09     | `/arte/workspace/getWorkspacePolicyContext`（新增）       | 完整 scope                                                                                                                                                                                                                              | 当前空间规则、可公开租户约束、逐项政策效果、冲突、权威版本；空间政策治理资格，不要求租户完整政策读取权      |
| C09     | `/arte/workspace/previewPolicyChange`（新增）             | kind、tenantId 或 scope、当前两级版本、完整拟保存规则                                                                                                                                                                                   | 规范化差异、超限项/政策效果、依据版本；租户草稿不返回无资格空间列表或虚构精确影响数                         |
| C10     | `/arte/workspace/updateTenant`（新增）                    | tenantId、expectedTenantVersion、name，Idempotency-Key                                                                                                                                                                                  | 原子 CAS、审计/事件及安全 TenantView 回执；不编辑类型/托管主体/状态                                         |
| C10     | `/arte/workspace/createOrganization`（新增适配及实现）    | name，Idempotency-Key                                                                                                                                                                                                                   | 首位 OWNER/必要政策及租户回执；缺 provisioning/template 关闭，不隐式创建空间                                |
| C10/C11 | 扩展回执序列化/查询                                       | 新命令种类及 schema、operationId                                                                                                                                                                                                        | 原操作者、当前结果可见与命令特定资格重查；不只扩大序列化白名单                                              |
| C11-A   | 租户成员端点组（新增）                                    | `listTenantMembers`、`changeTenantMemberRole`、`removeTenantMember`、`transferTenantOwner`；tenantId 与成员/租户预期版本                                                                                                                | 不复用模型专用成员列表；OWNER 交接独立命令，其他角色更新禁止修改 OWNER                                      |
| C11-A   | 租户邀请端点组（新增完整能力）                            | `inviteTenantMember`、`listTenantInvitations`、`revokeTenantInvitation`、`acceptTenantInvitation`                                                                                                                                       | 组织正式 MEMBER 邀请；接受者来自 actor，不从客户端读取；不自动加入空间                                      |
| C11-B   | 空间成员/邀请端点组（新增适配及实现）                     | `listWorkspaceMembers`、`inviteWorkspaceMember`、`listWorkspaceInvitations`、`revokeWorkspaceInvitation`、`acceptWorkspaceInvitation`、`changeWorkspaceMemberRole`、`removeWorkspaceMember`、`leaveWorkspace`、`transferWorkspaceOwner` | 使用已有命令语义；租户/空间/成员/邀请锁、版本、回执同事务                                                   |
| C11     | `/arte/workspace/findInvitationTarget`（新增受限查询）    | 完整租户/空间上下文、精确账号标识                                                                                                                                                                                                       | 先核验邀请资格，仅最小目标展示和稳定 subjectId；不开放全站账户枚举，不信任前端自报身份                      |

端点组均在 `/arte/workspace/` 下，除特殊查询外写入携带 Idempotency-Key。已有核心成员命令继续复用，tenant-only 邀请新增独立命令，不能混用
workspaceId 为空的现有 WorkspaceInvitation。

### 6.1 目录和资格细则

- TenantSummary 至少含 id/name/type/state/currentRole/version/isPersonal；isPersonal
  仅表示当前主体本人托管租户，不通过已加载空间推定。首轮独立目录只返回当前有效正式成员可读的 ACTIVE 租户，GUEST
  归属标签由已加入空间的安全读取提供。
- 租户与空间角色使用不同领域类型。现有前端 `workspaceTypes.ts` 的 TenantView.currentRole 复用 WorkspaceRole，C03 新 DTO
  改用独立 TenantRole，不因名称/数值相同而互换治理语义。
- 目录游标是不透明导航状态，绑定当前主体、目录类型和规范化查询。服务端重新授权；空页有游标继续加载，游标过期明确刷新；不能把条数不足当作结束。
- 租户候选须新增按主体扫描 SPI/查询及必要索引，不能前端遍历全站租户或空间后探测。资格结果仅作展示，提交仍重查。
- 能力状态区分未开放、无资格、权威不可用；权限服务失败不能缓存为“永久无权”，更不能当作允许。缺个人偏好不阻断组织目录。
- 当前 HTTP 访问判断支持租户根与空间根，不能据此代替模型资源 ACL、凭据消费资格或平台配置资格。

### 6.2 错误语义

沿用现有错误枚举及 HTTP 映射：输入不合法、未认证、无动作资格、资源不可见、版本冲突、能力未开放、权威不可用分别处理。
不可见目标不泄露名称/状态；409 区分版本与幂等冲突；超时/5xx 不推定写入失败；查询失败不伪装为空目录。新增命令与回执须验证身份、目标、规范化输入、schema
和当前读取资格。

## 7. 模型、费率和预算展示/保存语义

1. 空间 AI 页包含总开关/摘要、启用模型表、精确费率规则表。模型表的预算入口定位费率规则，同费率只编辑一条规则。
2. 模型候选展示是否启用及精确版本；“没有本人模型使用权”不应阻断合法负责人维护受权可读配置，但诊断不得因此暴露凭据或授权细节。
3. 规则 key 为完整精确 rate（type/id/version）；模型实例与规则不是一对一。费率单价由权威定义读取，名称仅用于展示。
4. 规则额度是匹配主体账户的批准限额，不是所有成员共用总池，不按模型额外发放；已有 held/charged 保留。不同币种分别显示，不自动换汇。
5. 预览为完整拟保存集合的差异，包括撤销规则；同费率有多个可读关联模型时明确列出。关联不完整标注“更多关联未加载/无权展示”，不能标为“无关联”。
6. 未新增独立审计权限时，默认只显示安全影响类别：调整已有匹配账户额度、保留费用/占用、撤销规则阻止新使用；不显示其他主体账户数量、身份、余额或账本。
7. 模型开关/选择与规则保存仍为独立命令；移除模型不删规则，规则撤销不删历史账户。每次成功重读并推进并发版本，版本变化后旧预览失效。
8. 使用准备仅本人显式触发；空间页面不为所有成员建账。“验证并发布”的界面流程不能跳过依赖重查、精确方案或验证有效期。

## 8. 政策效果和预览语义

- 当前空间规则为独立保存快照，首次创建可采用模板；不增加“自动继承/跟随租户”开关，不把历史模板当当前许可。
- 普通布尔条件按两层 AND 展示政策效果；协议、供应商、主机、资源类型、分享目标按两层允许集合交集说明。额外角色还受允许资源类型和基础角色规则约束，不能简单把两个角色列表拼接当权限结果。
- 受权编辑可选项优先来自目录；租户未允许的选项显示受限原因，不允许保存扩大范围。当前运行效果与草稿预期效果使用不同标签。
- 租户收紧可能使已有空间设置超出上限；显示保存值与当前冲突/有效限制，不静默重写空间。C09 的效果接口必须复用或抽取真实服务端检查规则；未能确认的组合返回
  UNKNOWN，不用前端交集结果伪造完整运行资格。
- 租户政策对照不公开部署出口 allowlist、其他主体授权或凭据。政策检查通过仍需模型、凭据、执行范围、预算、部署出口等条件；分别显示，不合并为全局
  READY。
- 预览携带依据的租户、政策、空间版本；保存时重新验证并发与当前资格。预览读取不初始化、保存、授权或创建命令回执。

## 9. 成员与邀请目标契约

- 组织租户新成员使用 tenant-only 邀请：现有有效账户受邀成为正式 MEMBER，ADMIN/OWNER 由独立受权治理操作处理。接受不会加入任何空间。
- 个人租户的外部协作使用空间邀请；不开放个人租户 OWNER 转交或 tenant-only 组织成员邀请。
- 空间邀请沿用现有约定：仅 MEMBER/GUEST，默认 7 天、最长 30 天；接受者必须是目标有效主体。组织 OWNER 交接目标须正式租户成员，空间
  OWNER 交接受个人默认空间和租户政策约束。
- 空间接受邀请在一个事务内重查身份、邀请、有效期、邀请者当前资格、租户和空间状态，必要时建立受限租户成员与空间成员；受限租户资格不开放其他空间。
- 已有有效成员接受邀请返回 ALREADY_MEMBER 并保留原角色；旧已消费邀请不能重新激活成员。新邀请重新加入增加成员
  generation，旧资源授权和凭据用途不得自动恢复。
- 租户成员撤销使依赖空间资格失效，不自动降为访客；移除某一个空间不能解除其他仍有效空间关系。OWNER
  不经普通角色修改/移除，交接原子更新双方角色与版本。
- 受邀用户从本人的邀请列表显式接受；管理员从当前目标成员/邀请页管理。展示目标查询必须核验邀请资格，稳定 subjectId
  不从任意登录名创建新账号。
- 邀请、角色、移除及交接都使用原键确认、当前结果可见性与审计。对外邮件/短信发送不属于本轮必需能力；应用内邀请列表本身必须真实可用。

## 10. 数据、权限和兼容变更

| 范围                       | 目标/约束                                                                                                             |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------|
| C02/C04/C05/C06 页面和状态 | 优先复用恢复键及已保存原操作；不能因搬入口丢失待确认记录。目标/来源参数只存安全定位，不存密钥或预算许可               |
| C03 租户目录               | 增加主体候选扫描能力；必要索引以 C03 实际 SQL/执行计划为准，提供幂等安装/核对脚本，不在 C01 执行                      |
| C06 列表筛选和能力         | 扩展游标绑定条件，保留原缺省查询；平台资格新查询只读，不新增平台管理权限                                              |
| C07/C08/C09 投影和预览     | 优先新 DTO/查询，不新增业务主表或持久化 READY；预览不产生回执或业务更新                                               |
| C10 租户维护/创建          | 默认复用 tenant/member/policy 表；补名称更新动作、受信显式资格和组织初始政策来源；新命令 schema/回执查询/事件完整安装 |
| C11 租户邀请               | 新 tenant-only 邀请领域对象、存储和表；状态 PENDING/ACCEPTED/REVOKED/EXPIRED、目标/期限/版本及必要索引                |
| C11 空间邀请               | 既有对象/SPI 不代表已有表；补 app 持久化、安装 DDL、成员/邀请默认服务与生产装配                                       |
| C11 成员                   | 复用两级成员表及 generation；唯一 OWNER 和跨记录一致性在既有治理锁/事务内保证，不用前端控件保证                       |
| 菜单和权限                 | 复用三个入口；新增业务动作单独声明和显式分配，不自动给已有角色扩权。生产菜单缓存核对与代码部署分别记录                |
| 回滚/数据升级              | 旧 schema、命令回执和恢复键兼容单独说明；邀请等数据写入后不能把删表当回滚；本轮不执行 reset 或旧数据清理              |

对旧模型配置、ACL、凭据和费率契约保持兼容；查询状态不得静默切换到最新版本。新增动作若触及角色预设/政策 schema
需明确升级与兼容检查，不顺带提高版本后假定旧数据可用。

## 11. 后续批次验收场景

下表是后续必须实现的验收要求，除第 12 节的现状回归外均未执行目标功能验收。

| ID   | 场景/预期                                                                                  | 负责批次      |
|------|--------------------------------------------------------------------------------------------|---------------|
| CA01 | 聊天 A 中浏览/编辑租户、空间 B、管理空间 C，公共当前空间/最近/启动均不变                   | C02           |
| CA02 | 配置目录加载时个人偏好不存在，仍不初始化；有组织资格可按新目录查询                         | C02、C03      |
| CA03 | 只有明确进入空间才切换；切换失败或保存失败不显示成功、不继续导航                           | C02、C05      |
| CA04 | 普通草稿可保存继续/放弃/取消；多个有版本依赖的草稿按序保存；发布和预算批准不被当作普通保存 | C02           |
| CA05 | 结果未知原键确认；已确认但读回失败只重读；恢复异常不清空记录解锁                           | C02           |
| CA06 | 无空间的有效组织租户可见；GUEST 不获得独立租户目录或跨空间发现                             | C03、C04      |
| CA07 | 租户/空间目录空页带游标可续扫；过滤绑定游标；撤权与权威异常不泄露/不伪装为空               | C03           |
| CA08 | MEMBER 可看基本租户归属；OWNER/ADMIN 完整治理；无政策权不阻断其他已获权页签                | C03、C04      |
| CA09 | 空间 ADMIN 能编辑元信息，不能编辑政策/AI；租户 OWNER 不自动取得空间权限                    | C05           |
| CA10 | 平台页签无空间也可按资格使用；切管理空间不改变平台草稿；无平台资格不触发读取完整平台目录   | C06           |
| CA11 | 模型名称/归属/状态筛选在服务端作用于完整候选，不只过滤第一页                               | C06           |
| CA12 | 模型 B → 执行空间 C 的精确版本定位 → 返回模型 B；聊天 A 始终不变                           | C06           |
| CA13 | 直接菜单为空选择，任务链接/刷新恢复；重复/篡改/不完整参数、跨账号/跨租户、失效版本拒绝     | C06           |
| CA14 | 旧路由和政策入口仍定位正确对象；返回来源已变时不恢复旧聊天、不自动应用模型                 | C04、C06      |
| CA15 | 新建/缺密钥/验证失败或过期/发布依赖变化均显示实际阶段；验证失败不能继续发布                | C07           |
| CA16 | 完整检查区分空间与本人条件；未执行/无权检查显示 UNKNOWN，不能推断全部通过                  | C07           |
| CA17 | 同主体/空间/精确费率的多模型复用账户和一条规则；跨主体/空间/费率不复用                     | C08           |
| CA18 | 修改规则保留费用/占用，不批量建账；移除模型保留规则，撤规则阻止新使用                      | C08           |
| CA19 | 预算关联分页准确；不泄露其他成员账本或数量；不同币种不混加                                 | C08           |
| CA20 | 租户/空间双层政策及草稿效果可解释；收紧后冲突可见，不自动清理旧设置                        | C09           |
| CA21 | 空间负责人无租户完整政策权仍可读取安全约束；读取失败不当允许；预览零业务写入               | C09           |
| CA22 | 租户名称更新需当前治理角色+显式新资格；CAS 冲突保留草稿，重复确认不重复变更                | C10           |
| CA23 | 组织 provisioning 或模板缺失时创建关闭；创建成功无空间/AI账户也能管理概览                  | C10           |
| CA24 | 个人托管主体/默认关联不可改，个人 OWNER 不交接；组织交接唯一负责人且原子                   | C10、C11      |
| CA25 | tenant-only 邀请接受仅入租户；空间邀请只加入目标，不开放其他空间；账号必须为邀请目标       | C11           |
| CA26 | 已有成员接受不改角色；过期/撤销/消费旧邀请拒绝；重新加入不恢复旧代次授权                   | C11           |
| CA27 | 移除租户成员后空间/模型/凭据资格及时失效；管理员不能授予/移除 OWNER 或绕过 ADMIN 限制      | C11           |
| CA28 | 账号退出/切换、迟到响应、跨标签变更不污染草稿或状态；历史回执按当前资格复核                | C02、C10、C11 |
| CA29 | 窄屏、键盘、加载/空状态、未开放能力正常；菜单可见不代替业务动作资格                        | C12           |
| CA30 | 隔离测试、真实登录、外部供应商、多节点、生产菜单/缓存与部署分别留证，不混写通过            | C12           |

## 12. C01 验证结果与交接

### 12.1 本次已执行

- 只读核对页面、领域接口、默认服务/装配、控制器、前端服务、动作预设、回执白名单和安装脚本。
- 现状前端回归：6 个测试文件、47 项通过，Vitest 报告耗时 5.79 秒。命令如下（在 frontend 下运行）：

```sh
npm test -- src/features/ainew/aiNavigation.test.ts src/pages/Workspace/Policies/index.test.tsx src/pages/Environment/Workspaces/index.test.tsx src/pages/AI/Models/index.test.tsx src/features/workspace/WorkspaceAiGovernanceEditor.test.tsx src/features/workspace/WorkspacePolicyDialog.test.tsx
```

- 这些测试证明所选现状回归通过，包含当前“忽略 URL 目标”行为；不代表新方案已经通过。C06 必须调整相关断言。
- 测试启动时出现 Vite 将来切换 native configLoader 的兼容提示，本次未改配置。
- 本批为文档交付，不新增实现测试或全量后端构建，不沿用历史 TypeScript 诊断数量作为本次结果。
- 文档相对链接、空白、C01～C12 批次及 CA01～CA30 验收编号核对通过；29 条既有非本批修改的状态与内容指纹保持，工作区仅增加本文并更新上轮实施计划。

### 12.2 实施计划修正

1. C03 不只是补 HTTP：必须补独立租户目录/详情的核心实现、候选查询及默认装配；目录不依赖个人偏好初始化。
2. C06 按服务端缺口扩展模型筛选与平台能力查询；目标页和现有测试同时改为消费合法任务定位。
3. C07/C08/C09 的完整检查、费率关联和政策效果不能只靠现有 DTO 或前端拼装，应先补权威安全投影/预览。
4. C10 要补组织创建默认服务、受信资格与初始政策模板，不把已有接口声明当生产能力；名称维护需新显式资格。
5. C11-A/B 均包括核心实现、持久化、装配、HTTP、前端和回执/审计；tenant-only 邀请为新增领域能力。

### 12.3 C02 进入条件和边界

C01 交付已满足：当前事实、页面职责、权限矩阵、接口差距、范围/导航/恢复字段、政策/预算语义及验收场景均已记录。 下一批可执行
C02：只统一局部目标、隔离配置加载副作用并建立离开保护；独立租户目录暂保留显式过渡限制，C03/C04 再替换。 本次停止于
C01，C02～C12 均未开始，生产环境与新功能验收均未执行。
