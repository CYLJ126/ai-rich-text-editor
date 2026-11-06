-- 第 4、6 节：只读最终核对。应用及搜索初始化仍须暂停。
-- 缺运行表时查询会报错：先按预检和实际模块 DDL 补齐缺表，不能忽略错误后启动。
SELECT DATABASE()                 AS target_database,
       @arte_reset_data_done      AS data_reset_database,
       @arte_reset_structure_done AS structure_repair_database;
SELECT id, version
FROM arte_ai_control_head;

-- 以下全部应为 0；head 单独核对恰有 (1,0)。
SELECT 'arte_ai_lock' AS table_name, COUNT(*) AS row_count
FROM arte_ai_lock
UNION ALL
SELECT 'arte_ai_acceptance' AS table_name, COUNT(*) AS row_count
FROM arte_ai_acceptance
UNION ALL
SELECT 'arte_ai_event' AS table_name, COUNT(*) AS row_count
FROM arte_ai_event
UNION ALL
SELECT 'arte_ai_operation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_operation
UNION ALL
SELECT 'arte_ai_outbox' AS table_name, COUNT(*) AS row_count
FROM arte_ai_outbox
UNION ALL
SELECT 'arte_ai_conversation_new' AS table_name, COUNT(*) AS row_count
FROM arte_ai_conversation_new
UNION ALL
SELECT 'arte_ai_turn' AS table_name, COUNT(*) AS row_count
FROM arte_ai_turn
UNION ALL
SELECT 'arte_ai_account' AS table_name, COUNT(*) AS row_count
FROM arte_ai_account
UNION ALL
SELECT 'arte_ai_reservation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_reservation
UNION ALL
SELECT 'arte_ai_settlement' AS table_name, COUNT(*) AS row_count
FROM arte_ai_settlement
UNION ALL
SELECT 'arte_ai_conversation_creation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_conversation_creation
UNION ALL
SELECT 'arte_ai_context_snapshot' AS table_name, COUNT(*) AS row_count
FROM arte_ai_context_snapshot
UNION ALL
SELECT 'arte_ai_result' AS table_name, COUNT(*) AS row_count
FROM arte_ai_result
UNION ALL
SELECT 'arte_ai_dispatch_permit' AS table_name, COUNT(*) AS row_count
FROM arte_ai_dispatch_permit
UNION ALL
SELECT 'arte_ai_dispatch_rate' AS table_name, COUNT(*) AS row_count
FROM arte_ai_dispatch_rate
UNION ALL
SELECT 'arte_ai_conversation_metadata' AS table_name, COUNT(*) AS row_count
FROM arte_ai_conversation_metadata
UNION ALL
SELECT 'arte_ai_conversation_metadata_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_conversation_metadata_command
UNION ALL
SELECT 'arte_ai_conversation_message_fact' AS table_name, COUNT(*) AS row_count
FROM arte_ai_conversation_message_fact
UNION ALL
SELECT 'arte_ai_tool_execution_snapshot' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_execution_snapshot
UNION ALL
SELECT 'arte_ai_scheduling_permit' AS table_name, COUNT(*) AS row_count
FROM arte_ai_scheduling_permit
UNION ALL
SELECT 'arte_ai_dispatch_cursor' AS table_name, COUNT(*) AS row_count
FROM arte_ai_dispatch_cursor
UNION ALL
SELECT 'arte_ai_dispatch_pool' AS table_name, COUNT(*) AS row_count
FROM arte_ai_dispatch_pool
UNION ALL
SELECT 'arte_ai_queue_capacity' AS table_name, COUNT(*) AS row_count
FROM arte_ai_queue_capacity
UNION ALL
SELECT 'arte_ai_tool_approval' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_approval
UNION ALL
SELECT 'arte_ai_tool_approval_audience' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_approval_audience
UNION ALL
SELECT 'arte_ai_tool_outcome_evidence' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_outcome_evidence
UNION ALL
SELECT 'arte_ai_tool_outcome_scan' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_outcome_scan
UNION ALL
SELECT 'arte_ai_tool_outcome_review' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_outcome_review
UNION ALL
SELECT 'arte_ai_tool_outcome_review_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_outcome_review_command
UNION ALL
SELECT 'arte_ai_tool_chat_run_work' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_run_work
UNION ALL
SELECT 'arte_ai_tool_chat_run_step' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_run_step
UNION ALL
SELECT 'arte_ai_tool_chat_run_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_run_command
UNION ALL
SELECT 'arte_ai_tool_chat_link' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_link
UNION ALL
SELECT 'arte_ai_tool_chat_run_event' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_run_event
UNION ALL
SELECT 'arte_ai_tool_chat_budget' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_budget
UNION ALL
SELECT 'arte_ai_tool_chat_budget_allocation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_budget_allocation
UNION ALL
SELECT 'arte_ai_tool_bill' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_bill
UNION ALL
SELECT 'arte_ai_attempt' AS table_name, COUNT(*) AS row_count
FROM arte_ai_attempt
UNION ALL
SELECT 'arte_ai_tool_outcome_check' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_outcome_check
UNION ALL
SELECT 'arte_ai_tool_chat_run' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_chat_run
UNION ALL
SELECT 'arte_ai_invocation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_invocation
UNION ALL
SELECT 'arte_ai_model_use_scope' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_use_scope
UNION ALL
SELECT 'arte_ai_model_sharing' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_sharing
UNION ALL
SELECT 'arte_ai_model_publication' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_publication
UNION ALL
SELECT 'arte_ai_model_validation' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_validation
UNION ALL
SELECT 'arte_ai_model_draft' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_draft
UNION ALL
SELECT 'arte_ai_model_credential' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_credential
UNION ALL
SELECT 'arte_ai_model_version' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_version
UNION ALL
SELECT 'arte_ai_model_resource' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_resource
UNION ALL
SELECT 'arte_ai_model_definition_source' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_definition_source
UNION ALL
SELECT 'arte_ai_model_acl_scope' AS table_name, COUNT(*) AS row_count
FROM arte_ai_model_acl_scope
UNION ALL
SELECT 'arte_ai_budget_definition' AS table_name, COUNT(*) AS row_count
FROM arte_ai_budget_definition
UNION ALL
SELECT 'arte_ai_workspace_configuration' AS table_name, COUNT(*) AS row_count
FROM arte_ai_workspace_configuration
UNION ALL
SELECT 'arte_ai_workspace_budget_rule' AS table_name, COUNT(*) AS row_count
FROM arte_ai_workspace_budget_rule
UNION ALL
SELECT 'arte_ai_control_revision' AS table_name, COUNT(*) AS row_count
FROM arte_ai_control_revision;

