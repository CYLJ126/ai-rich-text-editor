-- 对应重置清单第 0、1、3、4、5 节。MySQL 8.0.16+，同一维护连接执行。
-- 所有应用节点须停写；只编写脚本不会执行。参数见文档“附录-脚本”。
-- 仅旧 personal_publication 的 DROP 为不可回滚 DDL；后续数据清理同一事务。
SET
@arte_reset_data_done = NULL;
SET
@arte_reset_structure_done = NULL;
DROP PROCEDURE IF EXISTS arte_doc_model_reset_data;
DROP PROCEDURE IF EXISTS arte_doc_model_reset_delete;
DELIMITER
$$
CREATE PROCEDURE arte_doc_model_reset_delete(IN table_name_arg VARCHAR (128), IN search_only BOOLEAN)
    SQL SECURITY INVOKER
BEGIN
    IF
table_name_arg NOT REGEXP '^arte_ai_[a-z0-9_]+$' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid reset table';
END IF;
    IF
EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = table_name_arg AND table_type = 'BASE TABLE') THEN
        IF EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema = DATABASE() AND table_name = table_name_arg AND engine <> 'InnoDB') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reset requires InnoDB tables';
END IF;
        SET
@arte_doc_sql = CONCAT('DELETE FROM `', table_name_arg, '`',
            IF(search_only, ' WHERE generation IN (SELECT generation FROM arte_doc_reset_generations)', ''));
PREPARE arte_doc_stmt FROM @arte_doc_sql;
EXECUTE arte_doc_stmt;
DEALLOCATE PREPARE arte_doc_stmt;
END IF;
END$$

CREATE PROCEDURE arte_doc_model_reset_data()
    SQL SECURITY INVOKER
BEGIN
    DECLARE
generation_index INT DEFAULT 0;
    DECLARE
generation_value VARCHAR(128);
    DECLARE
generation_json JSON;
    DECLARE
EXIT HANDLER FOR SQLEXCEPTION
BEGIN
ROLLBACK;
SET
@arte_reset_data_done = NULL;
        RESIGNAL;
END;

    IF
DATABASE() IS NULL OR @arte_reset_expected_database IS NULL
       OR BINARY DATABASE() <> BINARY @arte_reset_expected_database
       OR COALESCE(@arte_reset_confirmation, '') <> 'RESET_NEW_AI_ALL_TENANTS' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Select and explicitly confirm the target database';
END IF;
    IF
COALESCE(@arte_reset_tools, -1) NOT IN (0, 1)
       OR COALESCE(@arte_reset_rebuild_source, -1) NOT IN (0, 1) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Set tools and rebuild_source explicitly to 0 or 1';
END IF;
    IF
@@foreign_key_checks <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Enable foreign_key_checks before running the reset';
END IF;
    IF
@arte_reset_search_generations IS NULL OR NOT JSON_VALID(@arte_reset_search_generations) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Set search_generations to a JSON array';
END IF;
    IF
JSON_TYPE(CAST(@arte_reset_search_generations AS JSON)) <> 'ARRAY' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'search_generations must be an array';
END IF;
    IF
(
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('arte_platform_resource_access', 'arte_platform_resource_grant',
                     'arte_platform_command', 'arte_platform_outbox', 'arte_platform_audit')
  AND table_type = 'BASE TABLE'
  AND engine = 'InnoDB') <> 5 THEN
        SIGNAL SQLSTATE '45000'
SET MESSAGE_TEXT = 'Install/check public ACL, command, outbox and audit prerequisites first';
END IF;

    DROP
TEMPORARY TABLE IF EXISTS arte_doc_reset_generations;
    CREATE
TEMPORARY TABLE arte_doc_reset_generations
        (generation VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY) ENGINE=InnoDB;
    WHILE
generation_index < JSON_LENGTH(@arte_reset_search_generations) DO
        SET generation_json = JSON_EXTRACT(@arte_reset_search_generations, CONCAT('$[', generation_index, ']'));
        IF
JSON_TYPE(generation_json) <> 'STRING' OR CHAR_LENGTH(JSON_UNQUOTE(generation_json)) > 128
           OR JSON_UNQUOTE(generation_json) NOT REGEXP '^[a-z][a-z0-9_-]{0,127}$' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Each old generation must be an exact physical index name';
END IF;
        SET
generation_value = JSON_UNQUOTE(generation_json);
        INSERT
IGNORE INTO arte_doc_reset_generations VALUES (generation_value);
        SET
generation_index = generation_index + 1;
END WHILE;

    -- 第 3 节：唯一废弃表；必须先处理其对 resource/version 的外键。
    -- MySQL DROP TABLE 会隐式提交；即使后续数据事务失败，此表也不会恢复。
DROP TABLE IF EXISTS arte_ai_model_personal_publication;

DROP
TEMPORARY TABLE IF EXISTS arte_doc_reset_acl_operations;
    DROP
TEMPORARY TABLE IF EXISTS arte_doc_reset_commands;
    CREATE
TEMPORARY TABLE arte_doc_reset_acl_operations
        (operation_id VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY) ENGINE=InnoDB;
    CREATE
TEMPORARY TABLE arte_doc_reset_commands LIKE arte_doc_reset_acl_operations;

START TRANSACTION;
-- 第 1.3 节：先取证再删事件，兼顾 resource:void:v1 的无目标回执。
INSERT
IGNORE INTO arte_doc_reset_acl_operations
SELECT JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'), '$.attributes.operationId'))
FROM arte_platform_outbox
WHERE JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'), '$.attributes.resourceType')) = 'model'
  AND JSON_TYPE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'), '$.attributes.operationId')) = 'STRING'
  AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'),
                                            '$.attributes.operationId'))) BETWEEN 1 AND 256;

