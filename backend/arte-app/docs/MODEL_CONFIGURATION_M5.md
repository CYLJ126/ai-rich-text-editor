# 模型配置 M5 综合验收与交付

2026-11-03。需求基线为[模型配置专项 V1.6](../../../ARTE%20AI%20模型配置需求及设计.md)。M5
编码修正和本机隔离验收完成；本说明逐项区分通过、部分验证和上线前待验证，不代表生产部署完成。

## 本批修正

1. **创建归属按当前资格提供。** 新增只读 `POST /arte/ai-new/model-resource/getModelCreationOptions`，请求严格为 `{scope}`
   ，返回当前可创建的 PERSONAL／TENANT。复用正式创建的空间成员、政策、租户及 RBAC 门控，不创建资源、回执或预算；提交仍重新检查。模型管理不再仅用空间
   `model:create` 推断租户创建资格。
2. **租户详情直接展示授权。** 嵌入授权组件优先采用当前传入的模型，不再从空的独立列表查找。统一创建后即可编辑允许空间与凭据消费者，无需刷新或跳转旧
   Tab。
3. **修复租户模型动作读取。** 展示型批量授权读取共享策略时补齐只读事务，避免已发布租户模型的动作查询误报
   AUTHORITY_UNAVAILABLE。已有读取快照复用原事务，独立调用才开启只读事务；执行型检查继续参加调用方当前事务。最终回归发现并修复了首次实现重复开启快照的问题，FIXED
   与 DATABASE 路径均重新验证；收紧允许空间后仍拒绝 USE，不扩大授权。
4. **更新验收基础设施。** A2 浏览器目录升级为正式页面＋真实 MVC／H2／回环供应商桥；工具页面测试改为检查 M4 已迁移的只读指引。MySQL
   测试夹具仅在 H2 时转换方言，MySQL 使用原始生产 DDL；元数据查询限定本次随机数据库。未修改生产 DDL。

## 实际浏览器闭环

连接、审核方案、模板、模型草稿、测试密钥、验证和发布均从正式页面配置。审核目录最初为空；方案发布没有变成聊天模型。界面保存经过真实控制器、身份映射、公共授权、持久化和事务，没有用内存响应替代业务接口。

个人与租户分两次全新 H2 启动验证，临时数据不共用：

| 场景     | 调用主体／执行空间                                                                        | 结果和持久化证据                                                                                                                                                                                                                               |
|----------|-------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 个人模型 | Alice `06c2ff9e-7803-4c1e-acf6-cb9f8bca7e75`；空间 `0f77d0e3-08c5-4117-b81b-02fecf18c2ec` | 单一实际模型候选；手动准备并应用后 SUCCEEDED／SETTLED；[截图](evidence/model-m5/personal-chat.jpg)、[账本快照](evidence/model-m5/personal-state.json)                                                                                          |
| 租户模型 | bob `c02d1fe1-e0f9-4bd2-859c-d37400a2da81`；执行 B `80ccb008-8d98-47b1-af4f-7aa9fe92098d` | 模型管理范围在 A，bob 无 A 成员身份；分别批准目标空间、READ／USE 和当前凭据用途后，bob 不经治理编辑完成本人准备、手动应用及 SUCCEEDED／SETTLED；[截图](evidence/model-m5/tenant-bob-chat.jpg)、[账本快照](evidence/model-m5/tenant-state.json) |

两次均使用 `reviewed-m5-text@v1`，测试规则 100 CNY；供应商通过真实本机 HTTP 报告输入 3、输出 2 Token，以输入 1／输出 2 CNY
每百万 Token 结算 **0.000007 CNY**，held=0。各次恰有 1 个账户、1 条准备记录、1 次生成外发和 1 条 SETTLED
快照。金额只是隔离测试数字，没有真实费用或外部供应商请求。截图中的预算面板是调用前读取值，以 JSON 持久化快照核对最终费用。

租户运行固定资源为 `e78d8add-e65f-44b0-9c3e-95a78f6bef1e`，binding 为
`resource-2895916d0b5f405fcb6736c1c3d32b9e7ca5006ca218f349cf8779c8ece0f7b7@v1`。来源 A 是
`679eae9f-7a7c-44e6-bd5d-a3163f8d1d0d`。打开并保存 B 的配置后，目录仍显示当前
A；[宽屏](evidence/model-m5/tenant-space-wide.jpg)
同时保留精确发布版本定位与“独立预算规则已保存”。[390px 窄屏](evidence/model-m5/tenant-space-narrow.jpg)测得 document
clientWidth=scrollWidth=375px（扣除滚动条），无横向溢出。

