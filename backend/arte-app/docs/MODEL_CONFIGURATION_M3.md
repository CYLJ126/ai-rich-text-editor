# 模型配置 M3：目标空间、独立预算与执行贯通

日期：2026-11-03。对应[模型配置需求及设计 V1.4](../../../ARTE%20AI%20模型配置需求及设计.md)第 10 节 M3。

M3 已完成实现与定向回归；完整页面搬迁和三个模型 Tab 整合属于 M4，综合浏览器／角色验收属于 M5。 本批没有执行业务库
DDL、清库、历史迁移、菜单分配或部署。

## 1. 本批交付

- `WorkspaceAiGovernanceService` 独立承担目标空间治理，不继续把该职责加入审核目录服务。它同时提供正式
  `ModelSelectionSource`；M2 的内存选择测试来源已删除。
- `WorkspaceConfiguration` 保存 `scope/enabled/models/templateVersion/version`，其中每项模型为 `resourceId + binding`
  。每个空间、每个模型资源只能选择一个固定版本，不接受裸审核绑定、错配资源或 latest。
- 新增 `arte_ai_workspace_budget_rule`，按完整 tenant/workspace 唯一保存独立规则集合及 CAS 版本。预算规则和模型选择分别提交；允许零个模型时维护规则。
- 旧 `/workspace/ai/ensure` 已移除，页面进入改为读取配置。显式首次准备原子保存空模型集合与默认规则快照，不建立账户，不覆盖已准备空间。
- 空间首次准备、模型更新和规则更新分别沿用公共空间门控、物理事务、CAS、authEpoch、审计、Outbox 和成功命令回执。原键重放不再次执行写入。
- A1 本人准备只按当前空间启用、当前模型／凭据资格和正额度精确费率规则初始化本人账户。已有账户复用，不重置预留／费用，不通过准备提额；定义／账本缺失或归属不一致拒绝处理。
- 同一主体、完整 scope、精确费率的多个模型共用确定性 budgetRef 和账户。取消模型不删除规则或账户；撤销规则阻断新准备、受理、派发及外发，保留历史定义、账本和原费率结算依据。
- 独立规则变更只调整已经存在且匹配费率的账户额度，不批量建立成员账户。调整保留 held、charged 及历史义务；新额度低于占用时由既有账本阻止新预留。
- DATABASE 查询／受理／派发／外发及 Tool Chat 模型子预算选择接入目标空间权威。重新核验不再仅凭目录或模型版本未变复用旧预算授权。

## 2. 权限与安全投影

空间配置需要当前目标空间 `workspace:policy:write`；配置者只需读取实际模型并满足目标范围，不要求拥有 `model:use` 或消费租户凭据。
规则提额超出可信批准上限时，再按原 appDataSource 事务读取当前平台 `aiConfiguration:manage`／既有 admin
权限，不接收客户端权限布尔值，不绕过空间治理资格。

普通使用者准备本人账户只需要实际使用资格，不要求成为空间负责人或取得治理菜单。租户范围批准、模型使用授权与精确凭据代次的消费者批准继续独立核验。

`listModelOptions` 返回已获读取资格且允许在目标空间配置的实际资源固定版本，不以本人预算是否存在作为条件；审核方案不会直接成为候选。
`getBudgetRules` 和 `listBudgetRateOptions` 只投影规则、可信额度上限及安全费率元数据，不返回其他主体的预算账户、凭据或密钥。
候选游标有界并绑定当前主体、完整 scope、候选内容和审核目录版本；内容变化或进程重启后旧游标须重新读取。

使用状态补充以下空间治理原因：

- `WORKSPACE_AI_NOT_PREPARED`、`WORKSPACE_AI_DISABLED`。
- `MODEL_NOT_ENABLED`、`MODEL_VERSION_NOT_ENABLED`。
- `BUDGET_RULE_NOT_CONFIGURED`、`BUDGET_RULE_ZERO`。
- `BUDGET_NOT_PREPARED` 对应本人可显式准备；READY 仍不保证余额或供应商成功。

状态保留精确 `scope/resource/binding/rate`，并返回当前允许动作 `PREPARE_OWN_BUDGET`／`CONFIGURE_WORKSPACE_AI`。
CAS／幂等冲突、授权失败和存储损坏继续按真实错误处理，不转换为“缺预算”。完整受权页面跳转由 M4 接续。

## 3. HTTP 契约

均为 POST，表中路径前缀为 `/arte/workspace/ai`。写入需要 `Idempotency-Key`；请求拒绝未声明字段。 所有 scope
均是配置目标空间，不自动切换当前聊天或修改启动／最近访问偏好。

