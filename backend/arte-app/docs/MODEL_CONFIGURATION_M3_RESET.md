# M3 全新数据重置清单

日期：2026-11-03。按当前仓库 DDL、Mapper 和 M1～M3 实现核对；未连接目标数据库或执行清理。

本清单适用于此前确认的“不保留历史模型／聊天／预算测试数据，从页面重新配置”。以下整表清理覆盖所有租户和空间的 New-AI 数据。
公共身份、租户／空间、成员、政策、RBAC、文章和旧 AI 模块保留；独立工具的定义和个人绑定默认保留，工具执行历史随共用运行表一起清理。
若要保留任一模型历史或账本，不能使用这套整表重置清单，需要改为保留历史或显式迁移方案。

## 1. 清空数据，保留结构

### 1.1 模型与控制面：15 张

| 表                              | 清理内容／重置后的要求                                                        |
|---------------------------------|-------------------------------------------------------------------------------|
| arte_ai_control_revision        | 全部旧审核连接、方案、费率与默认模板修订；reviewedPlans 属于 payload 内的字段 |
| arte_ai_control_head            | 重置后必须保留唯一一行 `(id=1, version=0)`，不能留空                          |
| arte_ai_workspace_configuration | 所有空间旧开关、旧 preset 或新 ModelSelection 数据                            |
| arte_ai_workspace_budget_rule   | 所有空间独立预算规则；表不存在时先安装新结构                                  |
| arte_ai_budget_definition       | 所有旧主体／空间／精确费率的预算定义，与运行账本一并清理                      |
| arte_ai_model_use_scope         | 租户模型允许使用空间的索引，先于 sharing 清理                                 |
| arte_ai_model_sharing           | 租户模型范围及凭据消费者批准                                                  |
| arte_ai_model_publication       | 新的 RESOURCE 中性发布证据；表必须保留                                        |
| arte_ai_model_validation        | 旧验证请求和结果                                                              |
| arte_ai_model_draft             | 旧草稿及其审核方案快照                                                        |
| arte_ai_model_credential        | 旧凭据密文和代次；重建时需重新填写密钥                                        |
| arte_ai_model_version           | 旧精确发布版本和依赖快照；先于 resource 清理                                  |
| arte_ai_model_resource          | 旧个人／租户模型管理资源                                                      |
| arte_ai_model_definition_source | 旧精确引用的来源与摘要；避免新建同名引用被旧摘要阻塞                          |
| arte_ai_model_acl_scope         | 旧 FIXED／迁移清单数据；保留表，当前仍有对应 Mapper 和 FIXED 路径             |

### 1.2 New-AI 运行数据：42 张

重建模型和费率后，以下旧调用、上下文、预算和工具聊天仍引用原版本／账户；按本轮不保留历史方案一起清理。 这会删除 New-AI
聊天和工具执行历史。独立工具定义／绑定是否保留见第 3 节。

下面是依据当前外键依赖得到的一种“子表先于父表”的清理顺序；只列名字，不代表已经执行 SQL。
`DELETE` 按依赖顺序进行；不要把有外键引用的父表直接当作可 TRUNCATE 的表，即使子表已无数据。

```text
arte_ai_lock
arte_ai_acceptance
arte_ai_event
arte_ai_operation
arte_ai_outbox
arte_ai_conversation_new
arte_ai_turn
arte_ai_account
arte_ai_reservation
arte_ai_settlement
arte_ai_conversation_creation
arte_ai_context_snapshot
arte_ai_result
arte_ai_dispatch_permit
arte_ai_dispatch_rate
arte_ai_conversation_metadata
arte_ai_conversation_metadata_command
arte_ai_conversation_message_fact
arte_ai_conversation_projection_job
arte_ai_tool_execution_snapshot
arte_ai_scheduling_permit
arte_ai_dispatch_cursor
arte_ai_dispatch_pool
arte_ai_queue_capacity
arte_ai_tool_approval
arte_ai_tool_approval_audience
arte_ai_tool_outcome_evidence
arte_ai_tool_outcome_scan
arte_ai_tool_outcome_review
arte_ai_tool_outcome_review_command
arte_ai_tool_chat_run_work
arte_ai_tool_chat_run_step
arte_ai_tool_chat_run_command
arte_ai_tool_chat_link
arte_ai_tool_chat_run_event
arte_ai_tool_chat_budget
arte_ai_tool_chat_budget_allocation
arte_ai_tool_bill
arte_ai_attempt
arte_ai_tool_outcome_check
arte_ai_tool_chat_run
arte_ai_invocation
```

这 42 张属于 [arte-ai-new-ddl-mysql.sql](../../arte-ai/scripts/arte-ai-new-ddl-mysql.sql) 中的运行表；M1～M3
没有要求修改它们的物理结构。 若当前库尚未完成其他 Tool／搜索／调度批次的安装，需另行对照实际结构；不能把所有历史 migration
无条件串行执行。