INSERT
IGNORE INTO arte_doc_reset_commands
SELECT c.operation_id
FROM arte_platform_command c
WHERE c.command_type LIKE 'model:%'
   OR c.command_type LIKE 'workspace:ai:%'
   OR c.result_schema = 'ai:catalog:view:v1'
   OR EXISTS (SELECT 1 FROM arte_doc_reset_acl_operations a WHERE a.operation_id = c.operation_id)
   OR (c.command_type LIKE 'resource:%' AND (
    JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(c.receipt), c.receipt, '{}'), '$.receipt.value.resource.type')) = 'model'
        OR JSON_CONTAINS(JSON_EXTRACT(IF(JSON_VALID(c.receipt), c.receipt, '{}'),
                                      '$.receipt.value.grants[*].resource.type'), JSON_QUOTE('model')) = 1));

-- 第 4 节：记录本次应保持的公共数据数量，不输出正文、回执或密钥。
SELECT COUNT(*)
INTO @arte_reset_before_other_acl
FROM arte_platform_resource_access
WHERE resource_type <> 'model';
SELECT COUNT(*)
INTO @arte_reset_before_other_grants
FROM arte_platform_resource_grant g
         JOIN arte_platform_resource_access a ON a.resource_key = g.resource_key
WHERE a.resource_type <> 'model';
SELECT COUNT(*)
INTO @arte_reset_before_audit
FROM arte_platform_audit;

CALL arte_doc_model_reset_delete('arte_ai_lock', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_acceptance', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_event', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_operation', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_outbox', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_conversation_new', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_turn', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_account', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_reservation', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_settlement', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_conversation_creation', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_context_snapshot', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_result', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_dispatch_permit', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_dispatch_rate', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_conversation_metadata', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_conversation_metadata_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_conversation_message_fact', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_execution_snapshot', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_scheduling_permit', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_dispatch_cursor', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_dispatch_pool', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_queue_capacity', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_approval', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_approval_audience', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_outcome_evidence', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_outcome_scan', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_outcome_review', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_outcome_review_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_run_work', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_run_step', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_run_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_link', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_run_event', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_budget', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_budget_allocation', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_bill', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_attempt', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_outcome_check', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_chat_run', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_invocation', FALSE);

IF
@arte_reset_tools = 1 THEN
        CALL arte_doc_model_reset_delete('arte_ai_tool_binding_resolution', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_binding_revision', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_binding_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_binding', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_release_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_release', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_discovery_command', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_price_guard', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_definition', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_provider', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_tool_schema', FALSE);
END IF;

    -- 第 1.1 节：子记录先于父记录；缺失的本轮新增表在结构阶段补齐。
CALL arte_doc_model_reset_delete('arte_ai_model_use_scope', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_sharing', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_publication', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_validation', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_draft', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_credential', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_version', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_resource', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_definition_source', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_model_acl_scope', FALSE);

-- 第 1.3 节：只删除模型公共授权、已识别命令和对应事件，保留其他资源及全部审计。
DELETE
g FROM arte_platform_resource_grant g JOIN arte_platform_resource_access a
        ON a.resource_key = g.resource_key WHERE a.resource_type = 'model';
DELETE
FROM arte_platform_resource_access
WHERE resource_type = 'model';
DELETE
c FROM arte_platform_command c JOIN arte_doc_reset_commands d ON d.operation_id = c.operation_id;
DELETE
FROM arte_platform_outbox
WHERE event_type LIKE 'model:%'
   OR event_type LIKE 'workspace:ai:%'
   OR event_type = 'ai:catalog:published'
   OR JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(event), event, '{}'), '$.attributes.resourceType')) = 'model';

-- 第 1.1、5 节：目录、空间配置和预算定义与账本一起重置。
CALL arte_doc_model_reset_delete('arte_ai_budget_definition', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_workspace_configuration', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_workspace_budget_rule', FALSE);
CALL arte_doc_model_reset_delete('arte_ai_control_revision', FALSE);
IF
EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE() AND table_name = 'arte_ai_control_head') THEN
        CALL arte_doc_model_reset_delete('arte_ai_control_head', FALSE);
INSERT INTO arte_ai_control_head(id, version)
VALUES (1, 0);
END IF;

    -- 第 1.4 节：只删除明确列出的旧搜索代际；空数组表示不处理任何搜索行。
CALL arte_doc_model_reset_delete('arte_ai_search_entry', TRUE);
CALL arte_doc_model_reset_delete('arte_ai_search_work', TRUE);
CALL arte_doc_model_reset_delete('arte_ai_search_checkpoint', TRUE);
CALL arte_doc_model_reset_delete('arte_ai_search_generation', TRUE);

COMMIT;
SET
@arte_reset_data_done = DATABASE();
    DROP
TEMPORARY TABLE arte_doc_reset_commands;
    DROP
TEMPORARY TABLE arte_doc_reset_acl_operations;
    DROP
TEMPORARY TABLE arte_doc_reset_generations;
SELECT 'DATA_RESET_OK'                AS result,
       DATABASE()                     AS target_database,
       @arte_reset_tools              AS tools_reset,
       @arte_reset_search_generations AS old_search_generations;
END$$
DELIMITER ;
CALL arte_doc_model_reset_data();
-- 只清理本文件安装的临时维护例程，不改变业务表；异常后亦可手动执行这两句。
DROP PROCEDURE arte_doc_model_reset_data;
DROP PROCEDURE arte_doc_model_reset_delete;