### 故障与角色

- 个人流程：丢失空间模型保存响应及本人准备响应，重挂载恢复原操作；没有自动再写或自动发送。
- 租户流程：空间保存已确认后首次重读失败，重挂载仅显示“重试确认原配置操作”，完成确认后分别保存规则；bob
  本人准备响应丢失，重挂载仍需原键确认，账户和准备记录均保持唯一。
- publisher 普通成员没有治理菜单，也没有默认的新建模型资格，页面正确收起创建选项；未为演示改变政策。已有获权个人模型在移除空间治理资格后仍可发布，由
  `ModelConfigurationM5IntegrationTest.existingPersonalPublisherDoesNotNeedWorkspaceGovernanceOrPlatformMenu`
  验证；不把它写成浏览器正向发布证据。
- bob 无空间治理资格，具备三道独立使用门控后可以准备本人账户并聊天。权限与 B 的测试配置、模拟账本操作仅在用户明确批准的本次隔离夹具中执行。

## AC01～AC37 验收矩阵

“通过”仅表示所列自动化／隔离层证据满足该行，不外推到未测试部署。后端测试路径均在
`src/test/java/com/arte/app/platform/persistence`；前端路径从 `frontend/src` 起。后端为本次重跑结果，M4 说明仅补充历史交互证据。

