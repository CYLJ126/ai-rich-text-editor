-- 第 0、2、3、4 节：只读预检，先由客户端 USE 选择目标库。
SELECT DATABASE() AS target_database, VERSION() AS mysql_version, @@foreign_key_checks AS foreign_key_checks;
-- table_rows 为 InnoDB 估计数，只作盘点；精确清零检查在 05-verify.sql。
SELECT table_name, engine, table_rows AS estimated_rows
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN (
                     'arte_ai_model_use_scope',
                     'arte_ai_model_sharing',
                     'arte_ai_model_publication',
                     'arte_ai_model_validation',
                     'arte_ai_model_draft',
                     'arte_ai_model_credential',
                     'arte_ai_model_version',
                     'arte_ai_model_resource',
                     'arte_ai_model_definition_source',
                     'arte_ai_model_acl_scope',
                     'arte_ai_budget_definition',
                     'arte_ai_workspace_configuration',
                     'arte_ai_workspace_budget_rule',
                     'arte_ai_control_revision',
                     'arte_ai_control_head',
                     'arte_ai_lock',
                     'arte_ai_acceptance',
                     'arte_ai_event',
                     'arte_ai_operation',
                     'arte_ai_outbox',
                     'arte_ai_conversation_new',
                     'arte_ai_turn',
                     'arte_ai_account',
                     'arte_ai_reservation',
                     'arte_ai_settlement',
                     'arte_ai_conversation_creation',
                     'arte_ai_context_snapshot',
                     'arte_ai_result',
                     'arte_ai_dispatch_permit',
                     'arte_ai_dispatch_rate',
                     'arte_ai_conversation_metadata',
                     'arte_ai_conversation_metadata_command',
                     'arte_ai_conversation_message_fact',
                     'arte_ai_conversation_projection_job',
                     'arte_ai_tool_execution_snapshot',
                     'arte_ai_scheduling_permit',
                     'arte_ai_dispatch_cursor',
                     'arte_ai_dispatch_pool',
                     'arte_ai_queue_capacity',
                     'arte_ai_tool_approval',
                     'arte_ai_tool_approval_audience',
                     'arte_ai_tool_outcome_evidence',
                     'arte_ai_tool_outcome_scan',
                     'arte_ai_tool_outcome_review',
                     'arte_ai_tool_outcome_review_command',
                     'arte_ai_tool_chat_run_work',
                     'arte_ai_tool_chat_run_step',
                     'arte_ai_tool_chat_run_command',
                     'arte_ai_tool_chat_link',
                     'arte_ai_tool_chat_run_event',
                     'arte_ai_tool_chat_budget',
                     'arte_ai_tool_chat_budget_allocation',
                     'arte_ai_tool_bill',
                     'arte_ai_attempt',
                     'arte_ai_tool_outcome_check',
                     'arte_ai_tool_chat_run',
                     'arte_ai_invocation',
                     'arte_ai_tool_binding_resolution',
                     'arte_ai_tool_binding_revision',
                     'arte_ai_tool_binding_command',
                     'arte_ai_tool_binding',
                     'arte_ai_tool_release_command',
                     'arte_ai_tool_release',
                     'arte_ai_tool_discovery_command',
                     'arte_ai_tool_price_guard',
                     'arte_ai_tool_definition',
                     'arte_ai_tool_provider',
                     'arte_ai_tool_schema',
                     'arte_ai_search_entry',
                     'arte_ai_search_work',
                     'arte_ai_search_checkpoint',
                     'arte_ai_search_generation',
                     'arte_ai_model_personal_publication'
    )
ORDER BY table_name;

SELECT table_name, column_name, column_type, is_nullable, collation_name
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name IN ('arte_ai_model_definition_source', 'arte_ai_model_publication', 'arte_ai_workspace_budget_rule')
ORDER BY table_name, ordinal_position;

SELECT tc.table_name, tc.constraint_name, cc.check_clause
FROM information_schema.table_constraints tc
         JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name
