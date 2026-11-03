# 模型配置 M1：审核方案与运行定义分离

日期：2026-11-03。实施基线：[模型配置专项 V1.1](../../../ARTE%20AI%20模型配置需求及设计.md)。

M1 已编码并完成阶段验证。M2 实际模型统一、M3 空间启用／独立预算规则、M4 四 Tab 与统一详情、M5
综合验收尚未交付；不能把本批作为完整新配置链路单独部署。本轮按全新数据设计，未清库、迁移或操作业务数据库。

后继进度：M2 已完成统一实际模型后端，见 [M2 交付说明](MODEL_CONFIGURATION_M2.md)；本文其余内容保留 M1 当批契约与验证事实。

## 1. 本批结果

- 新增独立 ReviewedModelPlan 和 ReviewedModelPlanSource，审核方案持久化在目录修订 reviewedPlans 中，不从 runtime bindings
  反推。
- publishModelPlan 只追加审核方案及必要的新费率，不追加运行 capability／binding、不授权、不建空间或账户。
- planRef 的 type 为 model-plan；方案族 v1、v2 与全局 catalogRevision 分离。相同方案族远端模型／单价／币种不变时复用原
  rate；仅改容量或用量／工具声明不发新费率身份。更换远端模型、单价或币种产生新 rate 版本。
- 显式声明 TOOL_CALLS，方案发布检查本地适配器支持；实际发布从受信方案继承能力，不依据模型名称推断。兼容性检查使用临时元数据结构，仅调用
  supports 方法，不登记、持久化、发现或执行该临时引用。
- 原发布可选方案查询改为受信方案读取；个人和租户发布均采用 planRef，原实际绑定与当前凭据／ACL／预算检查继续独立。
- getCatalog 改为安全只读投影；去掉 HTTP publishCatalog 与目录“发布新模型”表单。默认模板由 updateDefaultAiPolicy
  单独保存，不能提交执行绑定、Grant、SecretRef 或账户。
- 审核价格及默认额度返回十进制字符串；不在浏览器浮点转换后核对财务数值。
- 默认模板操作先保存原请求／原键，存储失败零写入；未知结果原键确认；确认成功但重读失败时保留 confirmed
  标记，重挂载后只重读。未保存模板、提交中、待确认及存储故障参与离开保护。

## 2. 公开契约变化

所有路径仍沿用 `/arte`、认证会话、ResultContext、公共错误、CAS 和写请求 Idempotency-Key。