| 编号 | 状态     | 本次证据及边界                                                                                                                                                                               |
|------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC01 | 通过     | DatabaseModelPlanIntegrationTest、ModelConfigurationM2IntegrationTest、WorkspaceAiGovernanceIntegrationTest；空目录浏览器先发布方案，仍需创建实际模型。                                      |
| AC02 | 通过     | personal-chat.jpg 对应从统一创建、直接详情、草稿／密钥／验证／发布到调用；PersonalModelManager.test.tsx。                                                                                    |
| AC03 | 通过     | 租户创建直接详情并维护授权、发布，无旧 Tab 接力；ModelSharingManager.test.tsx 新增嵌入详情回归。                                                                                             |
| AC04 | 通过     | M5 creationOptions 测试：读取零业务写、撤销当前 RBAC 后只剩 PERSONAL、伪造 TENANT 拒绝；M2 严格归属／scope HTTP；前端选择过滤回归。                                                          |
| AC05 | 通过     | WorkspaceAiGovernanceIntegrationTest.twoResourcesAtOneExactRateReuseAccountAndDeselectingOneKeepsOtherUsable、页面资源身份／来源测试；同名不作为去重键。                                     |
| AC06 | 通过     | 个人浏览器与账本快照：一份实际资源、一个可用候选，没有 reviewed 目录模型。                                                                                                                   |
| AC07 | 通过     | WorkspaceAiGovernanceIntegrationTest：无启用不发现、缺预算仍显示可读候选但不能执行；两个浏览器运行均先显示本人缺项。                                                                         |
| AC08 | 通过     | WorkspaceAiGovernanceIntegrationTest 同费率多模型复用；ModelUsagePreparationIntegrationTest 两种并发／重放测试及重组装。                                                                     |
| AC09 | 通过     | WorkspaceAiGovernanceIntegrationTest 取消单个资源保留另一资源与规则、账户。                                                                                                                  |
| AC10 | 通过     | WorkspaceAiGovernanceIntegrationTest 当前派发／外发撤销规则门控及 in-flight 原费率结算。                                                                                                     |
| AC11 | 通过     | WorkspaceAiGovernanceIntegrationTest、ModelUsagePreparationIntegrationTest 严格 HTTP／主体预算隔离；TenantModelUsagePreparationIntegrationTest 治理者可配置但无凭据消费权。                  |
| AC12 | 通过     | TenantModelSharingIntegrationTest、TenantModelUsagePreparationIntegrationTest：空间范围／模型 USE／消费者分别拒绝；浏览器逐项配置后 bob 成功。                                               |
| AC13 | 通过     | TenantModelSharingIntegrationTest、PersonalModelPublicationIntegrationTest 凭据轮换、撤销、成员代次及重放门控。                                                                              |
| AC14 | 通过     | DatabaseModelPlanIntegrationTest 费率身份、PersonalModelPublicationIntegrationTest 不可变历史及 WorkspaceAiGovernanceIntegrationTest 版本切换；ChatNew 回归保持手动应用。                    |
| AC15 | 通过     | WorkspaceAiGovernanceIntegrationTest 严格 HTTP、资源错配和精确版本验证；选择集合按资源唯一。                                                                                                 |
| AC16 | 通过     | WorkspaceAiGovernanceIntegrationTest inFlightSettlementVersionSwitchRegenerationAndGenerationChildrenUseCurrentGovernance、RegenerationReconciliationIntegrationTest。                       |
| AC17 | 通过     | tenant-space-wide.jpg 中当前 A／配置 B；tenant-state.json 账户明确归 bob＋B；M4/M5 工作空间与 ChatNew 空间隔离测试。                                                                         |
| AC18 | 通过     | M5 既有模型发布者降为 MEMBER 后 canManage=false 仍发布成功、无账户写入；模型页测试不要求前两平台 Tab 才能管理。                                                                              |
| AC19 | 通过     | WorkspaceAiGovernanceIntegrationTest.queriesNeverPrepareAndConcurrentFirstPreparationCommitsOneEmptySelectionAndNoAccounts；浏览器显式准备后才编辑。                                         |
| AC20 | 通过     | ModelUsagePreparationIntegrationTest.existingHeldChargedLimitAndVersionAreNeverResetOrIncreased；治理规则现有账本调整与提额权限回归。                                                        |
| AC21 | 通过     | WorkspaceAiGovernanceIntegrationTest 独立持久化／CAS；WorkspaceAiSettings.test.tsx 独立保存、dirty 冲突及恢复。                                                                              |
| AC22 | 通过     | 两次浏览器响应丢失／重挂载／原键确认，租户 confirmed 重读失败；ModelUsagePreparationPanel、WorkspaceAiSettings 及 ChatNew 测试。                                                             |
| AC23 | 部分验证 | 组件测试覆盖 storageBlocked、损坏恢复和密钥不落本地存储；后端安全投影／回执测试。未执行生产日志平台全量密钥检索或真实浏览器存储故障注入。                                                    |
| AC24 | 通过     | 模型、共享、工作空间编辑器与 ChatNew 测试覆盖账号／完整 scope、迟到响应和恢复记录隔离；固定会话切换可读，生产 JWT 切换另验。                                                                 |
| AC25 | 通过     | aiConfigurationEvents、WorkspaceAiUsagePanel 与 ChatNew 回归同页／storage 失效，不自动应用；浏览器配置保存后仍须明确应用再发送。                                                             |
| AC26 | 通过     | ModelAclRuntime/HttpRuntime/ExecutionIntegrationTest、ModelResourceIntegrationTest、页面分页测试；授权过滤空页继续 cursor。                                                                  |
| AC27 | 通过     | WorkspaceAiGovernanceIntegrationTest 参数化审计／Outbox／回执回滚、当前撤权；发布及账户准备失败整体回滚测试；真实 MySQL 旧 RR 快照／当前激活检查另补验 1 项通过。多节点竞争不在此结论内。    |
| AC28 | 通过     | personal-state.json、tenant-state.json 及两次聊天截图：真实 HTTP 回环与耐久 SUCCEEDED／SETTLED，主体／scope／费率明确。                                                                      |
| AC29 | 部分验证 | PlatformToolChatAccess、PlatformToolBilling、ToolChatLoop、ToolChatBudgetSelector 与控制面回归；工具页只读入口测试通过。未对真实外部供应商或完整 Tool Chat 浏览器执行新一轮工具调用认证。    |
| AC30 | 部分验证 | FIXED／共享控制面回归；真实 MySQL 新装 M1～M3 DDL 和菜单 DML、H2；生产构建通过；B 配置宽窄屏有真实后端证据。未覆盖所有页面断点、生产基础设施和多节点。                                       |
| AC31 | 通过     | Models、Workspaces、ChatNew 两个文件与 Tools 页面测试；浏览器正式使用准备／参数组件手动应用。完整 ChatNew 浏览器挂载未作为本次证据。                                                         |
| AC32 | 通过     | 浏览器配置 B 保持当前 A；workspaces／workspace／ChatNew 测试核验只有显式打开聊天且切换服务提交后才导航。                                                                                     |
| AC33 | 通过     | 精确发布跳转到 B 的页面证据；aiNavigation、Models、Workspaces 测试覆盖往返管理／执行／聊天 scope 及无自动写入。                                                                              |
| AC34 | 通过     | aiNavigation 与 Models／Workspaces 路由回归覆盖旧 URL、缺项、失效版本、账号、跨租户及 returnTo 白名单。                                                                                      |
| AC35 | 通过     | defaultAiPolicy、ModelPlanManager、Models 和治理集成测试；浏览器独立模板发布后显式准备空间，无账户自动发放。                                                                                 |
| AC36 | 通过     | M5 无治理资格既有模型发布测试＋bob 浏览器本人准备／调用；创建资格仍独立检查。                                                                                                                |
| AC37 | 部分验证 | 真实 MySQL 与 H2 重复安装菜单 DML，只有 admin 获现有入口；旧 governor 仅有 aiModels 时缺工作空间入口，业务 operation 关系数量不变。未读业务库的真实分配／在线 JWT 菜单／缓存，部署前须核对。 |

