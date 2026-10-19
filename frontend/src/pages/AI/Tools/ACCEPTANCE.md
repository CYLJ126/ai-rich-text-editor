# AI TOOL 页面分批验收记录

S2-07（2026-10-19）范围：`/AI/Tools` 的目录／个人配置；不包含 S2-08 的调用提交及结果跟踪，S2-09 为只读集成验收。

## 自动验证

- 前端：`node node_modules/vitest/vitest.mjs run src/features/aitool src/services/arte-ai src/features/workspace`，143
  项通过。本批 31 项覆盖精确 JSON／请求白名单、游标空页／停滞、迟到读取、重复点击／同键确认、恢复损坏／存储失败、账号／空间隔离、配置编辑与启停、有效预览及非兼容升级确认。
- 后端：沿用 `backend/arte-ai/TOOL_IMPLEMENTATION.md` §13 选定兼容命令。arte-ai 775 项，771 项通过／4 项 Redis 测试跳过；平台授权
  26 项全通过。新增 9 项 HTTP／H2 用例覆盖默认身份并发、跨用户、重放／异义冲突、事务内撤权、用户编辑／停用保留、配置纠正、精确数值及
  v2 回执、服务器过滤和审计回滚。
- 更新旧管理路由数量断言为 17；旧工具过期租约夹具改为等待真实时钟越过租约，未改变生产执行／恢复逻辑。
- 本批组件 Biome lint 与差异检查通过。全仓 `tsc --noEmit` 仍有未修改的文章编辑器、旧页面及既有搜索验收脚本等诊断；本批文件无类型诊断。完整
  Umi build 在当前环境等待期间无后续构建输出，已停止，不列为通过；生成路由已包含唯一 `AI/Tools/index`，本批页面通过隔离 Vite
  编译及浏览器走查。

## 浏览器走查

使用 `frontend/scripts/tool-s207-browser` 和显式 `ToolBrowserAcceptance#browserBridge`：固定测试主体／空间，经 HTTP 进入真实
Controller、管理用例、MyBatis 和临时 H2。没有调用工具、业务数据库或业务 Redis；宿主生产登录／菜单／RBAC 未以此替代验证。

| 场景             | 观察                                                                                 |
|------------------|--------------------------------------------------------------------------------------|
| 桌面目录→详情    | 用途、发布状态、只读风险可见，版本及 Schema 折叠                                     |
| 准备默认配置     | 显式命令创建本人默认绑定，随后读取当前修订；没有执行工具                             |
| 编辑／保存／回读 | 名称与普通字段正确回读；0.12345678901234567890123456789 和 9007199254740993 原文保留 |
| 有效限制预览     | 未修改配置也可只读预览；不产生保存或工具调用                                         |
| 停用配置         | 页面回读“已停用”，显示“启用配置”，没有自动恢复启用                                   |
| 390px 窄屏       | 筛选纵向排布、卡片与抽屉可用，操作换行、内容可滚动                                   |

![桌面保存与精确回读](acceptance/binding-saved.png)

![窄屏目录](acceptance/catalog-narrow.png)

![窄屏配置](acceptance/binding-narrow.png)

## 部署与未验证范围

菜单 DML 已交付，未执行：`backend/arte-app/scripts/arte-ai-tool-menu-dml-mysql.sql`；没有自动授予菜单或操作权限，没有开启
TOOL／分类调度。 默认命令的 tool-binding-receipt v2 要求先统一升级管理读写节点，回退构建须保留 v2 读取能力。 尚未验证真实
MySQL／Redis、多节点、宿主生产登录／RBAC／菜单授予、真实工具、性能或完整生产构建；这些不能由 H2、受控身份和单机页面测试替代。
普通未保存草稿只在当前页面内保存；关闭详情／切换空间／刷新离开前提示，未确认命令独立存储以恢复。

## S2-08 调用预览与结果（2026-10-19）

本批新增“工具调用”页签，目录／配置页面仍保留；覆盖预览、确认提交、原调用状态／结构化结果、错误、停止与刷新恢复。 前端相关测试共
157 项通过；后端兼容回归与最终 119 项受影响范围验证见 TOOL_IMPLEMENTATION.md §16。Biome lint 与差异检查通过； 全仓 tsc
仍有既有诊断，本批文件无诊断；未重跑完整 Umi 生产构建。

隔离运行方式（JDK 21；终端在仓库根、前端命令在 frontend）：

```sh
mvn -o -f backend/pom.xml -pl arte-ai -am -Dmaven.compiler.proc=full -Dmaven.compiler.release=21 \
  '-Dtest=ToolRunBrowserAcceptance#browserBridge' -Dsurefire.failIfNoSpecifiedTests=false test
node scripts/tool-s208-browser/server.mjs
```

桥仅绑定 127.0.0.1:15218，Vite 为 5218。固定测试身份与空间，真实 Controller／MyBatis／H2／Worker； fixture 的 Count
是受控测试适配器，不代表已登记生产文本计数工具或验证生产 RBAC。 通过 target/tool-run-browser-stop 停止桥并关闭本次 Vite，15
分钟有界自动退出。页面验证后临时服务已关闭。

| 场景 | 可观察事实 |
|---|---|
| 桌面预览 | 显示实际工具 v1、修订 r1、参数、有效配置、期限／尝试／输出上限与 FREE；预览不执行 |
| 修改参数 | 原确认方案消失，必须重新预览 |
| 公开提交至 Worker | 返回原调用编号，后续读取 SUCCEEDED、NOT_RESERVED 和结构化结果 |
| 页面刷新 | 保留 a5ddea03-87ed-4e78-98b4-62897d2782c1，按原 ID 读取成功结果，不产生第二次执行 |
| 390px | 卡片、参数与 JSON 换行，操作可用，结果及预览确认按钮可滚动到达 |
| 桥关闭统计 | invocations=1，reservations=0；新调用预览未增加执行次数 |

UNKNOWN、断网、恢复撤权、损坏存储及停止回执用自动化测试验证；本次浏览器未模拟全部故障。 新增最近 20 个本会话调用编号的只读恢复；UNKNOWN
明确确认结束查看后保留原编号，不自动重发。 真实业务权限／工具、生产构建、多节点／MySQL／Redis 和性能仍未验收，属于 S2-09；本批没有执行
DDL 或启用功能开关。

![桌面调用预览](acceptance/run-preview-desktop.png)

![桌面原调用结果](acceptance/run-result-desktop.png)

![390px 调用预览](acceptance/run-preview-narrow.png)

![390px 原调用结果](acceptance/run-result-narrow.png)
