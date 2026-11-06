# 模型配置 M0～M5 全新数据重置清单

更新日期：2026-11-03。按 M0～M5 最终代码、DDL、Mapper 和页面流程重新核对；文件名保留供现有文档引用。
本轮编码及本机隔离验收已完成，生产环境验证与部署仍待进行，具体证据和未覆盖项见 [M5 交付说明](MODEL_CONFIGURATION_M5.md)
。本次文档复核未连接目标数据库或执行清理。

M4／M5 没有新增模型业务表或结构迁移，物理结构仍以 M1～M3 初始化定义为准。当前处理范围为：15 张模型／控制面表、41
张共用运行表，以及模型相关公共 ACL／回执／事件；另有 4 张已安装搜索进度表按第 1.4 节处理。11 张工具配置表默认保留。

本清单适用于此前确认的“不保留历史模型／聊天／预算测试数据，从页面重新配置”。以下整表清理覆盖所有租户和空间的 New-AI 数据。
公共身份、租户／空间、成员、政策、RBAC、文章和旧 AI 模块保留；独立工具的定义和个人绑定默认保留，工具执行历史随共用运行表一起清理。
若要保留任一模型历史或账本，不能使用这套整表重置清单，需要改为保留历史或显式迁移方案。

## 0. 执行前核对

