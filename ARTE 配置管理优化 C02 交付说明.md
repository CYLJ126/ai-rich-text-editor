# ARTE 配置管理优化 C02 交付说明

日期：2026-11-05。状态：C02 编码与本机定向验证完成；下一批为 C03。

对应[实施计划](ARTE%20配置管理优化实施计划.md)及[C01 契约](ARTE%20配置管理优化%20C01%20基线与实施契约.md)
。本批仅改前端配置目标、读取及离开保护，不执行数据库操作、权限分配、旧数据清理或部署。

## 1. 完成内容

### 1.1 配置目标与使用空间隔离

- 租户政策页、模型页改用独立治理目录；公共当前空间通过只读订阅作为初始参考，不挂载公共目录的初始化/回退加载链。
- 租户政策页维护本地目标，选择后只调用 `getWorkspace` 核验元数据；不再通过公共 select 改变聊天空间或最近访问偏好。同租户换空间保留租户政策编辑器。
- 独立治理目录缺少个人偏好时仍可浏览当前受权空间，不初始化个人租户或默认空间；目录选择不会写公共
  currentWorkspace、lastScope 或派发使用空间切换事件。
- 配置修改触发同空间元信息刷新时只更新元数据，不重写公共当前空间键。公共 select 在提交保护没有实际放行时返回
  false，避免调用方将“已发起选择”当成“已切换成功”。
- 三个页面使用统一配置目标提示，展示名称、作用范围及辅助租户/空间标识。模型页的平台页签明确展示平台范围。
- 租户政策页保留 C03 前的范围限制：当前候选仅来自有可访问工作空间的租户；页面明确说明此限制，没有伪造独立租户全集。

实现：[目录状态](frontend/src/features/workspace/useWorkspace.ts)、[租户政策页](frontend/src/pages/Workspace/Policies/index.tsx)、[模型页](frontend/src/pages/AI/Models/index.tsx)、[空间页](frontend/src/pages/Environment/Workspaces/index.tsx)、[统一目标提示](frontend/src/features/configuration/ConfigurationTarget.tsx)。

### 1.2 统一离开保护

新增 [ConfigurationBoundary](frontend/src/features/configuration/ConfigurationBoundary.tsx)
，在应用布局中协调配置编辑器；页面级边界兼容单独挂载，并为局部对象选择隔离命名空间。

| 状态/操作                                                             | 当前处理                                                                 |
|-----------------------------------------------------------------------|--------------------------------------------------------------------------|
| 普通政策、空间元信息、模型配置草稿、空间 AI 模型选择                  | 保存并继续、放弃修改并继续、留在本页                                     |
| 连接/模型方案发布草稿、临时密钥、授权草稿、预算批准、平台默认模板草稿 | 保留各自明确提交入口；离开时可明确放弃尚未提交修改，不自动发布/授权/提额 |
| 提交中                                                                | 不重复提交，不允许通过放弃丢失原操作                                     |
| 结果待确认                                                            | 保留原目标/请求/操作键，在原业务入口确认；离开弹窗不重新执行命令         |
| 本地恢复异常                                                          | 提示具体未完成项，保留原记录，不通过清空记录解锁                         |
| 并发冲突或表单校验失败                                                | 停止后续保存与导航，保留当前目标及草稿                                   |

编辑器向协调器登记实时状态与允许的保存/放弃能力。多个草稿按顺序处理，每项成功后等待状态及并发版本更新，重新读取下一项能力；父级政策变化导致待保存子表单失效时停止，要求核对后继续，不自动覆盖。

局部模型管理空间选择只处理模型范围，平台草稿继续保留；跨页面导航检查全部相关未完成项。原有恢复键、服务端授权、CAS 与幂等提交约定保持。

接入：[政策](frontend/src/features/workspace/PolicyEditor.tsx)、[空间信息](frontend/src/features/workspace/WorkspaceDetailForm.tsx)、[空间 AI](frontend/src/features/workspace/WorkspaceAiGovernanceEditor.tsx)、[模型](frontend/src/features/ainew/ModelManager.tsx)、[授权](frontend/src/features/ainew/ModelSharingManager.tsx)、[本人准备](frontend/src/features/ainew/ModelUsagePreparationPanel.tsx)、[平台连接](frontend/src/features/ainew/ConnectionManager.tsx)、[模型方案](frontend/src/features/ainew/ModelPlanManager.tsx)、[默认模板](frontend/src/features/ainew/DefaultAiTemplateEditor.tsx)。

### 1.3 路由、缓存与账号保护

