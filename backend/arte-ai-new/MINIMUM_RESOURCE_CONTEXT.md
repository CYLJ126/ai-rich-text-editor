# 最小资料上下文

第二步在独立 `rewrite/v1` 动作中加入显式文章资料：待改写原文可为已保存文章、用户草稿或正文选区；参考资料可为用户主动选择的文章或摘录。
`ResourceContextService` 只依赖 base 和新 AI，文章领域查询、授权组合、JDBC 和 HTTP 位于 app。无需创建聊天会话；已有纯文本动作及聊天保持兼容。
此处完成后端资料闭环，编辑器资料选择界面、聊天资料接入、修改提案和采纳保存按后续步骤接入。

## 固定输入与来源

- `ResourceContextSelection(resource, draftText)`：客户端明确选择固定版本，草稿还提供完整纯文本和其摘要。`text` 和待改写 `target` 二选一；`references` 最多 16 项，顺序进入实际输入。
- `ArticleContextQueryService`：文章领域只读投影，读取未删除文章的 `row_version`、`content_text` 和标题。首期使用现有保存的纯文本投影；缺少版本或投影时拒绝处理，不猜测内容或转换为未经验证的历史版本。
- `ArticleResourceContextAdapter`：先检查当前文章权限，再核对版本、整个正文摘要和选区，产生 `ContextFragment`。仅注册 ARTICLE；未安装的资料提供者返回 UNSUPPORTED。
- `ResourceContextSnapshot`：记录实际消息、资料片段、完整 ResourceRef、引用编号、覆盖说明、容量、内容／选择摘要和期限。目标编号为 `target`，参考编号为 `reference-1` 等。草稿未选中的正文用于校验与选择摘要，不存入模型输入或动作快照。

保存正文必须先读取当前固定引用。客户端改变版本、草稿内容、选区、参考顺序或要求后重新预览；提交阶段重新解析并比较预览摘要。
文章在预览后变化会产生 VERSION_CONFLICT，不能用最新文章默默替换原输入。资料装配不自动搜索全库。

`contentDigest` 使用 `sha256:` 加 64 位小写十六进制，正文摘要是 **原始文本的 UTF-8 字节 SHA-256**，不做 trim、换行或 Unicode 归一化。
保存文章使用 GET 返回的正文摘要；草稿使用完整 `draftText` 的摘要，并保留编辑基于的保存版本及客户端草稿 ID。

`rangeRef` 使用 `utf16:start:end`，表示完整纯文本的 UTF-16 半开区间 `[start,end)`。不填表示全文。索引不得越界、为空或切开 emoji 的代理对。
范围基于 GET 返回的 `stored-plain-text` 或完整 `draftText`，不是 Markdown 源码、Tiptap 节点或 DOM 的位置；不得直接把该偏移用于编辑器替换。
选区片段的 `truncated=true` 表示使用了部分正文，覆盖说明明确标记保存／草稿、选区／全文、实际范围及正文总长度。

## 预览、提交及恢复

所有路径沿用现有登录身份；tenantId／workspaceId 由安全桥接验证。客户端不能提供主体、模型绑定、连接或执行上下文。

| 操作 | 路径 | 行为 |
|------|------|------|
| 获取文章坐标 | GET `/api/ai-new/context/articles/{id}?tenantId=…&workspaceId=…` | 返回 `{resource,title,text,representation,utf16Length}`；只读，不派发模型 |
| 预览资料 | POST `/api/ai-new/actions/rewrite/context` | 返回 ResourceContextSnapshot；不登记动作、模型执行、预算或外发同意 |
| 提交改写 | POST `/api/ai-new/actions/rewrite/executions` | 原选择加 `expectedContextDigest`、`externalTransferConfirmed=true` 和 Idempotency-Key；202 返回 `{action,execution}` |
| 观察和控制 | 原动作查询／events／cancel／regenerate 路径 | 保留流式、部分正文、停止、重新生成和响应丢失恢复行为 |

预览请求模板如下；占位值须替换为文章坐标接口返回的 `resource`。选全文时 `rangeRef=null`；选择摘录时填纯文本坐标。
只改写用户输入时用 `text` 替代 `target`，仍可显式加入 `references`。

```json
{
  "tenantId": "personal-1",
  "workspaceId": "workspace-1",
  "requirements": "表达更简洁，保留事实",
  "options": {"maxOutputTokens": 512},
  "target": {
    "resource": {
      "resourceType": "ARTICLE",
      "resourceId": "待改写文章ID",
      "version": "GET返回的版本",
      "draftId": null,
      "rangeRef": null,
      "contentDigest": "GET返回的正文摘要"
    },
    "draftText": null
  },
  "references": []
}
```

预览返回的 `messages` 是实际输入，`fragments` 是实际使用的来源与覆盖范围，`budget` 是容量事实。
提交使用相同请求，增加 `expectedContextDigest` 为预览返回的 `contentDigest`，以及外发确认；不能将客户端自报消息直接当作已验证快照。
选择草稿时把 target.resource 的 `draftId`、`contentDigest` 换成该草稿的值，target.draftText 为完整草稿，version 仍为编辑基于的保存版本。
加入／移除参考只需调整 references 并重新预览；旧摘要不能确认新资料范围。