| 路径                                                         | 当前 M1 契约                                                                                                                                                                       |
|--------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/workspace/ai/getModelPlans`                                | `plans[]` 返回 planRef、displayName、connection、rate、remoteModel、contextWindowTokens、价格字符串、usageReporting、toolCalls、发布信息及 catalogRevision；不返回执行绑定或凭据。 |
| `/workspace/ai/publishModelPlan`                             | expectedVersion 仍为目录 CAS；settings 增加必填 toolCalls，价格为十进制字符串。返回同族新方案版本及精确费率，幂等重放保留原版本。                                                  |
| `/workspace/ai/getCatalog`                                   | catalog 仅含 reviewedPlans、安全连接、价格字符串及 defaults:{enabled,rateCaps}；不输出 legacyGrants、legacyBudgets、运行绑定或内部凭据引用。                                       |
| `/workspace/ai/updateDefaultAiPolicy`                        | `{expectedCatalogVersion,enabled,rateCaps:[{rate,limit:{amount,currency}}]}`；amount 是十进制字符串，rate 固定且唯一。只写默认模板，不更新已有空间、不建账户。                     |
| `/workspace/ai/publishCatalog`                               | HTTP 路由已移除，不能通过原全量目录写入模型或授权。                                                                                                                                |
| `/ai-new/model-draft/listReviewedModelPlans`                 | scope、resourceId；按已有草稿连接／远端模型／容量、当前政策及适配支持返回有界发布候选 `{planRef,rate,catalogRevision,contextWindowTokens}`，不要求平台配置资格或本人预算。         |
| `/ai-new/model-resource/listPersonalModelPublicationOptions` | 原路径已移除，前端与验收夹具改接上一行。                                                                                                                                           |
| `/ai-new/model-resource/publishPersonalModel`                | M1 暂保留实际发布入口名，审核引用字段由 templateBinding 改为 planRef，类型为 model-plan；M2 再统一服务及公开命名为 publishModel。                                                  |

默认模板写命令和其每层请求 DTO 均严格拒绝额外字段。新方案查询 Controller 已加入 PlatformExceptionHandler，反序列化、验证和越权拒绝沿用公共
HTTP 状态及错误码，不落入旧全局“200＋系统错误”处理。

M1 只是把审核读取改为独立来源；草稿仍使用既有 connectionTemplate／remoteModel 字段和发布流程。M2 才实施草稿直接选方案、统一创建预设、RESOURCE
来源及通用发布服务。当前默认模板底层暂复用原 Preset 保存开关／额度，公开视图不再有模型 bindings；M3 才拆空间规则实体及首次准备命令。

受信内部 Catalog 发布方法尚用于现有控制面／工具回归夹具，没有生产 HTTP 调用方；它不能作为新建用户模型入口。旧 DATABASE
裸目录采用及实际资源运行时完整收口按 M2／M3 执行，不因本批移除一个 HTTP 路由就宣称全部后续目标已实现。

## 3. 存储、初始化及安全边界

审核方案的完整记录包括精确 planRef、connection、rate、能力、容量、发布者／时间及当时目录修订。目录修订保持不可变，连接启停及默认模板变更必须保留已有
reviewedPlans。精确历史费率保留，不改变执行或账本归属。

本批更新 `arte-platform-ai-control-ddl-mysql.sql` 的载荷说明，沿用 arte_ai_control_revision／head 的 JSON 存储；不新增物理表，不实施
M3 的空间预算规则表。新装空目录明确包含 reviewedPlans 空集合。测试夹具显式登记方案；生产没有从文件绑定或旧 JSON
自动推导审核方案的兼容／导入逻辑。

本轮按用户重建业务数据的前提实施，未对旧库或旧浏览器待确认记录做双格式兼容。旧格式恢复记录校验失败时保留警告并阻止竞争操作，不自动清除或重发；任何业务库清理、脚本安装或部署需按后续交付安排执行，M1
没有代为执行。

审核连接仍受部署 origin、协议、供应商和当前启停限制。元数据审核不访问供应商，不等于连通性验证；个人／租户模型仍须使用自己有资格维护的凭据通过既有验证及发布门控。

## 4. 阶段验收

| 场景                           | 证据与范围                                                                                                                                                                          |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 方案独立、重装配读取、精确价格 | DatabaseModelPlanIntegrationTest，审核目录重装配后 runtime bindings／capabilities 仍为空；无文件种子回退。                                                                          |
| 方案族版本、容量修改复用 rate  | 同专项验证全局连接修订插入后方案仍按 v1→v2 递增，费率不随容量修改变化。                                                                                                             |
| 单价／币种／远端模型变化       | 同专项验证新费率及不可变旧价、同键重放、不同内容冲突、CAS 和审计失败整体回滚。                                                                                                      |
| 有默认额度仍不能使用审核方案   | 同专项显式准备既有空间测试环境后，discover 仍无模型，审核引用不可解析为执行绑定；模板发布本身没有账户／空间写入。                                                                   |
| 工具能力                       | 同专项拒绝不受支持的声明；真实独立审核→个人发布用例保留 TOOL_CALLS。Tool Chat 共用新空间规则的完整验收仍属 M3／M5。                                                                 |
| HTTP 注入与安全投影            | strict fields、操作键、权限、嵌套未知字段、精确极小额度字符串；旧 publishCatalog 路由 404，无 Grant／凭据泄露。                                                                     |
| 实际个人／租户回归             | PersonalModelPublicationIntegrationTest、TenantModelSharingIntegrationTest、A1 准备专项通过；个人真实回环验证／生成／PROVIDER_BILL 结算及重装配通过。不是新空间启用语义的最终验收。 |
| FIXED／工具／公共边界          | ControlReadPorts、ControlPreparation、AssemblyCompatibility、AiResourceEntitlement、工具预算及公共包边界回归。                                                                      |
| 前端与恢复                     | 9 个定向文件 105 项通过，覆盖方案／费率回执、能力声明、模板白名单、存储失败零写、confirmed 重挂载只读及管理／聊天页面回归。                                                         |

后端按测试类去重统计 120 项：119 通过、1 项既有 MySQL 专用用例因未配置隔离 MySQL 跳过；没有失败／错误。数据库用例使用新建隔离
H2，网络用例仅本机回环模拟供应商；未执行真实 MySQL 锁竞争、业务库、真实供应商、生产 JWT 或多节点验收。

前端生产构建与定向 lint 通过。全量 `npm run tsc` 仍失败：最终 106 条诊断，与本批基线逐条一致，无新增诊断；不将生产构建成功表述为全量类型检查通过。最后补齐测试响应必填
desc 字段后，默认模板接口的 4 项测试复跑通过，105 项统计不重复累计。

本阶段不开展新的完整四 Tab 浏览器验收，不复用 A2 截图证明 M1 已有新管理页；该交互整合在 M4，综合浏览器验收在 M5。

## 5. 复现

backend 目录使用 JDK 21：

```shell
mvn -o -pl arte-app -am \
  -Dtest=DatabaseModelPlanIntegrationTest,DatabaseConnectionIntegrationTest,DatabaseAiControlIntegrationTest,PersonalModelPublicationIntegrationTest,TenantModelSharingIntegrationTest,ModelUsagePreparationIntegrationTest,TenantModelUsagePreparationIntegrationTest,PlatformBindingJsonTest,PlatformToolBillingIntegrationTest,AiResourceEntitlementServiceTest,ControlPreparationTest,ControlReadPortsTest,ControlPlaneAssemblyCompatibilityTest,PlatformPackageBoundaryTest,PublicContractBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

