# C12 综合验收、兼容与交付

验收基于 C01～C11 的交付代码和 [C01 基线与验收契约](ARTE%20配置管理优化%20C01%20基线与实施契约.md)
。当前操作见 [用户手册](ARTE%20用户手册.md)
，部署前核对见 [环境配置说明](backend/arte-app/docs/ENVIRONMENT_CONFIGURATION.md)。

## 组合角色和操作链

| 组合                   | 验收步骤                                                                 | 可观察的边界                                                                       |
|------------------------|--------------------------------------------------------------------------|------------------------------------------------------------------------------------|
| 普通使用者             | 查看当前聊天空间的模型条件，按已批准规则准备本人预算，显式应用后发送     | 本人账户与所选空间、精确费率一致。准备无需空间治理菜单                             |
| 模型维护者             | 在管理空间维护个人或租户模型，验证并发布固定版本，选择不同执行空间并返回 | 资源动作、创建、空间治理分别核验。往返保留原模型和精确版本，聊天保持原空间         |
| 工作空间负责人         | 维护概览、政策、模型启用、费率规则及成员                                 | 空间治理与模型执行分别授权。规则保存不会批量发放账户                               |
| 组织租户负责人／管理员 | 查看空组织、维护名称和政策、邀请或撤销成员                               | 租户资格与空间资格分别检查。组织创建和改名使用不同 RBAC 操作。租户撤权联动空间撤权 |
| 平台配置者             | 无局部空间时打开已获权的审核连接、审核方案与费率、默认模板               | 平台资格独立于管理空间筛选及成员角色                                               |

本机回环集成从审核配置、个人或租户实际模型发布走到目标空间启用、本人预算准备、执行和耐久结算。租户场景的使用者只在执行空间具备成员资格，分别核验允许空间、模型动作与凭据用途。完整
ChatNew 的显式应用另有组件测试。历史固定会话浏览器闭环见 [M5 证据](backend/arte-app/docs/MODEL_CONFIGURATION_M5.md)。

## 采纳点与实现位置

| 采纳点                                   | 主要实现位置                                                                                                                                                                                   | 对应验收               |
|------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------|
| 独立租户概览，空间政策在空间详情维护     | [租户页](frontend/src/pages/Environment/Tenants/index.tsx)、[空间详情](frontend/src/features/workspace/WorkspaceDetailForm.tsx)                                                                | CA06～CA09、CA20～CA24 |
| 本页配置对象、显式进入与离开保护         | [工作空间页](frontend/src/pages/Environment/Workspaces/index.tsx)、[配置导航边界](frontend/src/features/configuration/ConfigurationBoundary.tsx)                                               | CA01～CA05、CA28       |
| 管理空间筛选、平台审核区域及跨页任务往返 | [模型配置页](frontend/src/pages/AI/Models/index.tsx)、[定位与返回](frontend/src/features/ainew/aiNavigation.ts)                                                                                | CA10～CA14             |
| 维护阶段、空间条件和本人条件             | [模型详情](frontend/src/features/ainew/ModelManager.tsx)、[模型条件与本人准备](backend/arte-app/src/main/java/com/arte/app/platform/ai/DatabaseModelUsagePreparationService.java)              | CA15～CA16             |
| 模型版本、精确费率与预算规则的关联       | [空间 AI 配置](frontend/src/features/workspace/WorkspaceAiGovernanceEditor.tsx)、[空间 AI 治理服务](backend/arte-app/src/main/java/com/arte/app/platform/ai/WorkspaceAiGovernanceService.java) | CA17～CA19             |
| 政策分组、上限及效果                     | [政策编辑器](frontend/src/features/workspace/PolicyEditor.tsx)                                                                                                                                 | CA20～CA21             |
| 两级邀请、成员治理及撤权                 | [成员面板](frontend/src/features/workspace/MembershipPanel.tsx)、[成员治理服务](backend/arte-core/src/main/java/com/arte/core/application/workspace/DefaultMembershipGovernanceService.java)   | CA24～CA28             |

## CA01～CA30 验收矩阵

「通过」限于表内验证层次。Workspaces、Tenants、Models 指对应配置页面测试，Chrome 场景使用隔离响应夹具。