矩阵共 37 项：33 项在所列层次通过，AC23／AC29／AC30／AC37 为部分验证。尚未覆盖的生产条件不计为通过。

## 回归与构建

- 前端 25 个文件 **235 项通过**：23 个文件 200 项，ChatNew 页面 18 项、空间集成 17 项分别串行执行。
- 后端完整受影响回归 **306 项：304 通过、2 项 MySQL 专用测试跳过，0 失败／错误**（core 3、ai 62、app 241）。H2 中跳过旧来源约束迁移专项和
  MySQL current-read phantom 专项；后者已另用真实 MySQL 单独运行并通过 1 项，旧迁移专项本轮未执行。浏览器桥另行 opt-in 的 1
  项退出成功，不与 306 项合并计数；启动等待本身不代替实际浏览器证据。
- 真实 MySQL 8.4 专项 **4 项通过，0 跳过**：只读创建资格、租户批量授权事务、无治理资格发布、新装
  DDL／菜单重复安装。使用本次专用回环容器及随机数据库，未连接业务库。H2 测试才移除 MySQL
  方言；最初失败为测试夹具方言处理与跨库元数据查询问题，修复的是测试代码，生产 DDL 沿用 M1～M3。
- 另补验真实 MySQL `ModelAclExecutionIntegrationTest#staleRepeatableReadSnapshotCannotBypassActivation` **1 项通过**，确认旧
  RR 快照不能绕过新激活后的当前检查；与上面新装四项分开记录。
- 前端生产构建成功（338 个产物）；未执行部署。
- 全量 tsc **106 条既有诊断、0 新增／0 消除**，按文件和诊断内容与 M4 基线比较并忽略行号偏移；不宣称全量类型检查通过。
- 本次前端变更定向 Biome lint 无错误，3 处既有非空断言警告；services 目录按既有 Biome 配置排除，由服务测试和类型基线比较验证。
- 文档 256 个本地链接核对，新增链接均有效；顶层文档原有 `docs/arte-top-level-design-v0.1.md` 缺失，保留历史问题。手册模板及历史章节、原有
  28 个无关 diff 块和暂存区保持；`git diff --check` 通过。

后端复现（backend，JDK 21；必要时加本机 Mockito agent）：

```sh
mvn -o -pl arte-app -am -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=WorkspaceAiGovernanceIntegrationTest,ModelConfigurationM2IntegrationTest,ModelDraftIntegrationTest,ModelDraftAssemblyIntegrationTest,PersonalModelPublicationIntegrationTest,TenantModelSharingIntegrationTest,DatabaseModelPlanIntegrationTest,DatabaseAiControlIntegrationTest,DatabaseConnectionIntegrationTest,ModelResourceIntegrationTest,ModelManagementHttpIntegrationTest,ModelAclRuntimeIntegrationTest,ModelAclHttpRuntimeIntegrationTest,ModelAclExecutionIntegrationTest,ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest,ModelStoreProxyIntegrationTest,PlatformBindingJsonTest,PlatformToolBillingIntegrationTest,PlatformMybatisConfigurationTest,PlatformToolChatAccessIntegrationTest,PlatformToolManagementAccessTest,AiResourceEntitlementServiceTest,ControlPreparationTest,ControlReadPortsTest,ControlPlaneAssemblyCompatibilityTest,ToolChatLoopTest,ToolChatBudgetSelectorTest,RegenerationReconciliationIntegrationTest,PlatformPackageBoundaryTest,PublicContractBoundaryTest,ModelConfigurationM5IntegrationTest test
```

