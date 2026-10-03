# 新聊天第四步：使用体验与第一版验收

前三步已完成 [会话管理](ai-new-chat-step1.md)、[最小聊天闭环](ai-new-chat-step2.md)
和 [执行控制与异常处理](ai-new-chat-step3.md)。本步完善 `/AI/Chat` 的阅读与输入体验，并将四步汇总为可执行的第一版验收清单。

## 本步交付

- 模型成功回答使用独立的 `AssistantMarkdown` 展示标题、段落、列表、引用、行内代码、代码块和链接。问题仍按原始文本展示。
- 代码块可水平滚动；问题、回答和代码均可复制。回答复制保留原始 Markdown；代码复制只包含代码正文。复制成功有反馈，浏览器拒绝剪贴板访问时提示手动选择复制，不显示虚假成功。
- 使用已有 `react-markdown` 依赖，不启用原始 HTML；不执行模型返回的脚本、iframe 等标签。图片只展示说明文字，不自动加载远程资源。链接只允许显式
  HTTP／HTTPS，点击打开新页且隔离 opener／referrer。
- 聊天记录有独立滚动区域。首次读取定位最新记录；在底部时跟随最新回答和状态。查看旧消息时保持位置并显示“查看最新消息／状态”；向前加载历史保持当前阅读位置。
- 同一用户／空间的当前页面内，切换会话保留各自未发送草稿。普通草稿只在页面内存中，离开页面、刷新、切换空间或账户时清除；删除会话和收到权限拒绝时清除相应草稿。待核对请求仍按第二步规则保留在
  `sessionStorage`，用于恢复原幂等请求，和普通草稿用途不同。
- Enter 换行，Ctrl／⌘ + Enter 打开发送确认，输入法组合期间不触发快捷发送。输入框关联字节数与操作提示，超限显示错误样式；窄屏操作栏换行，代码块在自身区域滚动。
- 仅确认普通提交已 ACCEPTED 后清理相同草稿。HTTP 成功但业务记录为 REJECTED 时保留输入；重新生成保留草稿，迟到回执不能清除其他会话或已经改变的草稿。

本版 Markdown 使用 CommonMark，暂未加入 GFM 表格、公式、图表或代码高亮。当前聊天仍为文本提交加状态查询，没有逐 token
输出、附件、文章资料、助手选择、知识检索、文章采纳或费用展示；这些属于后续功能。

## 验收前准备

在测试环境执行，使用专门的测试账号、空间和有限预算。实际供应商调用可能产生费用。

1. 根据后端 [最小模型调用](../../backend/arte-ai-new/MINIMUM_MODEL_CALL.md)
   和 [最小聊天服务](../../backend/arte-ai-new/MINIMUM_CHAT_SERVICE.md) 准备现有账号、空间成员、AI
   与外发许可、默认受控连接、预算及服务端凭据引用。
2. 按依赖顺序准备安全桥接、执行支撑、模型和聊天 DDL；个人空间回填只在相应部署需要时执行。已有表须核对现有结构，
   `create table if not exists` 不会自动修改它们。菜单准备见 [第一步](ai-new-chat-step1.md)。
3. 启用安全桥接、`single-instance` 执行支撑、模型及聊天开关；重新登录获得 `AIChat` 菜单。通过前端现有代理或同源入口访问
   `/arte/api/ai-new`。
4. 确认可用配置显示的是实际测试模型、固定版本、目的地及上限。记录测试账号、tenantId、workspaceId、绑定版本、浏览器与时间；证据中移除
   Token、凭据和不必要的对话正文。

不要通过前端传入主体、模型凭据或目的地来绕过服务端配置；不要在生产数据上直接改状态模拟故障。预算、撤权和响应丢失等异常应在隔离测试环境或测试适配器中验证。

## 第一版手工验收清单

下列项目留待已配置环境逐项勾选。自动测试通过不等于真实数据库、浏览器和供应商已完成验收。