| 编号 | 结论     | 本轮证据                                                                                                            |
|------|----------|---------------------------------------------------------------------------------------------------------------------|
| CA01 | 本机通过 | Workspaces、Models、ChatNew 工作空间集成验证局部目标与当前空间及偏好隔离                                            |
| CA02 | 本机通过 | 目录和页面测试验证无偏好读取、个人初始化与配置浏览分离                                                              |
| CA03 | 本机通过 | Workspaces、ChatNew 工作空间集成验证显式进入及失败不导航                                                            |
| CA04 | 本机通过 | ConfigurationBoundary 与页面测试验证保存、放弃、取消、按序处理及版本关联                                            |
| CA05 | 本机通过 | 配置、政策、成员及预算准备测试验证原键恢复、读回失败和存储异常                                                      |
| CA06 | 本机通过 | TenantDirectoryTest、租户页面和成员 HTTP 验证空组织及受限访客                                                       |
| CA07 | 本机通过 | TenantDirectoryTest、WorkspaceBrowseTest、WorkspaceHttpIntegrationTest 验证过滤、续页与拒绝                         |
| CA08 | 本机通过 | Tenants、TenantAuthorizationServicesTest 验证基础摘要与治理分离                                                     |
| CA09 | 本机通过 | TenantAuthorizationServicesTest、WorkspaceAiGovernanceIntegrationTest 验证租户角色与空间治理独立                    |
| CA10 | 本机通过 | Models、平台资格 HTTP 测试验证无局部目标的审核资格及撤权                                                            |
| CA11 | 本机通过 | ModelDraftIntegrationTest 验证完整候选查询、空页续扫与游标条件绑定                                                  |
| CA12 | 本机通过 | 模型、工作空间和聊天导航测试验证管理 B、执行 C、返回聊天 A 与精确版本                                               |
| CA13 | 本机通过 | aiNavigation、Models、Workspaces 测试验证定位、刷新、畸形或越权参数                                                 |
| CA14 | 本机通过 | Workspace/Policies、Models、aiNavigation 测试及兼容路由构建产物                                                     |
| CA15 | 本机通过 | 维护进度、发布资格、PersonalModelPublicationIntegrationTest 的版本与验证失效场景                                    |
| CA16 | 本机通过 | Models、ModelUsagePreparationIntegrationTest 与 TenantModelUsagePreparationIntegrationTest 验证条件分组、缺项与拒绝 |
| CA17 | 本机通过 | WorkspaceAiGovernanceIntegrationTest 与 Chrome 同费率规则定位                                                       |
| CA18 | 本机通过 | WorkspaceAiGovernanceIntegrationTest 验证规则与启用独立、保留预留与费用、撤销后的准入                               |
| CA19 | 本机通过 | 费率关联、本人准备和预算 HTTP 测试验证受权投影、分页与精确费率                                                      |
| CA20 | 本机通过 | 政策页面、DefaultWorkspacePolicyServiceTest 及 Chrome 政策对照和草稿预览                                            |
| CA21 | 本机通过 | 政策服务、WorkspaceHttpIntegrationTest、Chrome 安全上限与只读预览                                                   |
| CA22 | 本机通过 | Tenants、PlatformWorkspaceAssemblyIntegrationTest 组织改名权限、CAS 与原键                                          |
| CA23 | 本机通过 | PlatformWorkspaceAssemblyIntegrationTest 模板缺失关闭创建、创建成功且无空间                                         |
| CA24 | 本机通过 | 同一装配测试验证个人关联和默认空间限制、组织与空间交接                                                              |
| CA25 | 本机通过 | 同一装配测试验证两级邀请、本人接受、跨空间拒绝                                                                      |
| CA26 | 本机通过 | 同一装配测试验证已是成员、过期、撤销、已消费及重新加入。TenantModelSharingIntegrationTest 验证旧凭据代次            |
| CA27 | 本机通过 | 级联撤权、负责人保护、角色与失效回执的 HTTP 和模型准入测试                                                          |
| CA28 | 本机通过 | 配置页面、成员操作恢复、跨标签事件、账号隔离及当前资格回执测试                                                      |
| CA29 | 部分验证 | Chrome 窄屏和代表性键盘流程、组件加载／空态、只读菜单 SQL。真实账号菜单缓存及全页键盘巡检待验收                     |
| CA30 | 已区分   | 本机、模拟和目标环境证据分列，发布条件见下文                                                                        |

## HTTP 与兼容范围

应用前缀为 `/arte`，以下接口使用 POST。完整字段见相应批次说明。

