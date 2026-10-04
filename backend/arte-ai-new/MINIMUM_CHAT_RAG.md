# 新聊天的文章 RAG

新聊天增加显式文章检索和发送前预览，继续使用现有 `InvocationCoordinator`、模型账本、耐久 Worker 和 SSE。
`ArticleMcpTools`、`ArticleRetrievalAugmentationAdvisor`、`BackEndChatServiceImpl`、`ArticleServiceImpl.hybridSearch()` 均保持原实现。

## 界面使用

启用检索后，新聊天输入区显示「文章检索」选择框：

| 模式 | 输入来源 |
| --- | --- |
| 不检索文章 | 普通问题及已成功的聊天历史 |
| 单篇文章全文 | 选择一篇文章，直接读取 `arte_rt_article.content_text`，不调用 ES |
| 检索选定文章的相关片段 | 选择 1–16 篇文章，在这些文章中搜索问题相关片段 |
| 检索文章库 | 在当前工作区中通过 READ 和 AI_PROCESS 授权的文章集合内搜索 |

片段检索默认勾选「启用语义检索」，复用原 BM25 + kNN 逻辑；取消勾选则只做关键词检索。
发送先准备资料预览，再弹出外发确认。可以查看引用编号、文章 ID、版本、全文或片段范围说明、实际内容和容量事实。
历史回答中也能展开当次生成的来源。文章选项只展示当前获准读取及进行 AI 处理的文章。

全文不自动裁剪，也不因超限自动切换 ES；没有召回结果不退回普通聊天。
需要使用长文章时可以选择「检索选定文章的相关片段」。重新生成保留原输入和原来源，不重新检索。

## 部署

已安装上一版新聊天的实例，在停止 Worker 后依次执行：

1. `../arte-app/scripts/arte-ai-new-rag-upgrade-mysql.sql`：给新聊天轮次和上下文快照增加可空资料快照字段。
2. `../arte-app/scripts/arte-ai-new-rag-ddl-mysql.sql`：创建资料预览表。

新安装使用更新后的模型、聊天、Worker 初始化脚本，然后执行 RAG 建表脚本；不执行 RAG 字段升级脚本。
此前「资料上下文」步骤的 `arte_ai_new_execution.resource_context_json` 仍是前置条件。
新版本 `JdbcChatStore` 会在启动时验证新增字段，因此已有聊天实例更新代码前也须执行字段升级，即使暂未启用检索。

在构建所用的 `backend/profile/app.properties` 中设置：

```properties
arte.ai-new.chat.enabled=true
arte.ai-new.chat.retrieval-enabled=true
```

检索开关默认关闭；动作开关可以保持关闭。聊天启用时保留来源验证器，因此关闭新检索后仍可在当前权限下读取或重新生成既有 RAG 结果。也可通过 Spring 配置覆盖
`arte.ai-new.chat.retrieval-enabled`。仅改 Maven profile 文件时，需要重新构建以更新过滤后的 YAML。

使用既有安全桥配置：当前账号应有空间成员资格及对应模型应用策略；文章应登记到相同租户和工作区，
并具备旧分享/所有者/公开阅读许可及独立 `resource.ai_process` 授权；发送到模型还需要 `resource.egress`。
应用动作策略同时需要允许 `resource.read`、`resource.ai_process`，发送时还需要 `resource.egress`。
本实现不自动为文章或用户增加权限。缺少 READ 应用策略时，文章选择和预览会被拒绝。

## 调用位置

| 类 | 职责 |
| --- | --- |
| `ResourceRetrievalRequest` | 显式模式、文章 ID、语义选项及最多十个片段的请求契约 |
| `ResourceRetrievalProvider` | 业务提供者接口；新 AI 模块只依赖 base，不导入文章 DTO |
| `ArticleRetrievalQueryService` | 读取空间内的文章元数据，复用旧 `hybridSearch()`，全文委托只读正文投影 |
| `ArticleResourceRetrievalAdapter` | READ/AI_PROCESS 授权、固定文章版本、ES 片段映射和索引新鲜度检查 |
| `RagContextService` | 将固定片段及问题组装为参考资料消息，生成内容摘要和预算 |
| `JdbcRetrievalPreviewStore` | 按当前主体保存预览内容，提交只读取预览，避免二次召回发生漂移 |
| `NewChatCallService` | 将登录主体、精确文章任务范围、预览和外发同意组合到新聊天 |
| `ContextService` | 合并成功历史，整体修剪最旧的问答对，保留历史回答的来源授权事实 |