WHERE tc.table_schema = DATABASE()
  AND tc.table_name = 'arte_ai_model_definition_source'
  AND tc.constraint_type = 'CHECK';

SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_order
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'arte_ai_model_resource'
GROUP BY index_name, non_unique;

-- 核对所有入向外键；未知的额外子表应先处理，不通过关闭外键绕过。
SELECT table_name AS child_table, column_name, referenced_table_name AS parent_table, referenced_column_name
FROM information_schema.key_column_usage
WHERE referenced_table_schema = DATABASE()
  AND referenced_table_name IN (
                                'arte_ai_model_use_scope',
                                'arte_ai_model_sharing',
                                'arte_ai_model_publication',
                                'arte_ai_model_validation',
                                'arte_ai_model_draft',
                                'arte_ai_model_credential',
                                'arte_ai_model_version',
                                'arte_ai_model_resource',
                                'arte_ai_model_definition_source',
                                'arte_ai_model_acl_scope',
                                'arte_ai_budget_definition',
                                'arte_ai_workspace_configuration',
                                'arte_ai_workspace_budget_rule',
                                'arte_ai_control_revision',
                                'arte_ai_control_head',
                                'arte_ai_lock',
                                'arte_ai_acceptance',
                                'arte_ai_event',
                                'arte_ai_operation',
                                'arte_ai_outbox',
                                'arte_ai_conversation_new',
                                'arte_ai_turn',
                                'arte_ai_account',
                                'arte_ai_reservation',
                                'arte_ai_settlement',
                                'arte_ai_conversation_creation',
                                'arte_ai_context_snapshot',
                                'arte_ai_result',
                                'arte_ai_dispatch_permit',
                                'arte_ai_dispatch_rate',
                                'arte_ai_conversation_metadata',
                                'arte_ai_conversation_metadata_command',
                                'arte_ai_conversation_message_fact',
                                'arte_ai_conversation_projection_job',
                                'arte_ai_tool_execution_snapshot',
                                'arte_ai_scheduling_permit',
                                'arte_ai_dispatch_cursor',
                                'arte_ai_dispatch_pool',
                                'arte_ai_queue_capacity',
                                'arte_ai_tool_approval',
                                'arte_ai_tool_approval_audience',
                                'arte_ai_tool_outcome_evidence',
                                'arte_ai_tool_outcome_scan',
                                'arte_ai_tool_outcome_review',
                                'arte_ai_tool_outcome_review_command',
                                'arte_ai_tool_chat_run_work',
                                'arte_ai_tool_chat_run_step',
                                'arte_ai_tool_chat_run_command',
                                'arte_ai_tool_chat_link',
                                'arte_ai_tool_chat_run_event',
                                'arte_ai_tool_chat_budget',
                                'arte_ai_tool_chat_budget_allocation',
                                'arte_ai_tool_bill',
                                'arte_ai_attempt',
                                'arte_ai_tool_outcome_check',
                                'arte_ai_tool_chat_run',
                                'arte_ai_invocation',
                                'arte_ai_tool_binding_resolution',
                                'arte_ai_tool_binding_revision',
                                'arte_ai_tool_binding_command',
                                'arte_ai_tool_binding',
                                'arte_ai_tool_release_command',
                                'arte_ai_tool_release',
                                'arte_ai_tool_discovery_command',
                                'arte_ai_tool_price_guard',
                                'arte_ai_tool_definition',
                                'arte_ai_tool_provider',
                                'arte_ai_tool_schema',
                                'arte_ai_search_entry',
                                'arte_ai_search_work',
                                'arte_ai_search_checkpoint',
                                'arte_ai_search_generation',
                                'arte_ai_model_personal_publication'
    )
ORDER BY referenced_table_name, table_name, ordinal_position;

-- 菜单核对脚本另见 ../arte-model-m4-menu-audit-mysql.sql，不执行授权 DML。
