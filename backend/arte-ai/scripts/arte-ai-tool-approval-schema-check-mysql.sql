-- 只读检查：先确认当前库；发现 LEGACY_NAME_COLLISION_STOP 时不要执行新审批的回填。
-- S3-01 列存在不等于全部部署验收通过，仍需核对下列类型、索引和节点版本。
SELECT DATABASE() AS current_database,
       CASE
           WHEN (SELECT COUNT(*)
                 FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'arte_ai_tool_approval'
                   AND COLUMN_NAME IN ('invocation_key', 'owner_key', 'approval_key', 'state',
                                       'version_no', 'expires_at', 'payload_digest', 'snapshot')) = 8
               THEN 'S3_01_COLUMNS_PRESENT'
           WHEN EXISTS (SELECT 1
                        FROM information_schema.COLUMNS
                        WHERE TABLE_SCHEMA = DATABASE()
                          AND TABLE_NAME = 'arte_ai_tool_approval'
                          AND COLUMN_NAME = 'request_id')
               THEN 'LEGACY_NAME_COLLISION_STOP'
           ELSE 'MISSING_OR_INCOMPATIBLE_APPROVAL_TABLE_STOP'
           END    AS approval_schema_status;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('arte_ai_tool_approval', 'arte_ai_tool_approval_audience')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 重命名旧表前核对依赖；查询结果为空也不能证明没有其他旧应用访问它。
SELECT TABLE_NAME, COLUMN_NAME, CONSTRAINT_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE REFERENCED_TABLE_SCHEMA = DATABASE()
  AND REFERENCED_TABLE_NAME = 'arte_ai_tool_approval';