| 能力                 | 路径和动作                                                                                                                                                                              | 客户端约束                                                                                                       |
|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 目录、租户概览和名称 | `/workspace/` 下的 `browseAccessibleTenants`、`getTenant`、`getTenantCreationOptions`、`createOrganization`、`updateTenant`                                                             | 目录使用服务端游标与过滤。组织创建资格与名称更新资格分开                                                         |
| 空间目录与偏好       | `/workspace/` 下的 `browseAccessibleWorkspaces`、`getWorkspace`、`getWorkspacePreference`、`updateWorkspacePreference`                                                                  | 目录可按租户过滤。用完整 scope 定位，进入和偏好维护分别显式执行                                                  |
| 政策和效果           | `/workspace/` 下的 `getTenantPolicy`、`getWorkspacePolicy`、`previewWorkspacePolicy`、`updateTenantPolicy`、`updateWorkspacePolicy`                                                     | 预览只读，使用预览基准版本。空间详情只显示受权上限及效果                                                         |
| 资格、启用与规则     | `/workspace/ai/` 下的 `getConfigurationCapabilities`、`getConfiguration`、`prepareConfiguration`、`updateConfiguration`、`getBudgetRules`、`listBudgetRateOptions`、`updateBudgetRules` | 平台资格查询独立于 scope。空间启用与规则独立保存                                                                 |
| 模型条件与本人准备   | `/ai-new/model-resource/` 下的 `getModelUsageChecks`、`getModelUsagePreparation`、`prepareModelUsage`                                                                                   | 显式传执行 scope 与实际模型、绑定版本。读取不建账                                                                |
| 成员及回执           | `/workspace/getMembershipCapabilities`、两级成员及邀请端点、`/workspace/getWorkspaceCommandReceipt`                                                                                     | 写入用原 `Idempotency-Key`，依据当前资格、范围及版本核验。见 [C11 说明](ARTE%20配置管理优化%20C11%20交付说明.md) |

配置路由的账号、管理空间、执行空间及聊天来源独立核对。旧 `/AI/Models`、`/AI/ModelSharing`、`/AI/Connections`、
`/AI/ModelPlans`、`/Workspace/Policies` 继续生成页面。旧 `tab=workspace-ai`
转入工作空间配置，缺少目标时先选择空间。历史模型数据结构的兼容性按 [M3 数据说明](backend/arte-app/docs/MODEL_CONFIGURATION_M3.md)
核对。

## 安装、上线检查和回退

1. 用只读账号运行 [C12 核对 SQL](backend/arte-app/scripts/arte-configuration-c12-audit-mysql.sql)
   。确认库、菜单同编码／同路径冲突、子菜单父级、启用状态、租户操作登记、关系分配及依赖表。表存在性用于预检，结构和索引按对应 DDL
   核对。
2.
新装按 [平台身份](backend/arte-app/docs/PLATFORM_IDENTITY.md)、[公共持久化](backend/arte-app/docs/PLATFORM_PERSISTENCE.md)、[空间政策和模板](backend/arte-app/docs/PLATFORM_WORKSPACE_POLICY.md)
的顺序准备基础表和模板。已有库只运行适用的增量，核对 UTC
微秒版本及政策来源字段。随后按 [模型部署说明](backend/arte-app/docs/MODEL_CONFIGURATION_M5.md) 核对执行、控制面、模型与 ACL
结构、DATABASE 模式及加密配置。
3. 缺少环境菜单时先处理冲突，再执行现有 [环境菜单脚本](backend/arte-app/scripts/arte-environment-menu-dml-mysql.sql)
   。若要改名或新建组织，再登记 [C10 操作项](backend/arte-app/scripts/arte-tenant-governance-rbac-dml-mysql.sql)，按职责分配
   `EnvironmentTenants:update`、`EnvironmentTenants:create`
   。组织创建另需部署开关和版本化初始模板。见 [C10 说明](ARTE%20配置管理优化%20C10%20交付说明.md)。
4.

在启动新版工作空间模块前，执行 [C11 邀请表 DDL](backend/arte-app/scripts/arte-platform-membership-invitation-ddl-mysql.sql)
。邀请沿用应用数据源，成员治理随工作空间模块一起启用。部署时统一前后端版本并核对所有节点配置。
5. 分配菜单后刷新受影响角色、用户及在线权限缓存，再检查 `/arte/auth/onlineInfo`
   。用前述五类真实账号核对可见菜单、拒绝边界、跨空间往返、原键恢复及本人应用。按批准范围核对外部供应商、费用、回执、多节点并发与撤权。