### 1.3 公共表：按模型条件清理，不整表清空

| 表                            | 处理范围                                                                                                                                                        |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| arte_platform_resource_grant  | 只删除其 resource_key 对应 `resource_access.resource_type='model'` 的授权；先于 access 清理                                                                     |
| arte_platform_resource_access | 只删除 `resource_type='model'` 的 ACL 根，保留其他资源                                                                                                          |
| arte_platform_command         | 清理 `command_type LIKE 'model:%'`、`command_type LIKE 'workspace:ai:%'`、`result_schema='ai:catalog:view:v1'` 的旧回执；兼顾下述通用 ACL 回执                  |
| arte_platform_outbox          | 清理 `event_type LIKE 'model:%'`、`event_type LIKE 'workspace:ai:%'`、`event_type='ai:catalog:published'` 以及 attributes.resourceType 为 model 的通用 ACL 事件 |
| arte_platform_audit           | 可保留作为历史审计，不参与启动或恢复旧业务配置；功能重置不要求清空                                                                                              |

不要清空整个 command／outbox／audit，它们还服务于公共空间、政策及其他资源。 通用 `resource:*` ACL 命令不是都属于模型，不能直接按这个前缀全删。先从模型
ACL Outbox 的
`event.attributes.operationId` 收集其 operation_id，再清相应 command；也可核对回执中 value.resource 或 value.grants 的资源类型。
其中 `resource:void:v1` 回执没有目标内容，需通过关联事件定位；无法确认目标的历史通用回执可保留，不作为重新建模的入口。 这些关联
operation_id 应在删除模型事件之前收集。

仅删除模型 ACL 的关系为：

```sql
-- 本段会删除模型 ACL；仅在已确认重置范围并停写后手动执行。
DELETE
g
FROM arte_platform_resource_grant g
JOIN arte_platform_resource_access a ON a.resource_key = g.resource_key
WHERE a.resource_type = 'model';

DELETE
FROM arte_platform_resource_access
WHERE resource_type = 'model';
```

## 2. 结构需要处理的表

| 表／结构                                                                                                        | 当前目标                                              | 如何处理                                                                                    |
|-----------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|---------------------------------------------------------------------------------------------|
| arte_ai_workspace_budget_rule                                                                                   | M3 新增独立规则表                                     | 缺失时创建；已与最新 DDL 一致则只清数据                                                     |
| arte_ai_model_publication                                                                                       | M2 新增中性发布表                                     | 缺失时创建；不要拿旧 personal_publication 改名后直接用旧载荷                                |
| arte_ai_model_definition_source                                                                                 | source 为整数，CHECK 允许 0～3（3=RESOURCE）          | 对照 SHOW CREATE TABLE；旧上限为 1／2 或旧字符串来源时，在清空后按最新定义单独重建此表      |
| idx_model_resource_workspace                                                                                    | model_resource(tenant_id, workspace_id, resource_key) | 草稿脚本增加的索引，缺失时补建                                                              |
| ux_ai_model_resource_tenant                                                                                     | model_resource(resource_key, tenant_id) 唯一索引      | sharing／use_scope 复合外键的前置索引，缺失时先补建再装共享表                               |
| arte_ai_control_revision、arte_ai_workspace_configuration、arte_ai_model_draft／credential／validation／version | M1～M3 多数变更在 JSON payload 内                     | 字段／索引与最新 DDL 一致时，只清数据；无需增加 reviewedPlans、planRef 或 models 等数据库列 |

只读核对示例（对存在的表执行）：

```sql
SHOW
CREATE TABLE arte_ai_model_definition_source;
SHOW
INDEX FROM arte_ai_model_resource;
SHOW
CREATE TABLE arte_ai_model_publication;
SHOW
CREATE TABLE arte_ai_workspace_budget_rule;
```

初始化定义按依赖对应以下文件：

1. [arte-platform-ai-control-ddl-mysql.sql](../scripts/arte-platform-ai-control-ddl-mysql.sql)：控制面、空间配置、独立预算规则及预算定义。
2. [arte-ai-model-resource-ddl-mysql.sql](../scripts/arte-ai-model-resource-ddl-mysql.sql)：资源、版本、0～3 来源约束。
3. [arte-ai-model-acl-scope-ddl-mysql.sql](../scripts/arte-ai-model-acl-scope-ddl-mysql.sql)：保留的 FIXED／迁移清单表。
4. [arte-ai-model-draft-ddl-mysql.sql](../scripts/arte-ai-model-draft-ddl-mysql.sql)：工作空间索引、草稿、凭据与验证。
5. [arte-ai-model-publication-ddl-mysql.sql](../scripts/arte-ai-model-publication-ddl-mysql.sql)：中性发布证据，依赖资源和版本。
6. [arte-ai-model-sharing-ddl-mysql.sql](../scripts/arte-ai-model-sharing-ddl-mysql.sql)：租户模型复合索引、共享与使用范围。