受限 JVM 按既有约定追加 `-DargLine=-javaagent:<本机 mockito-core-5.23.0.jar 的绝对路径>`；回环供应商测试需要允许本机端口监听。不得设置
platform.test.mysql 指向业务数据库。

frontend 目录：

```shell
npm test -- --run src/services/platform/modelPlan.test.ts src/services/platform/defaultAiPolicy.test.ts src/features/ainew/ModelPlanManager.test.tsx src/features/ainew/WorkspaceAiSettings.test.tsx src/services/platform/model.test.ts src/features/ainew/PersonalModelManager.test.tsx src/pages/AI/Models/index.test.tsx src/pages/AI/ChatNew/index.test.tsx src/pages/AI/ChatNew/workspaceIntegration.test.tsx
npm run build
npm run tsc
```

## 6. 下一步

M2 统一实际模型创建／发布、RESOURCE 来源与存储、直接采用方案的草稿及运行读取；M3 再实施实际资源版本启用、独立空间预算及当前资格；M4
才调整四 Tab 和合并个人／租户详情。本批不实现上述后续工作，不操作业务数据或部署中间状态。

## 后继入口设计说明（2026-11-03，仅文档）

[模型配置专项 V1.3](../../../ARTE%20AI%20模型配置需求及设计.md) 已将后继空间 AI 完整编辑统一到工作空间详情；模型页保留三个
Tab，聊天保留使用功能及受权跳转，平台默认模板单独留在平台审核模型与费率 Tab。本文原“四 Tab／两个空间编辑入口”等保留当批规划，不再作为
M4 目标。本次没有改动本批代码、测试数量、存储或部署事实；M3～M5 按 V1.3 接续。

## 后继 M3 交付（2026-11-03）

M3 已接入持久化空间实际模型选择、独立预算规则、显式首次准备及执行链路，详情见[M3 交付说明](MODEL_CONFIGURATION_M3.md)。
本文原验证数量和当批边界保持历史事实，不累计为 M3 新验收；完整页面搬迁及三个 Tab 整合仍由 M4 接续。