- 当前 DATABASE 部署还需安装[平台模型出口策略](PLATFORM_AI_CONTROL.md#平台模型出口策略)
  。本清单的重置脚本保留全局白名单及其版本。重建供应商连接前，在「环境配置 → 模型配置 → 平台出口策略」核对所需 origin。
- 确认实际连接的库和本轮全量重置范围；同库所有租户／空间的 New-AI 模型、聊天和账本都会受影响。保留可恢复备份，并按目标库的实际
  `SHOW CREATE TABLE` 核对结构与外键。
- 停止所有连接该库的 API、Worker、验证任务、搜索扫描／回填／校准及事件投递进程；关闭相关浏览器页面，避免重建期间旧请求继续读写。不在仍有执行、预留或待结算任务的运行环境中直接重置。
- 前后端使用同一轮最终代码；页面建模使用 `arte.ai-new.configuration-mode=DATABASE`，不同时加载 `ai-fixed`
  profile。公共身份／持久化／空间、New-AI、模型管理及模型发布能力应按当前部署开启；创建租户模型还需开启
  `arte.ai-new-model-sharing.enabled` 并满足原业务资格。
- 模型凭据加密及指纹密钥配置由原部署密钥系统提供，不因清表生成或替换。供应商 API Key 已随凭据表清理，重建模型时需重新填写；两者不是同一类密钥。
- 菜单与业务授权保留。工作空间完整 AI 编辑入口为 `/Environment/Workspaces`，模型配置入口为 `/Environment/Models`
  。现有治理角色是否有工作空间菜单，按[只读菜单核对 SQL](../scripts/arte-model-m4-menu-audit-mysql.sql)
  检查；清数据不会自动补菜单、模型权限或凭据用途，也不要求重跑全部 RBAC 初始化。

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

### 1.2 New-AI 运行数据：41 张

旧版的 `arte_ai_conversation_projection_job` 已退出运行时和本清单。新装不再创建此表，已有表保留在原库。

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

这 41 张与第 3 节的 11 张工具配置表共同覆盖 [arte-ai-new-ddl-mysql.sql](../../arte-ai/scripts/arte-ai-new-ddl-mysql.sql)
的 52 张表；M0～M5 本轮模型配置改造没有要求修改这 41 张运行表的物理结构。另行安装的搜索进度表见第 1.4 节。 若当前库尚未完成其他
Tool／搜索／调度批次的安装，需另行对照实际结构；当前全新 DDL 已包含的历史增量不能重复执行。

### 1.3 公共表：按模型条件清理，不整表清空

| 表                            | 处理范围                                                                                                                                                        |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| arte_platform_resource_grant  | 只删除其 resource_key 对应 `resource_access.resource_type='model'` 的授权；先于 access 清理                                                                     |
| arte_platform_resource_access | 只删除 `resource_type='model'` 的 ACL 根，保留其他资源                                                                                                          |
| arte_platform_command         | 清理 `command_type LIKE 'model:%'`、`command_type LIKE 'workspace:ai:%'`、`result_schema='ai:catalog:view:v1'` 的旧回执；兼顾下述通用 ACL 回执                  |
| arte_platform_outbox          | 清理 `event_type LIKE 'model:%'`、`event_type LIKE 'workspace:ai:%'`、`event_type='ai:catalog:published'` 以及 attributes.resourceType 为 model 的通用 ACL 事件 |
| arte_platform_audit           | 可保留作为历史审计，不参与启动或恢复旧业务配置；功能重置不要求清空                                                                                              |

不要清空整个 command／outbox／audit，它们还服务于公共空间、政策及其他资源。 通用 `resource:*` ACL 命令不是都属于模型，不能直接按这个前缀全删。先从模型
ACL Outbox 的 `event.attributes.operationId` 收集其 operation_id，再清相应 command；也可核对回执中 value.resource 或
value.grants 的资源类型。
其中 `resource:void:v1` 回执没有目标内容，需通过关联事件定位；无法确认目标的历史通用回执可保留，不作为重新建模的入口。 这些关联
operation_id 应在删除模型事件之前收集。 按 `resource:register` 登记的模型 ACL 根也有通用回执，须一并识别，不能只处理后续
grant／revoke。
`arte_platform_outbox.event` 保存事件 JSON，核对其中 `attributes.resourceType` 和 `attributes.operationId`；不要把它们当作表的独立列。

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

### 1.4 已安装搜索进度：额外 4 张，保留结构

以下表来自单独的[搜索同步脚本](../../arte-ai/scripts/arte-ai-new-search-sync-migration.sql)，不在上方 41
张中。只要此前安装并产生过本轮待删除聊天的同步记录，即使当前关闭搜索，也要把残留纳入处理；未安装时不必为了模型重置而新增搜索能力。

```text
arte_ai_search_entry
arte_ai_search_work
arte_ai_search_checkpoint
arte_ai_search_generation
```

这些是按物理索引 `generation` 分区的身份／确认版本、任务、检查点与租约。停写后，按已确认属于本环境待重建的旧代际清理对应行；只有确认整张表的所有代际都属于此次全量重置时才整表清空，不删除其他环境或保留代际。

搜索 MySQL 进度、Elasticsearch 物理索引／别名、Redis 搜索会话／PIT
必须配套处理。不能只清同步版本而保留旧索引，或只删索引却复用旧代际的已确认进度。按[搜索初始化说明](../../arte-ai/CONVERSATION_SEARCH_INITIALIZATION.md)
为重建选择新的物理代际并显式完成创建／校准／切换；应用重启不会替换已有别名，也不会自动重置检查点。
旧代际及本环境对应搜索会话按专项流程清理，查询恢复前确认不会命中已删除的历史正文。不执行整库 Redis 清空或删除整个
Elasticsearch。

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

这里 `reviewedPlans`、`planRef`、`models` 等变化属于 JSON 契约。不能通过手工给旧目录补 `reviewedPlans: []`、仅重置
head，或仅删除某个空间配置来替代全段数据重建。

初始化定义按依赖对应以下文件：

1. [arte-platform-ai-control-ddl-mysql.sql](../scripts/arte-platform-ai-control-ddl-mysql.sql)：控制面、空间配置、独立预算规则及预算定义。
2. [arte-ai-model-resource-ddl-mysql.sql](../scripts/arte-ai-model-resource-ddl-mysql.sql)：资源、版本、0～3 来源约束。
3. [arte-ai-model-acl-scope-ddl-mysql.sql](../scripts/arte-ai-model-acl-scope-ddl-mysql.sql)：保留的 FIXED／迁移清单表。
4. [arte-ai-model-draft-ddl-mysql.sql](../scripts/arte-ai-model-draft-ddl-mysql.sql)：工作空间索引、草稿、凭据与验证。
5. [arte-ai-model-publication-ddl-mysql.sql](../scripts/arte-ai-model-publication-ddl-mysql.sql)：中性发布证据，依赖资源和版本。
6. [arte-ai-model-sharing-ddl-mysql.sql](../scripts/arte-ai-model-sharing-ddl-mysql.sql)：租户模型复合索引、共享与使用范围。

这些是初始化定义，不是“对已有表反复执行”的脚本。已有且一致的表只清空；缺表／索引或确需重建的表才执行对应语句，避免重复 CREATE
TABLE／CREATE INDEX；尤其不要重复执行控制面脚本中的 head 插入，或在索引已存在时重复执行草稿／共享脚本的 CREATE INDEX。
公共身份、公共空间与 ACL 表继续作为前置结构保留，不重复初始化它们的数据；实际缺失前置结构时先按相应初始化定义补齐，不能只装模型表。

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

如果工具配置也全部重新做，再额外清理上述 11 张；完成第 1.2 节后，一种依赖顺序为：

```text
arte_ai_tool_binding_resolution
arte_ai_tool_binding_revision
arte_ai_tool_binding_command
arte_ai_tool_binding
arte_ai_tool_release_command
arte_ai_tool_release
arte_ai_tool_discovery_command
arte_ai_tool_price_guard
arte_ai_tool_definition
arte_ai_tool_provider
arte_ai_tool_schema
```

表结构仍保留。保留配置的收费工具仍依赖原精确费率和空间预算；目录重置后须重新补齐对应批准条件，不能以已有工具绑定恢复预算资格。
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

因此不要按 `arte_ai_%` 通配符批量 DROP／TRUNCATE，也不要整库清空。 保留的租户／空间 `version`、`authEpoch`、成员代次和偏好不回退为
0；新页面会重新读取其当前版本。`arte_ai_control_head.version=0` 只表示审核目录未初始化，不是公共空间版本的重置指令。

## 5. 建议执行顺序

1. 完成第 0 节核对，停止所有相关进程及旧页面，保留可恢复备份；本次是整段开发数据重置。
2. 核对实际表结构，收集待清理的公共模型命令／ACL 事件 operation_id。
3. 先清第 1.2 节运行数据；若决定工具配置也重做，此时按外键依赖清其目录／绑定数据。
4. 删除旧 personal_publication 表；清理模型 use_scope → sharing、publication、validation／draft／credential、version →
   resource；清 definition_source 和 acl_scope。
5. 按条件清理模型 ACL、对应公共命令回执和事件。保留其他资源记录及公共空间／身份。
6. 清预算定义、空间 AI 选择、独立预算规则以及目录修订；按第 1.4 节处理搜索进度残留，补齐缺失／不一致的结构和索引。保留原有其他业务结构及数据。
7. 对齐全部重置完成后，把 arte_ai_control_head 恢复为唯一 `(1,0)`。若它已清空，应插入此行；不能只留下空表或只重置指针而保留旧关联数据。
8. 在所有曾连接该环境、涉及被重置数据的账号／浏览器标签中，处理 `arte.ainew.` 前缀的 sessionStorage／localStorage
   恢复记录、聊天配置和模型选择；同时移除 M4 的失效通知键 `arte.ai.configuration.revision`。旧请求键、资源 ID、binding 和
   budgetRef 不可带回新数据。只清相应 New-AI 项，保留登录与其他业务存储；不要将旧“待确认操作”对重建后的数据重新提交。
9. 按第 1.4 节同步处理本环境搜索索引／代际和会话缓存；在查询重新开放前核对无旧正文残留。按实际配置定位，不清空整个
   Redis／Elasticsearch。
10. 启动后先执行第 6 节的重建前核对，再从无旧资源／版本参数的模型配置页开始，按第 7
    节重新配置。旧详情深链接不会自动映射到新模型，应重新选择管理空间、目标空间和发布版本。

公共租户／空间政策仍保留，所以新连接继续受允许协议、供应商和目的地限制；如采用新的目标配置，需在对应政策中明确允许。
重置没有改变模型管理、租户共享、目标空间治理、凭据消费或平台提额权限。

## 6. 重建前的只读核对

在开始页面重建、暂停搜索初始化／同步的状态下核对：

- 15 张模型／控制面表中，除 head 外其余均应无旧记录；head 恰有一行 `(id=1, version=0)`。41 张运行表无旧记录，模型 ACL
  根／授权及已识别旧回执／事件已按范围处理。
- 若工具配置选择保留，11 张表的数据仍保留；若选择同时重建则均为空。已安装搜索表的待重建旧代际行按第 1.4 节处理，其他保留代际不受影响。
- 公共身份、租户／空间／成员／政策／偏好、RBAC 和文章等无关业务保持；旧 personal_publication 表已按第 3 节处理。
- API／Worker 不出现缺表、来源码约束、旧目录 JSON 或错误配置模式的启动失败。head=0 是合法空目录；读取页面本身不会建立模型、空间配置或本人账户。

示例只读 SQL（表存在且已完成结构处理后执行）：

```sql
SELECT id, version FROM arte_ai_control_head;

SELECT 'catalog' AS item, COUNT(*) AS row_count FROM arte_ai_control_revision
UNION ALL SELECT 'model_resource', COUNT(*) FROM arte_ai_model_resource
UNION ALL SELECT 'model_version', COUNT(*) FROM arte_ai_model_version
UNION ALL SELECT 'model_publication', COUNT(*) FROM arte_ai_model_publication
UNION ALL SELECT 'workspace_configuration', COUNT(*) FROM arte_ai_workspace_configuration
UNION ALL SELECT 'workspace_budget_rule', COUNT(*) FROM arte_ai_workspace_budget_rule
UNION ALL SELECT 'budget_definition', COUNT(*) FROM arte_ai_budget_definition
UNION ALL SELECT 'account', COUNT(*) FROM arte_ai_account
UNION ALL SELECT 'invocation', COUNT(*) FROM arte_ai_invocation
UNION ALL SELECT 'model_acl', COUNT(*) FROM arte_platform_resource_access WHERE resource_type = 'model';
```

这组计数是关键项抽查，不能代替对全部清理表和外键的核对；开始页面重建后，计数会随显式操作增加，不应再全部要求为 0。

## 7. 按最终页面重新配置

1. 平台配置者在“模型配置 → 审核连接”发布第一个连接。head=0 时可直接发布，服务端从空目录产生第一份新修订；无需先手工构造
   Catalog 或寻找额外初始化按钮。连接页仍受部署允许 origin 和平台配置资格约束。
2. 在“审核模型与费率”发布精确连接对应的审核方案及费率；需要新空间默认值时，在此 Tab 独立维护平台默认 AI
   模板。审核方案不直接成为聊天候选，发布也不创建预算。
3. 在“模型管理”选择管理空间及当前有资格创建的个人／租户归属；创建后直接在详情保存草稿、对应凭据、验证并发布固定版本。无需到旧“租户模型与授权”Tab
   再刷新接力。
4. 租户模型在同一详情分别配置允许目标空间、目标成员模型 READ／USE 和当前凭据代次的消费者。个人凭据仍只供责任主体使用，不因空间配置转交用途。
5. 前往“工作空间配置 → 目标空间 → 配置”，或从模型精确发布版本跳转。显式首次准备后，分别保存 AI 开关／实际模型固定版本及精确费率预算规则。配置
   B 不切换当前聊天 A；空间启用不建本人账户，规则保存不代替模型／凭据授权。
6. 在目标空间聊天，检查精确版本的本人使用条件，准备本人兼容账户，重新加载可用配置并手动应用模型、参数和预算后发送。普通用户无需治理菜单即可执行本人准备；缺少空间启用／规则时由空间负责人处理。
7. 先用一份实际模型完成成功调用，核对执行主体、目标空间、费率和 SETTLED
   账本；再按需要增加其他模型。收费工具恢复时还要独立检查其费率／预算批准条件，文本模型成功不代表工具链路均已验收。

本清单只依据当前仓库作结构和清理范围说明，没有读取目标库的 SHOW CREATE TABLE，也没有执行任何清库、DROP、ALTER 或初始化操作。

## 附录-脚本

### A.0 章节对应与执行顺序

SQL 正文保存在 [model-reset 脚本目录](../scripts/model-reset/)，下面的 `SOURCE` 命令可在 MySQL 命令行客户端执行。使用
MySQL 8.0.16+（支持 CHECK 与本文语法）；SQL 编辑器执行时须支持 `DELIMITER`，并按同样顺序保持同一连接。不要一次跳过核对直接运行所有分支。

| 顺序 | 对应正文                                         | 脚本／动作                                                                                                                       | 是否修改数据           |
|------|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|------------------------|
| A.1  | 第 0、2、4 节；第 5 节步骤 1～2                  | 停写、备份、[01-precheck.sql](../scripts/model-reset/01-precheck.sql) 与原菜单只读核对                                           | 只读                   |
| A.2  | 第 0、3、5 节                                    | 明确库名、工具分支、旧搜索代际和 source 结构修复参数                                                                             | 仅设置连接变量         |
| A.3  | 第 1.1／1.2／1.3／1.4、3、4 节；第 5 节步骤 2～7 | [02-reset-data.sql](../scripts/model-reset/02-reset-data.sql)：先删废弃旧表，再按依赖清理；默认保留工具配置                      | 删除数据及唯一旧表     |
| A.4  | 第 2 节；第 5 节步骤 6～7                        | [03-repair-structure.sql](../scripts/model-reset/03-repair-structure.sql)：补缺表／索引，条件修复空的 source 表，确保 head=(1,0) | 结构及 head 初始化     |
| A.5  | 第 1.4 节；第 5 节步骤 9                         | [04-reset-search.py](../scripts/model-reset/04-reset-search.py)：仅在有旧搜索残留时处理确切旧索引／独占缓存命名空间              | 条件删除搜索数据       |
| A.6  | 第 5 节步骤 8                                    | 当前应用 origin 的浏览器存储脚本                                                                                                 | 删除匹配的本地恢复记录 |
| A.7  | 第 4、6 节；第 5 节步骤 10 的启动前核对          | [05-verify.sql](../scripts/model-reset/05-verify.sql) 和再次预检                                                                 | 只读                   |
| A.8  | 第 7 节                                          | 启动并按页面重建；调用成功后核对账本                                                                                             | 页面上的显式配置及调用 |

A.5／A.6 是数据库清理后的独立环境清理，在启动前依次完成，再运行最终核对。涉及搜索时，查询须保持关闭直至新代际创建、登记及别名核验完成。

脚本不会修改公共身份、租户／空间、成员、政策、偏好、RBAC、审计、文章及旧 AI 表；第 3 节的 11 张工具表仅在 `@arte_reset_tools=1`
时清数据。主数据清理采用 DELETE 和原外键，不使用 TRUNCATE 或关闭外键。

数据库写脚本需要对应 DELETE／CREATE／DROP／INDEX 与 CREATE ROUTINE／ALTER ROUTINE 权限，维护例程统一使用
`arte_doc_model_reset_*` 名称和 `SQL SECURITY INVOKER`，执行后移除；这组名称留给本附录使用。只读账号仅执行 A.1、A.7
的查询；缺少维护权限时由已有相应权限的维护人员执行，不在本脚本中修改授权。

### A.1 选择目标库并只读预检（正文第 0、2、4 节）

先按第 0 节完成备份、停进程及关闭旧页面。以下命令在本机仓库执行；`arte-reset` 是自行配置好的 MySQL 客户端 login-path，
`your_arte_database` 必须替换为明确的目标应用库。连接信息不写进脚本，不使用 `--force` 忽略错误。

```sh
cd /Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app
mysql --login-path=arte-reset --database=your_arte_database --show-warnings
```

在此 MySQL 连接中执行：

```sql
SOURCE scripts/model-reset/01-precheck.sql;
SOURCE scripts/arte-model-m4-menu-audit-mysql.sql;
```

预检返回实际数据库／版本、表清单、source 类型和 CHECK、资源索引及入向外键。InnoDB 的 `estimated_rows`
仅用于盘点，不代替最终精确计数。发现额外外键、非 InnoDB、结构与正文不符时先处理实际依赖。脚本只补模型领域结构，未安装的运行／工具／搜索模块需按其各自
DDL 补齐缺失语句，不整份重复初始化。

### A.2 设置明确参数（正文第 0、1.4、2、3 节）

仍在同一 MySQL 连接中设置；库名必须与 A.1 完全一致。默认保留工具配置；默认搜索数组为空，表示不删任何搜索进度，不能在有旧搜索残留时留空后直接开放查询。

```sql
SET @arte_reset_expected_database = 'your_arte_database';
SET @arte_reset_confirmation = 'RESET_NEW_AI_ALL_TENANTS';
SET @arte_reset_tools = 0;
SET @arte_reset_search_generations = JSON_ARRAY();
SET @arte_reset_rebuild_source = 0;
```

按预检结果，仅调整需要的分支，例如：

```sql
-- 仅当第 3 节的 11 张工具配置也明确全部重建时：
-- SET @arte_reset_tools = 1;

-- 仅填本环境明确要重建的旧物理代际，不填 alias、不填新代际、不使用通配符：
-- SET @arte_reset_search_generations = JSON_ARRAY('conversation-search-v2-g01');

-- 仅当 source 类型／CHECK 与第 2 节不一致、确认清空后重建此表时：
-- SET @arte_reset_rebuild_source = 1;
```

`rebuild_source=0` 不覆盖已存在结构；检查发现 source 不为 int 或未采用本轮 0～3 约束时会报错，不能带错结构继续启动。设为 1
时也必须先通过空表检查。

### A.3 执行数据清理（正文第 1、3、4 节；第 5 节步骤 2～7）

```sql
SOURCE scripts/model-reset/02-reset-data.sql;
SELECT @arte_reset_data_done AS completed_database;
```

脚本内部顺序与正文对应：

1. 核验实际库名、确认值、分支参数和公共 ACL／命令／Outbox／审计前置表。
2. 删除唯一废弃的 `arte_ai_model_personal_publication` 表，解除旧发布表的外键依赖。
3. 在一个数据事务中，先从模型 ACL 事件及可识别回执收集 operation_id，再按第 1.2 节顺序 DELETE 41 张运行表。
4. 只在工具分支为 1 时，按第 3 节列出的子表优先顺序清 11 张工具配置表。
5. 按 use_scope → sharing、publication、validation／draft／credential、version → resource 清模型记录，并清
   definition_source／acl_scope。
6. 仅删除模型公共授权根／grant、模型及空间 AI 命令／事件、审核目录回执；审计及其他资源保留。无法确定目标的历史 resource:void
   回执保留。
7. 清预算定义、空间选择、规则和目录修订；head 已存在时恢复唯一 `(1,0)`，缺失时留给 A.4 创建。
8. 对已安装搜索表，仅 DELETE 参数数组中列出的旧 generation；最后 COMMIT，返回 `DATA_RESET_OK` 和目标库。

缺失的本轮新增模型表、未安装的可选表会跳过，A.4／A.7 继续检查；不会对不明同名前缀表批量操作。数据事务异常会 ROLLBACK 并清除完成标记。
**旧表 DROP 属于独立 DDL，不能由后续 ROLLBACK 恢复**；备份仍是旧表的恢复来源。任一步报错应立即停止，查看原因，不开始页面重建。

### A.4 补齐结构及 head（正文第 2 节；第 5 节步骤 6～7）

```sql
SOURCE scripts/model-reset/03-repair-structure.sql;
SELECT @arte_reset_structure_done AS completed_database;
```

该阶段只接受同一连接中 A.3 成功的库；再次确认模型／控制面表为空、head 未推进，然后按当前初始化定义补齐第 1.1 节 15
张模型／控制面表以及两个资源索引。已有表通过 `CREATE TABLE IF NOT EXISTS` 保留，已有同名索引不重复创建；同名索引列序／唯一性不符时停止；source
仅按 A.2 明确选择重建空表。DDL 定义来自正文列出的原始脚本，不新增 JSON 独立列，不安装旧个人来源迁移。

DDL 有隐式提交，结构阶段不能整体回滚；`IF NOT EXISTS` 也不能验证全部已有列／外键定义。必须结合 A.1 和 A.7
核对；如发现其他表结构有差异，应按其实际缺项处理，不能仅看到 `MODEL_STRUCTURE_OK` 就认定整库升级完成。完成后应返回
`MODEL_STRUCTURE_OK`。

### A.5 条件处理旧搜索环境（正文第 1.4 节；第 5 节步骤 9）

没有安装或使用过搜索时跳过本节。有残留时，A.2 的 SQL 旧代际数组与下方 `ARTE_RESET_ES_OLD_GENERATIONS` 必须列出同一范围。ES／Redis
脚本会先核验别名均只指向这些旧代际、旧代际没有额外使用者、新代际尚不存在，以及 Redis
连接／命名空间格式，然后才删除。若旧命名空间被其他环境共用，不能填写独占确认值执行此脚本，应先拆分并制定分范围方案。

保持 API／Worker／搜索停写，另开终端执行，参数按真实环境修改：

```sh
cd /Users/cylj126/Workspace/my_project/arte_dev/backend/arte-app
export ARTE_RESET_SEARCH_CONFIRM='RESET_OLD_SEARCH_GENERATIONS'
export ARTE_RESET_ES_URL='http://127.0.0.1:9200'
export ARTE_RESET_ES_OLD_GENERATIONS='["conversation-search-v2-g01"]'
export ARTE_RESET_ES_NEW_GENERATION='conversation-search-v2-reset-20261103'
export ARTE_RESET_ES_READ_ALIAS='conversation-search-read'
export ARTE_RESET_ES_WRITE_ALIAS='conversation-search-write'
export ARTE_RESET_REDIS_OLD_NAMESPACE='arte:ai-new:conversation-search:v1'
export ARTE_RESET_REDIS_NEW_NAMESPACE='arte:ai-new:conversation-search:reset-20261103'
export ARTE_RESET_REDIS_NAMESPACE_EXCLUSIVE='YES'
export ARTE_RESET_REDIS_ARGS_JSON='["-h","127.0.0.1","-p","6379","-n","0"]'
# ES 若需要认证／TLS，指定已安全保存的 curl 配置；不使用 -k：
# export ARTE_RESET_ES_CURL_CONFIG='/absolute/path/to/es.curl.conf'
# Redis 认证沿用 REDISCLI_AUTH；不要把密码写入参数数组。
# 若已记录本次旧会话的 PIT，可明确传入；不会使用 _all 关闭其他会话：
# export ARTE_RESET_ES_OLD_PIT_IDS_JSON='["known-old-pit-id"]'
python3 scripts/model-reset/04-reset-search.py
```

脚本只 DELETE 明确旧物理索引，按旧搜索命名空间扫描并 UNLINK 匹配键，不执行 FLUSHDB／FLUSHALL、通配索引删除或自动构造新映射。已知旧
PIT 会关闭，未传入的由其 keep_alive 到期释放。索引／缓存清理跨服务不具备单一事务；失败后按输出检查实际完成范围，不宣称整体回滚。

启动新环境前，将部署配置改为下列新值（变量示例沿用上面参数；实际配置需重建资源时按项目部署方式生效）：

```sh
export ARTE_AI_NEW_SEARCH_GENERATION="$ARTE_RESET_ES_NEW_GENERATION"
export ARTE_AI_NEW_SEARCH_REDIS_NAMESPACE="$ARTE_RESET_REDIS_NEW_NAMESPACE"
export ARTE_AI_NEW_SEARCH_INITIALIZE_ON_STARTUP=true
export ARTE_AI_NEW_SEARCH_QUERY_ENABLED=false
```

按[搜索初始化说明](../../arte-ai/CONVERSATION_SEARCH_INITIALIZATION.md)启动初始化，确认新的物理索引、MySQL
generation／四通道检查点和读写别名一致后，再显式恢复查询。不要使用上面旧代际名称重建，也不要在存在额外未审定别名时强行继续。

### A.6 清理浏览器恢复记录（正文第 5 节步骤 8）

在仍保留的、对应应用 origin 的页面开发者工具 Console 执行；其余旧页面保持关闭，每个涉及的浏览器标签分别处理自己的
sessionStorage。先修改 `expectedOrigin` 为真实应用 origin；首次 `apply=false` 只列出匹配键，核对后改为 true 再执行。不要输出存储值。

```javascript
(() => {
  const expectedOrigin = 'http://localhost:8000'; // 改为实际应用 origin
  const apply = false; // 核对键名后改为 true
  if (location.origin !== expectedOrigin) throw new Error('当前 origin 与目标环境不符');
  const matches = key => key?.startsWith('arte.ainew.') || key === 'arte.ai.configuration.revision';
  const rows = [];
  for (const [name, storage] of [['sessionStorage', sessionStorage], ['localStorage', localStorage]]) {
    const keys = Array.from({length: storage.length}, (_, index) => storage.key(index)).filter(matches);
    for (const key of keys) {
      rows.push({storage: name, key, removed: apply});
      if (apply) storage.removeItem(key);
    }
  }
  console.table(rows);
  console.info(apply ? '匹配恢复项已移除；服务重建完成后再重新打开页面。' : '当前仅预览键名，未删除。');
})();
```

不清登录、公共空间偏好及其他业务存储，不恢复旧资源深链接或未知旧操作；重新打开不带旧 resourceId／binding／budgetRef 的页面。

### A.7 最终只读核对（正文第 4、6 节）

返回原 MySQL 连接执行：

```sql
SOURCE scripts/model-reset/05-verify.sql;
SOURCE scripts/model-reset/01-precheck.sql;
```

完成标记应均等于目标库；head 恰有 `(1,0)`；41 张运行表、其余模型／控制面表和可识别模型 ACL／命令／事件应为 0。非模型
ACL／grant、全部审计的计数应与 A.3 基线一致；工具表默认允许有原数据，仅分支为 1 时要求为 0。NULL
基线、缺表错误或不符结果均不算通过。原有其他公共／业务表不在写脚本的修改目标内，仍应与备份核对需要保留的内容。

第 1.4 节搜索表仅在存在时，按原 SQL 参数检查旧代际清零；该检查可在同一连接按下列方式执行（四表都已安装时）：

```sql
SELECT 'search_entry' AS item, COUNT(*) AS remaining FROM arte_ai_search_entry
WHERE JSON_CONTAINS(@arte_reset_search_generations, JSON_QUOTE(generation))
UNION ALL SELECT 'search_work', COUNT(*) FROM arte_ai_search_work
WHERE JSON_CONTAINS(@arte_reset_search_generations, JSON_QUOTE(generation))
UNION ALL SELECT 'search_checkpoint', COUNT(*) FROM arte_ai_search_checkpoint
WHERE JSON_CONTAINS(@arte_reset_search_generations, JSON_QUOTE(generation))
UNION ALL SELECT 'search_generation', COUNT(*) FROM arte_ai_search_generation
WHERE JSON_CONTAINS(@arte_reset_search_generations, JSON_QUOTE(generation));
```

### A.8 启动及页面重建（正文第 7 节）

完成上述核对后，按当前部署方式启动一致版本的后端／前端，再按第 7 节执行“审核连接 → 审核模型与费率／默认模板 →
统一个人／租户模型 → 目标工作空间 AI → 本人准备／手动应用 → 调用及结算核对”。head=0
可直接发布第一份审核连接；不提供绕过正式验证／发布、授权、空间治理或账本门控的直写业务 SQL。

本附录提供的是可由维护人员按参数执行的脚本；本次编写仅作静态／离线检查，未连接 MySQL、Redis 或 Elasticsearch，也未执行浏览器清理或页面重建。