原 `hybridSearch()` 的访问级别过滤不代替授权。新提供者先列出空间内候选并核对当前权限，
将获准使用的非空 `articleIds` 传给原方法；原方法会把它们同时放入 BM25 hard filters 和 kNN filters。
空授权集合不会调用 ES。结果返回后也检查文章是否越界、元数据 ID 是否一致、ES 的 `row_version` 是否匹配 MySQL。

全文来源保存整份正文 SHA-256；ES 来源使用 `es-chunk:<chunkId>` 范围引用及实际送入模型的片段 SHA-256，
不把 chunk ID 冒充 UTF-16 正文选区。标题/面包屑和资料中的指令作为参考数据，置于 USER 内容。

初次预览只固定本轮资料和问题，聊天历史仍按原聊天的会话版本和容量规则在提交时组装。
最终消息、历史引用、全部来源及预算作为聊天上下文保存；模型执行和耐久任务保存完全相同的资料快照。
后续普通问题复用旧回答时保留其文章来源，并重新检查这些来源的外发许可，不会绕过权限。

## HTTP 契约

所有路径位于 `/api/ai-new/conversations`，由现有认证及当前主体决定身份，不接受客户端自报主体。

- `GET /rag/articles?tenantId=...&workspaceId=...`：当前可用于 AI 的文章 ID、标题和版本。
- `POST /{id}/rag-preview`：固定检索结果；不调用模型、不预留模型预算。
- `GET /{id}/rag-preview/{previewId}?tenantId=...&workspaceId=...`：重新授权后读取已保存预览，供明确重试使用。
- `POST /{id}/turns`：既有接口增加可选 `previewId`、`expectedContextDigest`，仍要求幂等键和外发确认。
- `GET /{id}/turns/{turnId}/context?tenantId=...&workspaceId=...`：重新授权后读取该轮固定上下文，供重新生成确认使用。

预览示例：

```json
{
  "tenantId": "your-tenant",
  "workspaceId": "your-workspace",
  "expectedVersion": 1,
  "text": "这几篇文章如何解释这个问题？",
  "retrieval": {
    "mode": "SELECTED_ARTICLES",
    "articleIds": ["10", "11"],
    "semanticSearch": true,
    "maxResults": 10
  }
}
```

发送时保留同一问题、版本和模型参数，并携带响应的 `previewId` 和 `context.contentDigest`。
客户端不回传正文或任意来源快照。前端在发送前持久化这些字段，网络失败后的重试沿用原键和原请求。

## 失败与边界

- `rag-no-results`：无可用文章或无搜索命中。
- `rag-index-stale`：ES 文章元数据版本未同步；重新同步后再预览，不静默使用旧片段。
- `rag-search-scope`：ES 返回范围外文章；停止组装，避免泄漏。
- `resource-context-capacity` / `chat-capacity`：全文、片段及标签超出字节或 token 容量；不裁剪资料。
- `rag-preview-expired`：新提交不能使用过期预览；已受理的同键请求可以恢复，不会再次搜索或计费。
- `chat-source-capacity`：历史派生回答积累的不同来源超过 512 项时拒绝新调用，请新建会话。
- `rag-library-capacity`：初期候选范围上限为 4096 篇，超过时明确失败；选择具体文章不受库大小上限影响。

预览默认有效十分钟。过期不自动删除正文；预览、聊天、模型和 Worker 的数据保留策略仍须由部署方统一管理，
不能仅按预览 TTL 删除已有受理请求所需的恢复数据。
执行查询、历史、SSE 和外发均重新检查来源权限；撤权后不返回资料或回答。停止自己的执行使用模型权限，不要求资料读取权限。
当前检索继续使用旧 EmbeddingService 的配置；本步骤没有迁移向量模型、重写排序算法或改动索引同步流程。

## 验证

集成测试使用实际安全桥、MySQL 脚本在 H2 上的等价结构、聊天/模型账本、耐久队列、Worker 和流式协议。
ES 和模型网络使用可控制的替身；另外检查实际旧参数转换及真实 ES SearchRequest 中的 BM25/kNN 范围过滤。
覆盖全文、选定文章、文章库、无权限/空命中/越界/过期索引/容量、发送前后撤权、固定重试、重新生成、历史来源和较旧历史页。
前端测试覆盖预览完成前不派发、确认真实片段后发送，以及重载后保留原预览身份和幂等请求。
没有连接实际 ES、MySQL 或外部模型执行线上联调；H2 不替代真实 MySQL 部署验证。
