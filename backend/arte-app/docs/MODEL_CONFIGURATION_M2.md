# 模型配置 M2：实际模型统一后端

日期：2026-11-03。实施基线：[模型配置专项 V1.2](../../../ARTE%20AI%20模型配置需求及设计.md)。

M2 完成个人／租户实际模型的统一创建、方案草稿、验证、发布、证据存储与受信运行读取。M3 的空间启用命令及独立预算规则表、M4
的统一管理页面和四 Tab、M5 的综合验收仍待交付。本批是同一开发分支上的阶段结果，不单独部署；没有清库、迁移、执行业务 DDL
或访问真实供应商。

## 1. 已实现的行为

1. 创建统一为 `{scope,name,modelKind}`，modelKind 必填 PERSONAL／TENANT。服务端固定 PERSONAL 为 WORKSPACE 归属＋PERSONAL
   凭据，TENANT 为 TENANT 归属＋TENANT 凭据；管理空间固定，不允许客户端自由组合 ownership、subject 或 credential owner。
2. 租户创建仍需当前模型创建、租户治理及当前 RBAC 创建资格；创建和同键确认都重查资格。两个预设使用同一个命令动作和包含预设的摘要，同键换预设拒绝。资源、私有
   ACL、初始租户共享策略、审计、Outbox、回执沿用原事务。
3. 草稿只提交精确 `planRef`、上下文与输出上限；连接、远端模型、能力及费率由受信方案派生。保存草稿保留方案快照用于安全展示；不能选
   latest、扩大容量或使输出占满窗口。
4. 草稿尚未保存或尚无凭据时即可按模型编辑资格读取可采用方案，不要求管理空间 AI
   已启用或本人已有聊天预算。查询按当前政策、连接及适配能力过滤，支持有界分页；游标绑定主体、管理空间、资源及审核修订。
5. 验证固定草稿／凭据版本、精确方案及完整方案摘要、连接摘要。领取、完成、查询均复核依赖；成功验证十分钟内才可发布。网络仍在领取事务提交后执行，不持
   JDBC 锁等待供应商。
6. 发布共用 ModelPublicationService／DefaultModelPublicationService；检查责任主体、预设对应的凭据种类、当前精确方案、目录
   CAS、成功验证及摘要。新来源为 RESOURCE（存储码 3），原 PERSONAL（2）只保留历史编码，未重用。实际发布继承受审工具能力，生成独立资源绑定与连接，保留不可变发布证据及原
   ACL。
7. 发布响应增加 planRef、credentialKind 和采用的精确审核目录修订。发布状态只检查资源与发布依赖：`valid=true,reason=VALID`
   不表示目标空间已启用或具备预算；不返回管理空间预算冒充目标空间条件。
8. 运行适配、凭据解析、Store、Mapper、装配及 StartupSafety 均采用中性资源发布命名。DATABASE 的裸生成绑定不进入运行配置，旧
   Grant 不再为不存在的父空间兜底；DATABASE 目录采用和旧 ACL 清单启用命令均拒绝。FIXED 的明确采用和独立工具路径保留。

## 2. 当前 HTTP 契约

表中路径均在 `/arte` 下，全部沿用认证会话、严格字段白名单、公共错误与写操作 Idempotency-Key。