| 端点                     | 请求要点                                                          | 结果／约束                                                                                                             |
|--------------------------|-------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `/getConfiguration`      | scope                                                             | 只读 WorkspaceView；未准备时 configuration 为 null，另有当前 workspaceVersion、templateVersion、canManage              |
| `/listModelOptions`      | scope、可选 cursor                                                | items 为实际资源固定版本及启用状态，nextCursor；不依赖本人账户                                                         |
| `/prepareConfiguration`  | scope、expectedWorkspaceVersion、expectedTemplateVersion          | 成功回执内 configuration.version=0，models 为空；不建立账户                                                            |
| `/updateConfiguration`   | scope、expectedWorkspaceVersion、expectedVersion、enabled、models | 只保存开关与 `resourceId/binding`；不接收 preset、bindings、budgets 或权限标志                                         |
| `/getBudgetRules`        | scope                                                             | configuration.rules、configuration.version（规则 CAS 版本）、workspaceVersion、canManage、canRaiseBudget、approvedCaps |
| `/listBudgetRateOptions` | scope、可选 cursor                                                | 精确 rate、MODEL／TOOL、currency、configured；不先要求启用模型                                                         |
| `/updateBudgetRules`     | scope、expectedWorkspaceVersion、expectedRuleVersion、rules       | 全量规则集合，规则和必要的已有账户额度调整同事务；不新增成员账户                                                       |

`/arte/ai-new/model-resource/getModelUsagePreparation` 和 `/prepareModelUsage` 保持原精确目标请求，并改为采用上述独立选择和规则；响应增加
allowedActions。 聊天公开配置字段继续为 `data.modelOptions`。发布模型本身不创建空间选择、规则或账户。

## 4. 前端适配与 M4 边界

- `WorkspaceAiPreparation` 只调用 getConfiguration；进入页面不再自动提交治理或建账。
- 现有 `WorkspaceAiSettings` 分为平台默认模板和目标空间治理组件。空间组件有明确首次准备按钮，模型与规则分别保存，切换同资源版本只保留一个精确选择。
- 不同时编辑两组未保存配置，避免保存其中一项时重读覆盖另一项草稿；可重新读取放弃当前修改。
- 命令按账号和完整目标 scope 保存原请求／原键。未知结果按原键确认；已确认后的读取失败只重读。成功回执核验目标、CAS
  版本、精确模型或规则金额，失配不当作成功。
- 目标改变时重新挂载编辑上下文，迟到结果与注销不回写到新目标；存储失败保留阻塞提示。
- 本批保留既有页面入口。聊天侧栏完整空间表单的移除、工作空间详情唯一完整入口、三个模型 Tab、统一个人／租户详情与双向定位由 M4
  实施。

## 5. 安装与数据边界

初始化定义更新在[AI 控制面 DDL](../scripts/arte-platform-ai-control-ddl-mysql.sql)，包括独立规则表及空间配置新载荷说明。
先安装公共空间与 AI 执行 schema，再安装本控制面 schema，以及 M2 模型／发布／共享等对应 schema。启动校验规则表可读，不自动建表或迁移。

本轮按用户明确的全新数据方案实施；新载荷不兼容旧空间 preset 和旧回执 schema，不提供隐式转换、旧 Grant 或文件目录兜底。
用户清理及重建环境后应采用最新初始化定义；本批没有连接或清理业务数据库，也未执行任何安装脚本。

## 6. 验证结果

后端 31 个测试类，共 299 项：297 通过，2 项既有 MySQL 专用测试因未配置隔离 MySQL 而跳过。 分模块为 arte-core 3 项、arte-ai 62
项、arte-app 234 项；不是将前几轮重复运行累计为结果。

主要证据：

- `WorkspaceAiGovernanceIntegrationTest`：只读零业务写入、并发首次准备、零模型时独立规则、无账户候选、同费率多资源复用、单模型取消、规则／选择当前撤销、严格
  HTTP，以及审计／Outbox／回执失败后的整笔回滚。
- 个人模型本地 HTTP 验证、生成与耐久结算；响应期间撤销规则后仍按原精确费率完成已发生费用的结算；版本切换后旧调用重新生成被拒绝。
- 持久化 Tool Chat Run／步骤、正式子预算选择器与模型子受理，规则撤销后无法重新取得子账户或外发资格；既有完整 Tool Chat
  循环回归通过。
- 租户模型在不同目标空间、不同主体下，从无账户开始经正式本人准备进入本地 HTTP 调用和结算；缺范围、模型权限或凭据消费者任一条件均拒绝。
- 普通 MEMBER 可准备本人账户；只有模型读取权限、没有模型使用或凭据消费权限的目标空间 OWNER 仍可完成空间启用配置，不能借此执行。
- 既有 FIXED 采用、目录隔离、独立免费工具、收费工具、模型 ACL、当前撤权和重新生成回归通过。

前端 15 个文件共 149 项通过：相关契约／管理组件 13 个文件 114 项，ChatNew 主页 18 项、空间切换集成 17 项，两个较重的聊天文件分别运行。
全量 tsc 保留原有 106 条诊断，和 M2 基线逐条比较无新增或减少；本批改动文件定向 Biome lint 通过。 生产构建通过；9
份本批文档的新增本地引用 12 处及代码块检查通过，手册原模板、主专项 M0～M2／V1.3 历史记录和 M1／M2 原说明保持原文，git diff
--check 通过。