6.

停用组织创建可关闭其开关后重启，保留已写入的成员、邀请、组织、回执与账本。回退整个发布前停止相关写入并保存数据快照，核对回退版本能否读取新的回执类型和持久化格式，再协调回退前后端。按变更清单撤回本次新增的菜单分配或授权并刷新缓存。数据重置另行评估和审批。

本轮未在业务库执行脚本、分配权限或部署。真实登录、供应商及目标集群验收通过后，再签收目标环境发布。

## 本轮验证记录

| 层次           | 结果与范围                                                                                                                                                                                                |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 前端自动化     | 扩大回归覆盖 57 文件，546 项中 542 项通过。ChatNew 的 1 项超时在串行复验通过。ChatNew 空间集成另有 17 项串行复验通过。聊天布局的 3 项尺寸断言在串行复验仍失败，对应布局代码与测试在 C01～C12 范围内无改动 |
| 类型与构建     | 生产构建通过并生成配置及兼容路由。全量 tsc 的 107 条诊断与 C11 基线逐条一致                                                                                                                               |
| 后端本机集成   | 402 项中 400 项通过、2 项 MySQL 专用测试跳过。含真实 MVC、H2、个人与租户模型的本机 HTTP 回环及耐久结算。邀请过期与重复接受补充断言在 H2 另跑通过                                                          |
| 隔离 MySQL 8.4 | 6 项专项全部通过，覆盖成员、组织创建及改名、旧快照撤权、筛选游标、新装及菜单重复安装。C12 核对脚本使用仅有 SELECT 权限的账号，检出路径冲突、停用项及原始绑定                                              |
| 隔离 Chrome    | 在当前代码验证跨空间往返、390 像素布局、同费率行定位、政策预览与邀请。租户邀请表单验证 Tab 焦点及 Enter 精确查找。业务接口使用模拟响应                                                                    |
| 目标环境       | 真实登录与菜单缓存、外部供应商、目标集群并发撤权及部署待验收                                                                                                                                              |

聊天布局基线的三项失败涉及默认侧栏宽度与拖动／收缩断言。实现默认侧栏宽度为 350，旧测试仍有 300
的预期。本轮保留基线差异。后端回环用例首次在网络沙箱中无法监听本机端口，允许隔离回环后重跑通过。测试供应商使用假密钥及回环地址。

前端复现，在 frontend 目录执行：

```sh
npm test -- src/features/workspace src/features/configuration src/features/ainew \
  src/services/platform src/services/arte-ai src/pages/Environment src/pages/Workspace/Policies \
  src/pages/AI/Models src/pages/AI/ChatNew
npm test -- src/pages/AI/ChatNew/index.test.tsx --maxWorkers=1
npm run build
./node_modules/.bin/tsc --noEmit
```

后端复现，在 backend 目录使用 JDK 21。根据本机 Mockito 设置传入对应 Java agent。真实 HTTP 回环用例需要本机监听权限。

```sh
mvn -o -pl arte-app -am -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dtest=com.arte.core.application.workspace.**,com.arte.core.application.access.**,PlatformWorkspaceAssemblyIntegrationTest,PlatformWorkspaceConfigurationTest,WorkspaceHttpIntegrationTest,PlatformPersistenceIntegrationTest,DatabaseConnectionIntegrationTest,DatabaseModelPlanIntegrationTest,ModelResourceIntegrationTest,TenantModelSharingIntegrationTest,ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest,WorkspaceAiGovernanceIntegrationTest,ModelDraftIntegrationTest,PersonalModelPublicationIntegrationTest,ModelConfigurationM5IntegrationTest,ModelAclExecutionIntegrationTest,ChatHttpIntegrationTest,BudgetHttpIntegrationTest,AdmissionIntegrationTest' test
```

MySQL 专项使用临时实例和测试类自动建立的随机数据库。测试入口为 PlatformWorkspaceAssemblyIntegrationTest
的成员与组织场景、ModelConfigurationM5IntegrationTest 的新装／菜单测试、ModelDraftIntegrationTest 的筛选游标测试，以及
ModelAclExecutionIntegrationTest 的旧快照撤权测试。使用测试基类的隔离 MySQL 参数。Chrome 模拟验证的证明范围限于浏览器交互，历史
MVC 浏览器桥复现见 [隔离夹具说明](frontend/scripts/model-a2-browser/README.md)。