| 端点                                                                                              | M2 请求／响应                                                                                                                                                  |
|---------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/ai-new/model-resource/createModelResource`                                                      | `{scope,name,modelKind}`；返回真实归属、管理 scope、credentialKind 及当前允许管理动作。归属和创建预设以后不可切换。                                            |
| `/ai-new/model-draft/listModelResources`                                                          | 原有授权过滤和有界游标；资源卡增加凭据种类与允许动作，不按创建者代替授权。                                                                                     |
| `/ai-new/model-draft/listReviewedModelPlans`                                                      | `{scope,resourceId,cursor?,size?}`，size 默认 20、最大 100；返回 `{records,catalogRevision,nextCursor}`。不再返回按旧草稿连接反推的发布方案数组。              |
| `/ai-new/model-draft/saveModelDraft`                                                              | `{scope,resourceId,expectedVersion,name,settings:{planRef,contextWindowTokens,maxOutputTokens}}`；旧 connectionTemplate／remoteModel 覆盖字段在每层 DTO 拒绝。 |
| `replaceModelCredential／revokeModelCredential`                                                   | 责任与精确代次沿用；未指定 credentialKind 时按资源归属推导，显式给出时也必须匹配固定预设。替换不转交责任或继承旧消费者批准。                                   |
| `validateModelDraft／getModelValidation`                                                          | 原受理／领取／完成及恢复职责保留；服务端证据新增 planRef／planDigest，公开响应不暴露摘要或内部责任证据。                                                       |
| `/ai-new/model-resource/publishModel`                                                             | `{scope,resourceId,expectedVersion,draftVersion,credentialVersion,validationId,planRef,catalogRevision}`；不接受费率覆盖、发布快照或额度。                     |
| `/ai-new/model-resource/getModelPublicationStatus`                                                | `{scope,resourceId,binding}`，scope 必须是固定管理空间；返回 `{binding,valid,reason,maxOutputTokens}`。目标空间使用资格仍通过 usage 接口查询。                 |
| `getModelResource／listModelResourceVersions／disableModelResource`                               | 稳定资源及不可变版本查询、资源 CAS 停用继续保留；版本安全视图包含 RESOURCE、planRef、credentialKind。                                                          |
| 原 `model-sharing/createTenantModel`、`publishPersonalModel`、`getPersonalModelPublicationStatus` | 不再注册为业务端点；调用方改接统一契约，不做双写或回退。未知路径的错误封装仍由宿主路由处理，不能将 HTTP 200 的失败体误认为成功。                               |
| `associateModelResourceVersion／updateModelAclScope`                                              | DATABASE 模式或 DATABASE 来源拒绝，不能从审核目录生成实际资源发布；FIXED 原用途继续按原资格提供。                                                              |

共享管理端点 getModelSharing、updateModelUseScopes、updateModelCredentialConsumers、listTenantModelMembers
保持独立职责与版本，没有因创建统一而合并模型 ACL、允许空间和凭据消费者批准。

创建示例：

```json
{
  "scope": {
    "tenantId": "tenant-id",
    "workspaceId": "management-workspace-id"
  },
  "name": "我的模型",
  "modelKind": "PERSONAL"
}
```

草稿示例：

```json
{
  "scope": {
    "tenantId": "tenant-id",
    "workspaceId": "management-workspace-id"
  },
  "resourceId": "server-resource-id",
  "expectedVersion": 0,
  "name": "我的模型",
  "settings": {
    "planRef": {
      "type": "model-plan",
      "id": "reviewed-example",
      "version": "v1"
    },
    "contextWindowTokens": 8192,
    "maxOutputTokens": 1024
  }
}
```

以上 ID 为定位示例，必须采用服务端实际返回值，不是初始化种子。

## 3. 存储与安装边界

- 模型资源、草稿、凭据及验证继续沿用原表；JSON 载荷更新为方案草稿和验证证据。发布快照增加 planRef 与
  credentialKind，其完整性摘要一并覆盖新字段。
- 新装定义来源约束扩展至 0～3；3 为 RESOURCE，不重新解释 2。固定发布及共享精确费率的原内容权威、冲突拒绝规则保持。
- 中性证据表为 `arte_ai_model_publication`
  ，使用新脚本 [arte-ai-model-publication-ddl-mysql.sql](../scripts/arte-ai-model-publication-ddl-mysql.sql)。保存精确绑定、管理
  scope、方案／连接摘要、草稿／凭据代次、种类、验证 ID、目录修订、输出上限、发布者及时间，不含密钥明文或密文。
- 新装仍先安装公共空间及
  ACL，再按模型资源、草稿／凭据、中性发布证据和租户共享的依赖安装。本批更新 [模型资源 DDL](../scripts/arte-ai-model-resource-ddl-mysql.sql)
  与 [草稿 DDL](../scripts/arte-ai-model-draft-ddl-mysql.sql)。旧 PERSONAL 发布／来源迁移脚本保留历史用途，不属于本轮全新安装链路。
- 启用 publication 必须同时启用 DATABASE 控制面及 model-management，使用同一 appDataSource，并提供 SM4 密钥配置、完整表、资源
  ACL 与外发凭据解析器；StartupSafety 只核验，不建表或迁移。
- 没有对旧业务数据、旧 JSON 或旧浏览器恢复记录做双格式兼容；本批也没有代替用户清理。安装与部署仍须在完整后续链路交付后单独执行。

## 4. M2 与 M3 的分界

新增 ModelSelection／ModelSelectionSource 表达“目标空间启用同一实际资源的哪个精确版本”。M2
运行读取只接受该权威端口明确给出的资源＋绑定；未装配端口或未返回选择时没有已启用模型，不自动采用发布，也不能直接提交绕过。可信版本解析继续核验资源、归属、RESOURCE
来源、完整内容、方案、凭据代次及发布证据，供 M3 接入。

M2 没有实现选择持久化、启用写命令、独立预算规则表或显式首次空间准备；不会为了中间演示新增绕过开关。旧预算／空间配置管理语义尚由
M3 替换；A1 现对未启用资源返回 MODEL_NOT_ENABLED，对旧目录发布返回 RESOURCE_PUBLICATION_REQUIRED，不自动迁移。

既有执行回归通过隔离的 TestModelSelections 显式提供选择事实，继续覆盖实际个人／租户调用、撤权、派发、外发和结算。该测试端口只在
test 源码中，不是生产启用服务，也不能用来宣称 M3 的数据库选择、规则或并发验收已完成。M2 专项另有“发布不启用、发现为空、直接提交拒绝”的真实装配用例。

前端本批只适配统一请求、方案分页、草稿只读派生字段、RESOURCE 与 valid 状态，保留既有页面结构和操作恢复保护。个人／租户创建布局合并、创建后详情交接、四
Tab 重排、两个空间配置入口整合及宽窄屏浏览器验收仍由 M4／M5 实施。

## 5. 验证证据

后端最终按测试类去重 218 项：216 通过、2 项 MySQL 专用场景因未提供隔离 MySQL 跳过；没有失败或错误。覆盖 25 个测试类，重命名后完成
clean 重建，最终源码又完成定向回归。

前端最终按文件去重 11 个文件、123 项全部通过：ChatNew/index 18 项、workspaceIntegration 17 项，另 9 个管理／契约文件 88
项。为处理重型用例并行争用，分别串行复跑，未调高测试超时；更新旧“发布状态检查预算”断言，另补齐重复游标及跨页修订变化的拒绝用例。重复执行不累计为额外通过数量。

最终生产构建及定向 lint 通过，git diff --check 通过；7 份文档的新增本地引用／代码块检查及用户手册原模板保留核对通过。全量
tsc 仍有 106 条诊断，与 M1 基线逐条一致，无新增；未把构建成功写为全量类型检查通过。

ModelConfigurationM2IntegrationTest 覆盖：无草稿时选择方案、容量及 latest 拒绝；发布只保存 RESOURCE
证据且不建账户／空间；有效发布在未启用时无法发现／提交；同引用方案摘要冲突整体拒绝；严格草稿 HTTP
与旧路由无写入；当前执行政策在同物理事务核验；统一创建预设、同键换预设和归属伪造拒绝。

后继实际资源、租户共享、原键恢复、凭据轮换及退出再加入、审计失败回滚、并发发布、回环供应商调用／结算、重装配、来源内容冲突、FIXED、工具及公共包边界均纳入定向回归。旧
DATABASE 目录采用／迁移正例改为关闭入口的拒绝用例；资源执行授权竞争的正反例仍在实际资源及租户专项中保留。没有把旧不可达流程当作已兼容继续测试。

本机使用新建隔离 H2 和回环模拟 HTTP；MySQL 专用项未提供隔离库时跳过。未验收真实 MySQL 竞争、真实供应商、生产 JWT、多节点及新四
Tab 浏览器流程。

## 6. 复现

backend 目录使用 JDK 21；重命名后先执行一次 clean，避免旧 Mapper XML 或旧类留在 target 中。以下命令只运行隔离测试：

```shell
mvn -o -pl arte-app -am -Dtest=ModelConfigurationM2IntegrationTest,ModelDraftIntegrationTest,ModelDraftAssemblyIntegrationTest,PersonalModelPublicationIntegrationTest,TenantModelSharingIntegrationTest,DatabaseModelPlanIntegrationTest,DatabaseAiControlIntegrationTest,DatabaseConnectionIntegrationTest,ModelResourceIntegrationTest,ModelManagementHttpIntegrationTest,ModelAclRuntimeIntegrationTest,ModelAclHttpRuntimeIntegrationTest,ModelAclExecutionIntegrationTest,ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest,ModelStoreProxyIntegrationTest,PlatformBindingJsonTest,PlatformToolBillingIntegrationTest,PlatformMybatisConfigurationTest,AiResourceEntitlementServiceTest,ControlPreparationTest,ControlReadPortsTest,ControlPlaneAssemblyCompatibilityTest,PlatformPackageBoundaryTest,PublicContractBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false clean test
```

受限 JVM 沿用 `-DargLine=-javaagent:<本机 mockito-core-5.23.0.jar 绝对路径>`；HTTP 集成需要允许本机回环端口。不得将
platform.test.mysql 指向业务数据库。

frontend 目录执行定向 Vitest、生产 build、tsc 及 lint；完整文件清单为
modelPlan、defaultAiPolicy、ModelPlanManager、WorkspaceAiSettings、model、PersonalModelManager、modelSharing、ModelSharingManager、AI/Models、ChatNew/index、ChatNew/workspaceIntegration
的测试。重型聊天用例独立串行执行，避免与构建或 JVM 回归争用资源；测试超时阈值未提高。

```shell
npm test -- --run --maxWorkers=1 src/pages/AI/ChatNew/index.test.tsx
npm test -- --run --maxWorkers=1 src/pages/AI/ChatNew/workspaceIntegration.test.tsx
npm test -- --run --maxWorkers=1 src/services/platform/modelPlan.test.ts src/services/platform/defaultAiPolicy.test.ts src/features/ainew/ModelPlanManager.test.tsx src/features/ainew/WorkspaceAiSettings.test.tsx src/services/platform/model.test.ts src/features/ainew/PersonalModelManager.test.tsx src/services/platform/modelSharing.test.ts src/features/ainew/ModelSharingManager.test.tsx src/pages/AI/Models/index.test.tsx
npm run build
npm run tsc
```

## 7. 下一步

M3：实现空间实际模型精确版本启用、独立费率批准规则与显式首次准备，再贯通查询、A1 准备、受理、派发、外发和账本。M4 才交付统一管理布局；M5
进行新装、角色、故障、授权竞争及完整浏览器验收。本批不自动开始下一阶段或部署。

## 后继入口设计说明（2026-11-03，仅文档）

[模型配置专项 V1.3](../../../ARTE%20AI%20模型配置需求及设计.md) 已将后继空间 AI 完整编辑统一到工作空间详情；模型页保留三个
Tab，聊天保留使用功能及受权跳转，平台默认模板单独留在平台审核模型与费率 Tab。本文原“四 Tab／两个空间编辑入口”等保留当批规划，不再作为
M4 目标。本次没有改动本批代码、测试数量、存储或部署事实；M3～M5 按 V1.3 接续。

## 后继 M3 交付（2026-11-03）

M3 已接入持久化空间实际模型选择、独立预算规则、显式首次准备及执行链路，详情见[M3 交付说明](MODEL_CONFIGURATION_M3.md)。
本文原验证数量和当批边界保持历史事实，不累计为 M3 新验收；完整页面搬迁及三个 Tab 整合仍由 M4 接续。
