-- 仅用于丢弃 text.countCodePoints@v1 的开发测试数据，重新登记 30 秒契约。
-- 先停止所有连接目标开发库的应用并备份本脚本涉及的记录；不要在正式使用的环境执行。
-- 保留 Schema／提供方、其他工具、聊天、身份／RBAC、预算及审计平台表。
-- 客户端必须遇错停止，不使用 --force；若发生错误，执行 ROLLBACK 后关闭本连接。
-- guard 失败时为空，后续每条 DELETE 均受 guard 控制，即使客户端错误续跑也不删除。
-- 固定键由 CanonicalJson.key("tool", "text.countCodePoints", "v1") 计算。

CREATE
TEMPORARY TABLE tmp_arte_text_v1_bindings (binding_key CHAR(64) PRIMARY KEY);
INSERT INTO tmp_arte_text_v1_bindings
SELECT binding_key
FROM arte_ai_tool_binding
WHERE tool_id = 'text.countCodePoints';

CREATE
TEMPORARY TABLE tmp_arte_text_v1_invocations (invocation_key CHAR(64) PRIMARY KEY);
INSERT INTO tmp_arte_text_v1_invocations
-- 受理与绑定存储的 binding_key 哈希作用域不同，不能直接 JOIN 两列。
SELECT invocation_key
FROM arte_ai_tool_execution_snapshot
WHERE JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.request.input.tool.id')) = 'text.countCodePoints'
  AND JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.request.input.tool.version')) = 'v1';

CREATE
TEMPORARY TABLE tmp_arte_text_v1_messages (message_key CHAR(64) PRIMARY KEY);
INSERT INTO tmp_arte_text_v1_messages
SELECT o.message_key
FROM arte_ai_outbox o
         JOIN tmp_arte_text_v1_invocations i ON i.invocation_key = o.invocation_key;

CREATE
TEMPORARY TABLE tmp_arte_text_v1_guard (ok INT NOT NULL CHECK (ok=1));
START TRANSACTION;
INSERT INTO tmp_arte_text_v1_guard(ok)
SELECT CASE
           WHEN
               (SELECT COUNT(*)
                FROM arte_ai_tool_definition
                WHERE id_key = '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a'
                  AND JSON_UNQUOTE(JSON_EXTRACT(workspace_snapshot, '$.body.tool.defaultPolicy.timeout')) = 'PT5S') = 1
                   AND (SELECT COUNT(*) FROM arte_ai_outbox WHERE kind = 'DISPATCH' AND delivered = 0) = 0
                   AND (SELECT COUNT(*) FROM arte_ai_scheduling_permit) = 0
                   AND (SELECT COUNT(*) FROM arte_ai_dispatch_permit) = 0
                   AND (SELECT COALESCE(SUM(reservations), 0) FROM arte_ai_queue_capacity) = 0
                   AND (SELECT COUNT(*)
                        FROM arte_ai_tool_release
                        WHERE
                            compatible_from_key = '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a') =
                       0
                   AND (SELECT COUNT(*)
                        FROM arte_ai_tool_binding_revision r
                                 JOIN tmp_arte_text_v1_bindings b ON b.binding_key = r.binding_key
                        WHERE r.tool_key <> '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a') = 0
                   AND (SELECT COUNT(*)
                        FROM arte_ai_reservation r
                                 JOIN arte_ai_tool_execution_snapshot s ON s.invocation_key = r.invocation_key
                        WHERE JSON_UNQUOTE(JSON_EXTRACT(s.snapshot, '$.body.request.input.tool.id')) =
                              'text.countCodePoints'
                          AND JSON_UNQUOTE(JSON_EXTRACT(s.snapshot, '$.body.request.input.tool.version')) = 'v1') = 0
                   AND (SELECT COUNT(*)
                        FROM arte_ai_invocation i
                                 JOIN tmp_arte_text_v1_invocations t ON t.invocation_key = i.id_key
                        WHERE COALESCE(JSON_UNQUOTE(JSON_EXTRACT(i.workspace_snapshot, '$.body.state')), '')
                            NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                           OR COALESCE(JSON_UNQUOTE(JSON_EXTRACT(i.workspace_snapshot, '$.body.request.kind')), '') <>
                              'TOOL'
                           OR COALESCE(JSON_TYPE(JSON_EXTRACT(i.workspace_snapshot, '$.body.conversation')), 'NULL') <>
                              'NULL') = 0
                   AND (SELECT COUNT(*)
                        FROM arte_ai_dispatch_pool
                        WHERE pool_key = 'default'
                          AND manifest_digest <> '331756c89f707d42a1142275e1be4d4711b6ef76ef6ac7df4b996ce463cd6569') = 0
               THEN 1
           ELSE 0 END;

SELECT (SELECT COUNT(*) FROM tmp_arte_text_v1_guard)       AS preconditions_met,
       (SELECT COUNT(*) FROM tmp_arte_text_v1_bindings)    AS reset_bindings,
       (SELECT COUNT(*) FROM tmp_arte_text_v1_invocations) AS reset_terminal_tool_calls;

DELETE
FROM arte_ai_result
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_operation
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_event
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_attempt
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_outbox
WHERE message_key IN (SELECT message_key FROM tmp_arte_text_v1_messages)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_acceptance
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_execution_snapshot
WHERE invocation_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_invocation
WHERE id_key IN (SELECT invocation_key FROM tmp_arte_text_v1_invocations)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);

DELETE
FROM arte_ai_tool_binding_command
WHERE binding_key IN (SELECT binding_key FROM tmp_arte_text_v1_bindings)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_binding_resolution
WHERE binding_key IN (SELECT binding_key FROM tmp_arte_text_v1_bindings)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_binding_revision
WHERE binding_key IN (SELECT binding_key FROM tmp_arte_text_v1_bindings)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_binding
WHERE binding_key IN (SELECT binding_key FROM tmp_arte_text_v1_bindings)
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_release_command
WHERE tool_key = '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a'
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_release
WHERE tool_key = '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a'
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_tool_definition
WHERE id_key = '99e862aa5dc5abc7813b7700f8faf220dcb6dcd085a98ee4eb0cb2e4600cb45a'
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
DELETE
FROM arte_ai_dispatch_pool
WHERE pool_key = 'default'
  AND EXISTS (SELECT 1 FROM tmp_arte_text_v1_guard);
COMMIT;

DROP
TEMPORARY TABLE tmp_arte_text_v1_guard;
DROP
TEMPORARY TABLE tmp_arte_text_v1_messages;
DROP
TEMPORARY TABLE tmp_arte_text_v1_invocations;
DROP
TEMPORARY TABLE tmp_arte_text_v1_bindings;
