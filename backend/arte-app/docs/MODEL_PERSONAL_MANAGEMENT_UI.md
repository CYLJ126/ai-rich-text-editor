# 个人模型管理 4-03：管理页面与场景验收

日期：2026-10-31。

在 [4-01 配置准备](MODEL_PERSONAL_PREPARATION.md) 与 [4-02 发布和执行](MODEL_PERSONAL_PUBLICATION.md) 上接入普通用户管理页面。
资源仍归当前空间，个人凭据责任、模型动作和调用用户预算分别核验。文章仍待 arte-article 拆分时处理。

## 页面与操作

- `/AI/ChatNew` 右侧“模型管理”Tab 增加“可用模型／我可管理”，管理内容嵌入现有辅助栏。首次打开才读取管理数据，切回目录保留管理表单。
- `/AI/Models` 提供独立页面，共用当前账号的公共空间选择器。入口不依赖空间 AI 准备、可执行模型或预算；ChatNew 准备失败页也提供此链接。
- 管理列表使用服务端授权过滤及不透明游标，保留草稿、停用、缺预算项，也支持过滤后空页继续翻页。不按 createdBy 过滤。
- 流程：创建私有资源 → 保存审核模板和远端模型草稿 → 配置本人密钥 → 验证 → 选择精确审核模型／费率方案 → 发布固定版本 →
  查看当前状态 → 返回目录明确应用聊天配置。
- 表单分别使用管理版本、草稿版本和凭据代次。历史发布可分页查看，草稿保存不会覆盖旧发布；停用针对稳定资源的全部版本，操作前确认影响范围。
- 凭据输入不回填，保存后立即清空输入框；替换、撤销和验证受个人责任限制。具有模型编辑或使用权不代表取得他人的密钥用途。
- 页面展示个人发布／平台目录来源，以及无兼容预算、凭据代次失效、用途拒绝、模板变化、空间 AI 不可用等安全原因。发布不会创建预算或提高额度。

UI 只开放 `workspace + model:create` 及服务端逐项返回的 `model:edit/validate/publish/disable/use`，不以 OWNER、ADMIN
或菜单角色替代动作检查。 密钥维护还要求 credential.mayMaintain；首次配置按编辑资格提交，最终由后端确定责任主体。功能关闭、权限或策略变化时保留明确提示，不转调平台管理员接口。

本批采用审核模板，用户不能输入任意 endpoint 或自报费率。共享授权编辑、租户级创建、跨空间复用、团队凭据使用授权和共享预算池保持后续范围；本批不把可读列表当作这些功能已完成。

## 恢复、隔离与聊天一致性

`src/services/platform/model.ts` 连接真实 4-01／4-02 及公共动作检查端点，复用宿主 Bearer 传输和 ResultContext。
响应经过结构和资源／范围检查，并只返回安全 DTO 字段；页面不保留 endpoint、SecretRef、密文或内部授权证明。

所有管理写操作提交前在 sessionStorage 保存原请求及 Idempotency-Key，键含 userId、tenantId、workspaceId 和格式版本。
网络、408、5xx、响应无法确认及幂等参数冲突保留原操作；刷新只恢复定位，不自动提交。重试使用同一请求和同一键。 普通 CAS
冲突不自动换版本覆盖，输入留在页面，须明确放弃并读取当前内容或检查后再次提交。

凭据恢复描述不含 token，白名单严格拒绝含 token 的记录。待确认期间密钥仅在内存引用保留，允许主动清除；刷新后只能重新输入原操作的同一密钥确认。
本地存储失败时不发送新的写请求；恢复记录损坏时阻止竞争操作。验证记住 validationId，查询原验证不会重新探测供应商。

未保存配置、新建名称、密钥输入、内存临时密钥、正在提交和恢复存储异常参与离开提示及公共空间切换保护。
组件按账号和完整空间重建，取消旧读取并丢弃迟到响应；退出登录清除当前敏感输入和详情。浏览器记录清理沿用既有账号会话机制。

发布成功只刷新发现，不自动应用新模型。若当前会话所用资源发生凭据替换／撤销、发布或停用，页面撤销旧配置偏好并要求重新检查和明确应用；
已受理调用、历史消息、原未确认聊天请求及执行归属保持原有语义。服务器受理、派发与外发仍做最终当前授权检查。

## 验收结果