未执行真实供应商调用、业务库安装、已登录业务环境的浏览器点击、生产性能或双节点验收；不以本机隔离集成代替 M5 综合验收。

## 7. 复现

在 `backend` 使用 JDK 21，离线运行以下定向集合。测试使用隔离 H2 与 127.0.0.1 模拟模型服务；MySQL 专项须另配置隔离库，不能指向业务库。
本机 Mockito agent 路径需按 Maven 仓库位置调整。

```shell
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -o -pl arte-app -am -Dtest=WorkspaceAiGovernanceIntegrationTest,ModelConfigurationM2IntegrationTest,ModelDraftIntegrationTest,ModelDraftAssemblyIntegrationTest,PersonalModelPublicationIntegrationTest,TenantModelSharingIntegrationTest,DatabaseModelPlanIntegrationTest,DatabaseAiControlIntegrationTest,DatabaseConnectionIntegrationTest,ModelResourceIntegrationTest,ModelManagementHttpIntegrationTest,ModelAclRuntimeIntegrationTest,ModelAclHttpRuntimeIntegrationTest,ModelAclExecutionIntegrationTest,ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest,ModelStoreProxyIntegrationTest,PlatformBindingJsonTest,PlatformToolBillingIntegrationTest,PlatformMybatisConfigurationTest,PlatformToolChatAccessIntegrationTest,PlatformToolManagementAccessTest,AiResourceEntitlementServiceTest,ControlPreparationTest,ControlReadPortsTest,ControlPlaneAssemblyCompatibilityTest,ToolChatLoopTest,ToolChatBudgetSelectorTest,RegenerationReconciliationIntegrationTest,PlatformPackageBoundaryTest,PublicContractBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-javaagent:/Volumes/BIWIN_NV7400_1TB/CYLJ126/workspace/Maven/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar test
```

在 `frontend`：

```shell
npm test -- --run --maxWorkers=1 src/services/platform/workspaceAi.test.ts src/services/platform/modelUsage.test.ts src/features/ainew/ModelUsagePreparationPanel.test.tsx src/services/platform/modelPlan.test.ts src/services/platform/defaultAiPolicy.test.ts src/features/ainew/ModelPlanManager.test.tsx src/features/ainew/WorkspaceAiSettings.test.tsx src/services/platform/model.test.ts src/features/ainew/PersonalModelManager.test.tsx src/services/platform/modelSharing.test.ts src/features/ainew/ModelSharingManager.test.tsx src/pages/AI/Models/index.test.tsx src/pages/AI/Tools/index.test.tsx
npm test -- --run --maxWorkers=1 src/pages/AI/ChatNew/index.test.tsx
npm test -- --run --maxWorkers=1 src/pages/AI/ChatNew/workspaceIntegration.test.tsx
npm run tsc
npm run build
```

## 8. 启动时旧目录缺少 reviewedPlans（2026-11-03 补充）

若启动堆栈指向 `AiControlData.Catalog` 的 `List.copyOf(reviewedPlans)`，说明当前目录载荷中的 reviewedPlans 缺失或为 null。
JSON 反序列化使用 record 的完整构造器，不会调用为内部装配提供空列表的重载构造器。新格式允许 `reviewedPlans: []`，但必填字段不能缺失。

可先执行以下只读查询，定位当前指针及该字段是否存在，不输出完整目录或密钥材料：

```sql
SELECT h.version AS active_revision,
       JSON_CONTAINS_PATH(r.payload, 'one', '$.reviewedPlans') AS has_reviewed_plans,
       JSON_TYPE(JSON_EXTRACT(r.payload, '$.reviewedPlans')) AS reviewed_plans_type
FROM arte_ai_control_head h
LEFT JOIN arte_ai_control_revision r ON r.version = h.version
WHERE h.id = 1;
```

head=0 表示尚未初始化，是合法状态；正修订要求字段存在且类型为 ARRAY。缺字段、null 或其他类型均与当前目录契约不符。
按本轮已确认的全新数据方案，应在自行清理／重建 AI 相关数据及最新初始化 schema 后重新配置连接、审核方案、实际模型及空间使用条件。
按表分类、结构差异、旧表及清理顺序见[M3 全新数据重置清单](MODEL_CONFIGURATION_M3_RESET.md)。 只重置 head、删除某个空间配置，或给旧载荷补上空
reviewedPlans，都不代表完成关联模型、旧空间 preset、命令回执及账本结构的重建。
本次没有自动转换、清理或修改业务数据库；若保留历史数据，需要另行设计显式迁移，不能依赖本轮全新数据流程。

补充修正：目录构造器对 reviewedPlans 给出明确必填错误；所有目录读取统一附带失败修订及处理文档提示，仍拒绝不兼容或损坏载荷。
定向回归 `DatabaseConnectionIntegrationTest`、`DatabaseModelPlanIntegrationTest`、`ModelConfigurationM2IntegrationTest` 共
26 项通过， 其中新增缺字段／显式 null 的启动拒绝及零写入检查，以及当前空目录保存后重新装配成功用例。该结果独立于上方 M3 原批次
299 项统计。