SELECT 'model_acl' AS item, COUNT(*) AS row_count
FROM arte_platform_resource_access
WHERE resource_type = 'model'
UNION ALL
SELECT 'model_grant', COUNT(*)
FROM arte_platform_resource_grant g
         JOIN arte_platform_resource_access a
              ON a.resource_key = g.resource_key
WHERE a.resource_type = 'model'
UNION ALL
SELECT 'model_and_workspace_commands', COUNT(*)
FROM arte_platform_command
WHERE command_type LIKE 'model:%'
   OR command_type LIKE 'workspace:ai:%'
   OR result_schema = 'ai:catalog:view:v1'
UNION ALL
SELECT 'model_and_workspace_events', COUNT(*)
FROM arte_platform_outbox
WHERE event_type LIKE 'model:%'
   OR event_type LIKE 'workspace:ai:%'
   OR event_type = 'ai:catalog:published'
   OR JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'), '$.attributes.resourceType')) = 'model';

-- 这些 public 数量必须与 02 中记录的基线相同；NULL 表示未在同一连接运行，不能据此判通过。
SELECT 'other_acl' AS protected_item, @arte_reset_before_other_acl AS before_rows, COUNT(*) AS after_rows
FROM arte_platform_resource_access
WHERE resource_type <> 'model'
UNION ALL
SELECT 'other_grants', @arte_reset_before_other_grants, COUNT(*)
FROM arte_platform_resource_grant g
         JOIN arte_platform_resource_access a ON a.resource_key = g.resource_key
WHERE a.resource_type <> 'model'
UNION ALL
SELECT 'all_audit', @arte_reset_before_audit, COUNT(*)
FROM arte_platform_audit;

-- 默认保留工具配置；只在 tools_reset=1 时要求这组也为 0。
SELECT @arte_reset_tools AS tools_reset;
SELECT 'arte_ai_tool_binding_resolution' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_binding_resolution
UNION ALL
SELECT 'arte_ai_tool_binding_revision' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_binding_revision
UNION ALL
SELECT 'arte_ai_tool_binding_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_binding_command
UNION ALL
SELECT 'arte_ai_tool_binding' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_binding
UNION ALL
SELECT 'arte_ai_tool_release_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_release_command
UNION ALL
SELECT 'arte_ai_tool_release' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_release
UNION ALL
SELECT 'arte_ai_tool_discovery_command' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_discovery_command
UNION ALL
SELECT 'arte_ai_tool_price_guard' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_price_guard
UNION ALL
SELECT 'arte_ai_tool_definition' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_definition
UNION ALL
SELECT 'arte_ai_tool_provider' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_provider
UNION ALL
SELECT 'arte_ai_tool_schema' AS table_name, COUNT(*) AS row_count
FROM arte_ai_tool_schema;

-- 再执行 01-precheck.sql 复核列、CHECK、索引和外键；CREATE IF NOT EXISTS 不检查已存在表的全部定义。
-- 不盲目删无法定位的 resource:void:v1 回执；这类保留项见正文第 1.3 节。