| 场景                                             | 证据与结果                                                                                                                            |
|--------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| 普通用户创建、保存、本人密钥、验证、不可变发布   | 实际浏览器操作真实 PersonalModelManager，经过 MVC、公共授权、H2、SM4 与回环供应商，发布版本 v1 成功                                   |
| 发布进入当前目录、兼容用户预算                   | 浏览器发现 PERSONAL 项，显示 1 个兼容预算；原平台项仍可发现                                                                           |
| 使用个人发布执行及结算                           | 浏览器按钮提交精确绑定，真实 ChatService、GenerationDispatcher、Invocation／Attempt 和账本完成 SUCCEEDED／SETTLED；日志 PROVIDER_BILL |
| 三类版本、动作拒绝、凭据责任分离                 | 前端组件与服务测试通过；后端专项复核真实授权及事务                                                                                    |
| 未确认保存／密钥、刷新恢复、原键重试、存储失败   | 前端自动化覆盖，密钥不持久化，刷新不自动提交，存储失败零写请求                                                                        |
| 草稿 CAS 冲突、发布不自动应用、停用确认          | 前端自动化覆盖，不自动覆盖、不发送聊天请求，停用提交管理版本                                                                          |
| 缺预算、空过滤页、功能 404、退出和跨空间迟到响应 | 前端自动化覆盖；后端发布和 HTTP 专项覆盖相应资格与运行状态                                                                            |
| 撤销／轮换阻止旧发布、分享不转交密钥用途         | 既有真实后端专项本批回归通过；未重复宣称为本批浏览器操作                                                                              |
| 窄屏和刷新后浏览器复验                           | 已实现容器自适应；后续浏览器工具连续两次审批检查超时，未完成窄屏截图和刷新／停用的浏览器复验，不计为已通过                            |

浏览器执行证据：Invocation `337b292f-1297-4021-afb3-f1ead92dbb6e`，结果 SUCCEEDED，结算 SETTLED。
验收供应商、凭据和账号均为测试数据；固定测试会话通过正式 MVC 进入公共授权，未覆盖生产 JWT 登录、真实供应商或业务数据库。
临时浏览器后端和前端服务已关闭，H2 已清理。

后端专项 59 项：0 失败、0 错误、1 项 MySQL 专用用例在 H2 跳过；浏览器桥 1 项正常结束。 前端相关回归 18 个文件、195 项：192 通过、3
项既有布局测试失败。失败源于测试预期侧栏 300px 而 HEAD 实现 SIDE_MIN=350，本批未修改该布局实现。 最终受影响定向回归 4
个文件、44 项全部通过（服务 9、管理组件 17、配置刷新 1、ChatNew 17）。生产构建通过并生成 AI/Models 与 AI/ChatNew 路由。 全项目
TypeScript 仍有原有 106 项错误；与本批前的诊断一致，本批文件无新增类型错误。未执行业务库 DDL、配置生产密钥或重启现有节点。
新增管理主文件的 Biome lint 通过，管理组件、独立页及 ChatNew 的 Ant Design lint 均为 0 项问题。

## 复现

前端目录执行：

```shell
npm test -- --maxWorkers=2 src/services/platform/model.test.ts src/features/ainew/PersonalModelManager.test.tsx src/features/ainew/ChatConfigurationForm.test.tsx src/pages/AI/ChatNew/index.test.tsx
npm run build
```

后端目录使用 JDK 21，受限 JVM 按既有约定追加 Mockito javaagent：

```shell
mvn -o -pl arte-app -am -Dtest=ModelDraftIntegrationTest,ModelDraftAssemblyIntegrationTest,PersonalModelPublicationIntegrationTest,ModelManagementHttpIntegrationTest,Sm4ModelCredentialCipherTest -Dsurefire.failIfNoSpecifiedTests=false test
```

显式启动本机浏览器桥（默认跳过；禁止设置 platform.test.mysql）：

```shell
mvn -o -pl arte-app -am -Dtest=PersonalModelBrowserAcceptanceTest -Dmodel.browser.acceptance=true -Dsurefire.failIfNoSpecifiedTests=false test
```

等待 arte-app/target/model-browser-ready 后，从 frontend 执行 `node scripts/model-s403-browser/server.mjs`，打开
`http://127.0.0.1:5233`。 仅在该隔离场景使用源码中的虚拟测试凭据。桥接只监听回环地址，20 分钟自动结束，也可创建
arte-app/target/model-browser-stop 提前停止。 此页面复用正式组件和真实管理 API；“执行一次隔离聊天”按钮只属于验收夹具，不是正式聊天界面替代品。

正式部署继续按 4-01／4-02 安装表、审核目录、SM4 密钥环、个人凭据政策与功能开关；4-03 无新增数据库表或自动启用动作。