这些是初始化定义，不是“对已有表反复执行”的脚本。已有且一致的表只清空；缺表／索引或确需重建的表才执行对应语句，避免重复 CREATE
TABLE／CREATE INDEX。 公共身份、公共空间与 ACL 表继续作为前置结构保留，不重复初始化它们的数据。

## 3. 删除的旧表，以及保留的工具配置

旧 `arte_ai_model_personal_publication` 已被中性 `arte_ai_model_publication` 替代，当前生产源码无读取路径。
若库里有此表，按本轮全新数据方案可删除；应在清理／重建 model_version 和 model_resource 前处理其外键依赖。 不再执行旧
`arte-ai-model-personal-publication-ddl-mysql.sql` 或旧只扩展到 source=2 的 personal-source migration。

除了这个旧表，本轮没有要求删除其他当前模型表；特别是 definition_source、version、acl_scope 都应保留结构。

以下 11 张是独立工具的目录／发布／个人绑定／价格身份记录，重置模型配置不要求清空或删除：

```text
arte_ai_tool_provider
arte_ai_tool_schema
arte_ai_tool_definition
arte_ai_tool_release
arte_ai_tool_release_command
arte_ai_tool_binding
arte_ai_tool_binding_revision
arte_ai_tool_binding_resolution
arte_ai_tool_binding_command
arte_ai_tool_discovery_command
arte_ai_tool_price_guard
```

如果工具配置也全部重新做，再额外清理上述 11 张，先删 resolution／revision／release 等子记录，再删 binding／definition，最后删
provider／schema；表结构仍保留。 保留配置的收费工具仍依赖原精确费率和空间预算；目录重置后须重新补齐对应批准条件，不能以已有工具绑定恢复预算资格。
免费独立工具的使用不依赖模型选择，但其旧执行记录已随第 1.2 节清理。

## 4. 应保留的数据与表

- arte_platform_principal_identity：稳定主体映射。
- arte_platform_tenant、arte_platform_workspace、arte_platform_tenant_member、arte_platform_workspace_member：租户、空间及成员。
- arte_platform_tenant_policy、arte_platform_workspace_policy、arte_platform_workspace_preference：政策与空间偏好。
- arte_platform_personal_policy_template、arte_platform_personal_policy_template_head：个人租户政策默认模板。
- arte_rbac_*：账号、角色、菜单及权限关系。
- 文章、编辑器、知识库等本轮无关业务表。
- 旧 AI 模块的 arte_ai_assistant、arte_ai_conversation、arte_ai_model 等表，除非另行决定连旧模块也清理。特别注意，新会话表为
  arte_ai_conversation_new。

因此不要按 `arte_ai_%` 通配符批量 DROP／TRUNCATE，也不要整库清空。

## 5. 建议执行顺序

1. 停止连接该库的 API、Worker、模型验证及事件投递／搜索同步进程，保留可恢复备份；本次是整段开发数据重置。
2. 核对实际表结构，收集待清理的公共模型命令／ACL 事件 operation_id。
3. 先清第 1.2 节运行数据；若决定工具配置也重做，此时按外键依赖清其目录／绑定数据。
4. 删除旧 personal_publication 表；清理模型 use_scope → sharing、publication、validation／draft／credential、version →
   resource；清 definition_source 和 acl_scope。
5. 按条件清理模型 ACL、对应公共命令回执和事件。保留其他资源记录及公共空间／身份。
6. 清预算定义、空间 AI 选择、独立预算规则以及目录修订；补齐缺失／不一致的结构和索引。
7. 对齐全部重置完成后，把 arte_ai_control_head 恢复为唯一 `(1,0)`。若它已清空，应插入此行；不能只留下空表或只重置指针而保留旧关联数据。
8. 清理浏览器账号下 `arte.ainew.` 前缀的 sessionStorage／localStorage 恢复记录和模型选择。保留登录与其他业务存储，不整站清空。
9. 若启用了 New-AI 搜索，同步处理本环境的对应搜索索引和搜索会话缓存，避免旧正文索引残留；按实际配置定位，不清空整个
   Redis／Elasticsearch。
10. 启动后重新创建／发布审核连接 → 审核方案与费率 → 实际模型与凭据、验证及发布 → 显式首次准备空间 AI →
    启用模型并批准独立规则 → 本人准备账户 → 聊天中重新加载并明确应用。

公共租户／空间政策仍保留，所以新连接继续受允许协议、供应商和目的地限制；如采用新的目标配置，需在对应政策中明确允许。
重置没有改变模型管理、租户共享、目标空间治理、凭据消费或平台提额权限。

本清单只依据当前仓库作结构和清理范围说明，没有读取目标库的 SHOW CREATE TABLE，也没有执行任何清库、DROP、ALTER 或初始化操作。
