-- S3-02 已有 S3-01 记录的参与者索引回填，仅供 MySQL 8.0+。
-- 前置执行 arte-ai-tool-approval-schema-check-mysql.sql，必须确认 S3_01_COLUMNS_PRESENT。
-- 若现表为 request_id/call_id/status 等旧结构，同名但不是 S3-01 表；停止本脚本。
-- 本脚本不能迁移旧审批，也不能将 status 改名 state 或补几个空列后强行回填。
-- 停写、备份；先执行 audience-migration。原审批／快照不变，不授予权限。
-- SHA 与 Java CanonicalJson.key 的 UTF-8 四字节大端长度前缀保持一致。
INSERT INTO arte_ai_tool_approval_audience(actor_key, invocation_key, pending)
SELECT a.owner_key, a.invocation_key, CASE WHEN a.state = 'PENDING' THEN 1 ELSE 0 END
FROM arte_ai_tool_approval a
WHERE NOT EXISTS (SELECT 1
                  FROM arte_ai_tool_approval_audience p
                  WHERE p.actor_key = a.owner_key
                    AND p.invocation_key = a.invocation_key);

INSERT INTO arte_ai_tool_approval_audience(actor_key, invocation_key, pending)
SELECT actor_key, invocation_key, pending
FROM (SELECT DISTINCT SHA2(CONCAT(
                                   UNHEX(LPAD(HEX(OCTET_LENGTH(CONVERT(
                                           JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, '$.body.owner.tenantId')) USING
                                           utf8mb4))), 8, '0')),
                                   CONVERT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, '$.body.owner.tenantId')) USING
                                           utf8mb4),
                                   UNHEX(LPAD(HEX(OCTET_LENGTH(CONVERT(
                                           JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, '$.body.owner.workspaceId')) USING
                                           utf8mb4))), 8, '0')),
                                   CONVERT(JSON_UNQUOTE(JSON_EXTRACT(a.snapshot, '$.body.owner.workspaceId')) USING
                                           utf8mb4),
                                   UNHEX(LPAD(HEX(OCTET_LENGTH(CONVERT(p.subject_id USING utf8mb4))), 8, '0')),
                                   CONVERT(p.subject_id USING utf8mb4)
                           ), 256)                                    AS actor_key,
                      a.invocation_key,
                      CASE WHEN a.state = 'PENDING' THEN 1 ELSE 0 END AS pending
      FROM arte_ai_tool_approval a
               JOIN JSON_TABLE(a.snapshot, '$.body.plan.approverSubjectIds[*]'
          COLUMNS(subject_id VARCHAR(256) PATH '$')) p ON TRUE) candidates
WHERE NOT EXISTS (SELECT 1
                  FROM arte_ai_tool_approval_audience existing
                  WHERE existing.actor_key = candidates.actor_key
                    AND existing.invocation_key = candidates.invocation_key);