- 站内路由、公共空间选择、配置对象选择和政策/模板弹窗关闭共用保护协调器；浏览器刷新继续保留原生未完成提示。
- 顶部关闭标签、关闭其他标签、关闭全部标签先处理草稿，再清除缓存；切换被阻止时不提前改变激活标签。
- 多个缓存配置页面共享一个协调器，避免同一次导航出现多个确认弹窗；保留已有使用页面未完成操作的保守保护。
- 在确认保存/放弃后执行路由时，先结束保护处理中的互斥状态，避免自己的后续导航再次被阻止。新增真实内存路由测试覆盖此情况。
- 租户目标异步读取在完成时再次检查新产生的草稿。账号会话结束后，关闭旧目标展示并忽略迟到目标响应；处理中保存即使稍后返回，也不执行旧账号的导航延续。

实现：[应用布局](frontend/src/app.tsx)、[顶部标签](frontend/src/components/TabsLayout/index.tsx)、[已有 AI 离开保护适配](frontend/src/features/ainew/aiNavigation.ts)、[政策弹窗](frontend/src/features/workspace/WorkspacePolicyDialog.tsx)。

## 2. 验证结果

### 2.1 定向前端回归

24 个文件、200 项测试通过，Vitest 报告耗时 18.64 秒。覆盖配置/导航协调器、顶部标签、空间目录与表单、政策与恢复、模型/密钥/发布/授权/使用准备、三个配置页面。

```sh
npm test -- src/components/TabsLayout/index.test.tsx src/features/configuration src/features/workspace src/features/ainew/PersonalModelManager.test.tsx src/features/ainew/ConnectionManager.test.tsx src/features/ainew/ModelPlanManager.test.tsx src/features/ainew/DefaultAiTemplateEditor.test.tsx src/features/ainew/ModelSharingManager.test.tsx src/features/ainew/ModelUsagePreparationPanel.test.tsx src/features/ainew/WorkspaceAiUsagePanel.test.tsx src/features/ainew/aiNavigation.test.ts src/pages/Workspace/Policies/index.test.tsx src/pages/Environment/Workspaces/index.test.tsx src/pages/AI/Models/index.test.tsx
```

新增关键证据：

| C01 场景        | 本批证据                                                                                                    |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| CA01/CA02       | 治理目录无个人偏好仍读取；不初始化、不修改公共空间键/偏好、不发切换事件；租户/模型局部选择不调用公共 select |
| CA03/CA04       | 真实路由被草稿阻止；明确放弃或保存成功后才继续；顺序保存失败即停止；顶部缓存保留到确认后                    |
| CA05            | pending/submitting/blocked 均禁用保存/放弃延续；原业务恢复回归继续核验原键；预算/密钥不隐式提交             |
| CA28 的前端部分 | 账号结束取消延续，迟到目标响应不恢复旧账号目标；异步选择期间产生的新草稿不会丢失                            |

这些是本机单元/组件回归，不等同于已登录业务浏览器、多节点或生产验收。服务端命令行为未因本批前端验证而新增背书。

### 2.2 构建、类型与静态检查

- 生产构建通过，`utoo pack v1.5.11 built in 12842ms`，进程退出 0；生成
  Environment/Tenants、Environment/Workspaces、Environment/Models 路由。
- 沙箱内原生编译阶段停滞；停止诊断进程后，在沙箱外执行相同 `npm run build` 通过。配置打印的顶层 `@utoo/pack`
  版本读取提示不是构建失败结论，构建器实际可从嵌套依赖加载。
- 全项目 TypeScript 检查仍有 107 项既有诊断。用隔离源码副本恢复本批前的跟踪文件、处理生成代码的绝对路径后对照，当前 107
  项均可在基线中找到，本批未新增诊断；不将全项目类型检查表述为通过。
- 28 个受影响前端文件的定向 Biome lint：0 errors、8 warnings；警告为已有非空断言等，未扩大为无关代码清理。
- 差异空白检查通过。开始时已有的手册、后端、配置和 C01 文档保持；实施计划仅更新本批状态与记录。

## 3. 后续边界与交接

- C03 补独立租户目录、详情/资格及服务端租户过滤；当前候选范围限制仍存在。
- 工作空间详情分区及新增显式进入按钮属于 C05；现有使用侧空间选择入口仍走公共选择服务，配置页本身不自动切换。
- 跨页面任务链接的消费/精确模型定位属于 C06，C02 不将当前空间页忽略 URL 的旧行为改为已交付新定位。
- 完整诊断、预算关联、政策效果及租户/成员治理继续按 C07～C11 实施。
- 本批无后端代码、DDL、菜单或业务授权变更，无业务数据操作、部署或外部供应商调用；真实登录浏览器与目标环境验证留待后续相应验收。

C02 完成，C03～C12 未开始。