| 编号 | 操作                                                                      | 通过标准                                                                                                                      |
|------|---------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| A01  | 关闭聊天／移除菜单／撤销空间成员或 AI 许可                                | 显示对应不可用状态；不发模型请求；伪造 URL 空间不触发该空间查询                                                               |
| A02  | 创建、标题检索、重命名、删除；另一客户端修改版本后再次操作                | 列表、详情、URL 一致；刷新能恢复已保存会话；冲突后查询最新状态，不覆盖其他客户端内容；删除为软删除                            |
| A03  | 连续发送两轮；第一次取消确认，再重新打开确认                              | 未勾选不能提交，取消不派发；确认信息包含实际目的地、用途及可能费用；每个新提交使用新键和最新版本，能看到权威成功回答          |
| A04  | 让回答包含标题、列表、引用、代码、普通链接及 HTML／危险链接／远程图片     | 常用格式可读；代码宽度不撑破页面；HTML 不执行、危险链接不可点击、图片不自动加载；三种复制内容正确，拒绝剪贴板访问时可手动复制 |
| A05  | 准备超过 20 条历史，加载更早消息；查看中间历史时让生成完成                | 历史无重复、按顺序排列，前分页使用排他边界；加载旧历史不跳动；新状态不强行滚动，点击最新入口才回到底部；在底部正常跟随        |
| A06  | 在两个会话输入不同草稿后切换，随后刷新／退出登录／切换账户                | 会话切换保留各自草稿；刷新和退出后普通草稿清除；新账户看不到旧草稿或缓存。普通草稿不写浏览器存储，拒绝提交保留输入            |
| A07  | 中文输入法、Enter、Ctrl／⌘ + Enter；空白与 UTF-8 字节超限                 | Enter 正常换行，组合输入不打开确认，快捷键仅打开确认；空白／超限不能发送；字节数按 UTF-8 计算而非字符个数                     |
| A08  | 生成中请求停止，观察正常回执、丢失回执和 UNCONFIRMED                      | 控制请求不自动重试；回执与实际终态分别呈现；终态前仍锁定发送、重命名和删除；未知结果不冒充取消成功                            |
| A09  | 最新完成问题重新生成，取消一次后再确认                                    | 取消不派发；确认新费用／外发后新增执行，保留原结果和草稿；仅最新且结果确定的问题可重生成，UNKNOWN 无该入口                    |
| A10  | 模拟普通提交／重新生成的响应丢失，再刷新和重放                            | 先查询核对；保留原操作、目标、版本、文本和键；重放再次要求确认，不增加同键提交、执行或预算预留                                |
| A11  | 模拟预算不足、策略缺失、限流、版本冲突、超时和结果未知                    | 提示与稳定错误事实一致；输入保留、无自动派发；HTTP 等待超时不当成模型终态，未知费用不当成零费用                               |
| A12  | 查询／控制权限撤销，重新查询期间检查旧界面；切换会话／账户后让旧响应返回  | 权限失败隐藏缓存内容；重新授权读取成功前不闪回旧消息；迟到响应不恢复旧账户界面或清除新草稿                                    |
| A13  | 在 390px 和 1440px 宽度、浅色／深色主题、简中／繁中／英语下使用键盘和触屏 | 标题、按钮和代码不造成整页横向溢出；窄屏上下布局；记录可键盘滚动，按钮有名称，复制反馈可被辅助技术读出，弹窗可取消            |
| A14  | 返回旧聊天、文章 AI 等现有入口                                            | 既有入口、表和引用保持独立；新页面成功不意味着旧功能已迁移                                                                    |

## 数据核对

使用有权限的运维账号，在测试数据库对记录的会话执行以下只读查询；替换占位参数，不将敏感正文导出为验收证据。

```sql
select conversation_id, tenant_id, workspace_id, principal_id,
       status, row_version, model_binding_version, deleted_at
from arte_ai_new_conversation
where conversation_id = :conversation_id;

select turn_id, sequence_no, kind, status, conversation_version,
       idempotency_operation, idempotency_key, regenerates_turn_id,
       execution_id, slot_released_at
from arte_ai_new_turn
where conversation_id = :conversation_id
order by sequence_no;

select t.turn_id, e.execution_id, e.status, e.dispatched,
       e.budget_status, e.reserved_amount, e.currency
from arte_ai_new_turn t
join arte_ai_new_execution e
  on e.execution_id = t.execution_id and e.scope_key = t.scope_key
where t.conversation_id = :conversation_id
order by t.sequence_no;

select b.amount_limit, b.reserved_amount, b.spent_amount, b.currency, b.revision
from arte_ai_new_budget b
join arte_ai_new_conversation c on c.scope_key = b.scope_key
where c.conversation_id = :conversation_id;
```

正常新提交推进一次会话版本；同键重放不推进版本或增加同键执行。202 表示受理，成功以执行 SUCCEEDED 和结果记录为准。成功且用量确定后预算为
SETTLED；已可能外发的取消、失败或未知结果可保持
PENDING_RECONCILIATION，不能预期预留必然归零。管理会话操作和复制不增加模型执行；预算是主体作用域共享账本，核对差额时排除该主体其他调用。会话占位释放以服务端核对终态为准，不按取消回执判断。

## 自动验证与本次结果

从仓库相应目录运行：

```sh
# frontend/
npm test -- src/pages/AI/Chat src/services/ai-new src/features/ai-chat src/access.test.ts
npx biome lint src/pages/AI/Chat src/features/ai-chat src/locales/zh-CN/aiNew.ts src/locales/zh-TW/aiNew.ts src/locales/en-US/aiNew.ts
NO_UPDATE_CHECK=1 npx antd lint src/pages/AI/Chat
npm run tsc
npm run build

# backend/
mvn -o -pl arte-app -am -Dmaven.compiler.proc=full -Dtest=NewChatIntegrationTest,NewChatConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

2026-10-03 本次验证：前端相关 48 项测试通过，后端聊天集成／配置 21 项测试通过；目标范围 Biome、Ant Design 检查和生产构建通过。新增回归覆盖
Markdown 安全与复制、复制拒绝、历史位置保持与新结果提示、会话草稿及迟到回执隔离、快捷键／输入法及成功 HTTP 中的业务拒绝。

全项目 `tsc` 仍受已有 `src/components/Article/extension/canvas/canvas-ai-dialog.tsx:204,212` 的 `attachments` 隐式
`any[]` 两处错误阻挡，本步涉及代码没有新增类型错误。前端测试使用真实 React Query／Ant Design 和模拟 HTTP、DOM；后端使用 H2
与实际身份／权限和生产序列化，模型供应商为测试适配器。

本次未执行实际数据库迁移、启用环境或调用真实模型。浏览器自动化连接超时，尚未完成真实浏览器视觉验收。发布前须在已配置测试环境完成上表，并补齐
MySQL、登录菜单、真实供应商停止／费用及浏览器证据；全项目类型检查的已有错误也应在发布检查中处理。

建议记录：验收编号、通过／失败、时间、环境版本、conversationId／turnId／executionId、去敏后的证据与待处理项。只有代码回归和实际环境项目都通过，才标记“第一版验收完成”。