MySQL 仅在自建隔离服务运行时使用
`-Dtest=ModelConfigurationM5IntegrationTest -Dplatform.test.mysql='jdbc:mysql://127.0.0.1:13316/mysql?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC'`
。夹具会创建并删除自己的随机库；测试服务仅使用夹具固定的 root／platform-test，账号约束及本机依赖见测试基类，不把此命令指向业务地址。旧快照专项把
`-Dtest` 换为 `ModelAclExecutionIntegrationTest#staleRepeatableReadSnapshotCannotBypassActivation`，其余测试条件相同。

前端复现（frontend，重型聊天文件单独串行）：

```sh
npm test -- src/services/platform/model.test.ts src/features/ainew/PersonalModelManager.test.tsx src/services/platform/workspaceAi.test.ts src/services/platform/modelUsage.test.ts src/services/platform/modelPlan.test.ts src/services/platform/defaultAiPolicy.test.ts src/features/ainew/ModelPlanManager.test.tsx src/features/ainew/WorkspaceAiSettings.test.tsx src/services/platform/modelSharing.test.ts src/features/ainew/ModelSharingManager.test.tsx src/features/ainew/ModelUsagePreparationPanel.test.tsx src/features/ainew/modelSharingRecovery.test.ts src/features/ainew/aiNavigation.test.ts src/features/ainew/aiConfigurationEvents.test.ts src/features/ainew/WorkspaceAiUsagePanel.test.tsx src/pages/AI/Models/index.test.tsx src/pages/Environment/Workspaces/index.test.tsx src/features/workspace/workspace.test.tsx src/features/workspace/WorkspacePicker.test.tsx src/features/environment/navigation.test.tsx src/pages/AI/Tools/index.test.tsx src/features/ainew/ConnectionManager.test.tsx src/features/ainew/ChatConfigurationForm.test.tsx --maxWorkers=1
npm test -- src/pages/AI/ChatNew/index.test.tsx --maxWorkers=1
npm test -- src/pages/AI/ChatNew/workspaceIntegration.test.tsx --maxWorkers=1
npm run build
npm run tsc
```

本次命令退出状态与计数摘录见[验证记录](evidence/model-m5/verification.txt)
。浏览器复现见[夹具 README](../../../frontend/scripts/model-a2-browser/README.md)。证据仅含隔离测试身份和固定假密钥。

## 新装和部署边界

新装表和顺序沿用 [M3 安装说明](MODEL_CONFIGURATION_M3.md)及[重建清单](MODEL_CONFIGURATION_M3_RESET.md)：审核方案与
RESOURCE 模型发布证据、空间实际版本选择、独立费率规则及原账户／账本表必须一起匹配当前代码。M5 无需新增表、删表或结构迁移。不要把现有
Catalog／配置载荷带进这套新数据契约，也不要只清一部分账本关系。

本次真实 MySQL 验证按测试的空库依赖顺序加载当前 DDL，随后两次执行环境菜单 DML；不是对生产 RBAC 全部 schema
的安装认证。既有治理角色不能因为模型菜单可见就推断已有 `EnvironmentWorkspaces`
。上线前按[菜单只读核对 SQL](../scripts/arte-model-m4-menu-audit-mysql.sql)检查目标环境菜单、角色与直接用户绑定，按原职责维护现有菜单并刷新对应缓存；页面菜单与后端
canManage／模型／凭据／预算授权分别检查。

临时浏览器标签已关闭，视口恢复；15203 桥及随机回环供应商已随测试关闭，5234 Vite 已停止；本次专用 MySQL
容器已停止并自动移除。无业务库清理、真实费用、菜单自动赋权或部署。

上线前仍需验证生产 JWT／真实角色菜单与缓存、外部供应商协议及工具能力、完整 ChatNew／Tool Chat
浏览器流程、目标部署配置、跨节点竞争和容量。文章模块、助手及此前明确推迟的功能未纳入本批。
