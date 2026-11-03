# A2：模型到聊天闭环

日期：2026-11-03。

后继设计：[模型配置重构 M0](../../../ARTE%20AI%20模型配置需求及设计.md) 已定稿，M1～M5 尚未编码。后续统一实际模型来源、个人／租户管理及独立空间预算；本文保留
A2 当批实现和截图／测试事实，不代表新四 Tab 或新 API 已交付。

在 [A1 模型使用准备](MODEL_USAGE_PREPARATION_A1.md) 上接入管理页、聊天选型和原操作恢复。实际执行空间仍为页面当前公共空间；额度来源和执行授权沿用
A1 与原聊天链路。

## 页面流程

1. 在 `/AI/Models` 或 ChatNew 的“我可管理”选择已发布精确版本，读取“聊天使用准备”。读取不初始化账户。
2. 有当前批准规则时，由用户点击“准备此模型用于聊天”。请求只提交执行空间、资源和精确 binding，不接受额度、主体、费率或预算引用。
3. 成功后重新查询当前状态；READY 时可点击“返回聊天并选择此版本”，返回前再次查询，保存按账号和完整空间隔离的选型意图。
4. 聊天显示待选择提示。点击“重新发现并选择此模型”，从服务端重新发现，并同时匹配资源 ID 和精确 binding；版本不可用时提示原因，不替换成其他候选。
5. 检查兼容预算与参数后手动“应用配置”，才可按原聊天流程发送。应用对应版本后清除本次选型意图。

规则批准额度明确标为“非账户余额”。缺规则、零额度、空间 AI 关闭、凭据过期或用途拒绝等分别展示安全原因和修复方向；不开放客户端自行提高额度。
准备及返回不自动应用配置，也不发送内容。已有调用、原未确认聊天请求和重新生成继续使用原执行快照，受理、派发和外发仍核验当前资格。

## 恢复与隔离

- 写入前保存原请求和 Idempotency-Key；存储失败时不发送。未知结果保留原请求和原键，刷新只读取，不自动重发。
- 用户主动确认未知结果时重用原键。已收到成功回执则保存 confirmed 状态；之后读取失败只重读，不创建新的准备操作。
- 当前状态读取成功并清除恢复记录后，才释放离开和切换保护；清除失败时继续阻止竞争操作。
- 记录按账号、tenantId 和 workspaceId 隔离，使用严格字段白名单，不持久化密钥、额度或授权证据。退出、卸载及目标变化后丢弃迟到响应。
- 待选择提示仅是意图；聊天重新发现后仍须明确应用。取消、旧版本失效及恢复异常保留可见提示。

## 验证

- 定向前端回归：7 个文件、77 项全部通过，覆盖服务契约、管理入口、未知结果原键恢复、confirmed
  只重读、存储失败零写入、撤权、账号隔离、精确版本发现、禁止自动应用及 ChatNew 手动应用流程。
- 前端生产构建通过，生成 AI/Models 与 AI/ChatNew 路由。
- 全项目 TypeScript 检查仍有既有 106 项诊断，A2 修改文件无诊断；定向 Biome lint 通过。
- 浏览器验收使用正式 PersonalModelManager、准备面板、提示和 ChatConfigurationForm，连接真实 MVC、公共授权、隔离 H2
  及回环供应商。浏览器桥采用固定测试会话；不代表生产 JWT、真实供应商、业务数据库或多节点验收。
- 浏览器从零账户创建并发布个人模型，模拟准备已提交但响应丢失，重挂载后按原键确认；准备记录和账户均保持 1
  条。返回聊天、重新发现后发送仍禁用，手动应用后完成 SUCCEEDED／SETTLED，最终调用／准备／账户计数为 1／1／1。390px 容器下配置及结果可见。
- 浏览器后端桥 1 项测试正常结束，无失败、错误或跳过；临时前后端服务已关闭，隔离 H2 已清理。`git diff --check` 通过。

浏览器证据：[应用前发送禁用](model-a2-acceptance/explicit-apply.png)、[执行与结算成功](model-a2-acceptance/settled.png)、[390px 容器](model-a2-acceptance/narrow.png)
。预算摘要是发送前读取的快照，结算后须刷新预算，不将该截图中的摘要当成结算后余额。

本批不新增业务 DDL、生产功能开关或部署操作。文章、成员治理、空间关闭、跨空间复用、共享预算池与团队凭据用途继续按后续阶段处理。

## 复现

在 frontend 目录执行：

```shell
npm test -- --run src/services/platform/model.test.ts src/services/platform/modelUsage.test.ts src/features/ainew/PersonalModelManager.test.tsx src/features/ainew/ModelUsagePreparationPanel.test.tsx src/features/ainew/ChatConfigurationForm.test.tsx src/pages/AI/Models/index.test.tsx src/pages/AI/ChatNew/index.test.tsx
npm run build
```

隔离浏览器桥默认跳过。使用 JDK 21，在 backend 目录执行以下命令，禁止设置 platform.test.mysql；受限 JVM 可按 A1 说明追加
Mockito javaagent：

```shell
mvn -o -pl arte-app -am -Dtest=PersonalModelBrowserAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false -Dmodel.browser.acceptance=true -Dmodel.browser.a2=true test
```

等待 arte-app/target/model-browser-ready 后，在 frontend 执行 `node scripts/model-a2-browser/server.mjs`，打开
`http://127.0.0.1:5234`。A2 模式清空的账户和定义仅属于隔离 H2，保留已批准规则，必须通过准备入口建立预算。
夹具可模拟提交成功后的响应丢失、组件重挂载及查看账户／准备操作／调用数量。聊天按钮调用真实受理与派发服务；页面壳和固定测试会话不替代正式
ChatNew 路由及登录验收。 桥只监听回环地址，20 分钟自动结束，也可创建 arte-app/target/model-browser-stop
提前停止。测试凭据只适用于本机模拟供应商。