输入容量同时核对消息文本 UTF-8 字节和 Token 预算，包含系统指令、要求、资料标签与所有显式片段。
超限返回 400 `{error,capacity}`，capacity 包含已用／上限字节、估算／上限输入 Token、输出预留和安全预留。
不会静默截掉参考或选区；调用方可缩小选区、移除参考或分段处理。Token 为保守估算，不是供应商计费；完整协议 JSON 仍由网关另行检查。

已可靠受理的同键重复请求核对原选择和固定摘要，返回原执行，不再读取最新文章来替换输入，也不再次派发。
同键并发使用数据库实际登记的同一快照，包括 ID 和时间。结果可查询时不受快照期限影响，但必须重新通过当前权限检查。
输入登记成功而模型尚未受理时，仍需核对有效期限和明确外发同意；过期请求不能继续发送。取消始终保留为独立的停止控制。

重新生成沿用原实际消息、来源、范围、版本，重新核算容量并生成新期限；新模型选项进入新动作摘要。
不会自动加载文章的新版本。要改写当前最新内容，应重新获取文章坐标、预览，并使用新提交键。

## 权限和派发边界

来源权限按实际 ResourceRef 登记到本次任务，不通过模型许可代替文章许可。资料和结果都按租户、空间及主体隔离。

| 时机 | 必需条件 |
|------|----------|
| 获取保存正文 | 当前文章 READ，成员／资源归属及应用／绑定的 READ 许可 |
| 预览／读取结果／每批 SSE | 当前每项资料 READ + AI_PROCESS；草稿首次选择还需 EDIT |
| 提交／耐久 Worker 派发／完成 | 模型及预算许可；每项资料 READ + AI_PROCESS + EGRESS，草稿还需 EDIT；实际协议正文及目的地的明确外发同意 |
| 取消自己的执行 | 当前主体作用域及模型许可；资料读取许可撤销后仍可停止生成 |

现有安全桥接对 AI_PROCESS／EGRESS 还要求逐文章、逐用户的显式 `arte_security_resource_grant`。
旧文章可读、公开或管理员身份不自动成为 AI 或外发许可。保存正文只读；草稿要求原文章可编辑。
`arte_security_resource_scope` 须包含文章的正确归属；应用／绑定除了原有 `resource.ai_process`、`resource.egress`，资料读取须启用 `resource.read`，草稿须启用 `resource.edit`。
部署人员在现有安全管理流程中开通这些许可；本功能不会自动授权全库或重启已撤销的许可。

统一协调入口的 EgressRequest 使用快照实际 SourceRef，正文摘要使用实际供应商协议正文。
模型账本持久化同一资料快照；原始模型查询／事件入口也逐项重新授权，不能绕过动作端的资料权限检查。
Worker 验证队列、执行账本的快照一致及正文指纹，派发前、完成前重新检查来源；权限撤回会停止后续读取或派发。
快照 TTL 限制新的外发有效期，不会自动删除已有资料或模型结果。保存版本在新提交时核对，已固定输入的恢复／重新生成仅重查当前权限，保留原版本。

## 数据库与启用

1. 新安装使用更新后的 [模型 DDL](../arte-app/scripts/arte-ai-new-model-ddl-mysql.sql)、原有耐久工作队列 DDL 和 [动作 DDL](../arte-app/scripts/arte-ai-new-action-ddl-mysql.sql)，不需要聊天表。
2. 已完成第一步的实例：先停止旧应用／Worker，再执行 [资料上下文升级 DDL](../arte-app/scripts/arte-ai-new-resource-context-ddl-mysql.sql)，然后部署新后端。该脚本一次性增加执行资料字段并更新动作 JSON 字段注释；已有纯文本记录保持为空，旧输入不重写。
3. 保持 `arte.ai-new.model.enabled=true`，开启 `arte.ai-new.action.enabled=true`；聊天开关可独立关闭。运行新版本的模型账本，即使动作关闭，也需要升级执行表。
4. 按上述范围检查文章归属及明确许可，再用文章坐标 → 预览 → 确认提交 → SSE／查询验证。

资料容量沿用 `model.max-input-bytes`、`model.max-output-tokens`、`chat.context-window-tokens`、`chat.context-safety-tokens`；快照期限沿用 `chat.snapshot-ttl`（默认 PT10M）。
资料快照 JSON 为 `arte.resource.context.v1`；有资料上下文的动作输入／耐久工作为 v2，纯文本原路径仍写 v1，新读取器兼容两版。
新旧 Worker 不应混合运行，旧 Worker 不识别 v2 工作。执行资料列为空表示原纯文本调用，不表示资料无需授权。

## 验证范围

集成测试覆盖真实文章查询、现有身份／权限桥接、H2 执行与预算账本、耐久 Worker、协议流替身和 SSE：全文／选区／草稿／参考、资料移除、摘要与版本冲突、UTF-16 边界、容量反馈、当前权限撤回、取消、过期、重新生成、响应丢失及同键并发。
持久化测试验证快照及 v1／v2 工作往返、篡改拒绝和升级前后旧动作输入保留；配置测试验证完整接线与聊天关闭时独立运行。
本次新增资料集成／持久化测试 24 项，连同动作、聊天、模型、配置及安全桥接回归共 153 项通过，失败／错误／跳过均为 0。
MySQL DDL 已补齐一致的中文字段注释；H2 兼容测试不代替实际 MySQL 部署验证。未自动执行业务数据库迁移或调用真实模型。
