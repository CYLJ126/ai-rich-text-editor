-- S6-02：已安装 TOOL 受理表的增量升级。部署方停写、备份、统一升级后执行；运行时不执行 DDL。
-- 新库使用完整基线，不再重复运行本文件。先执行结构段，再按需分批重复执行回填段至遗留数为 0。
-- 不改写原快照、Invocation、Attempt、费用或业务结果；已升级节点首次受理在原 INSERT 中填写投影。
ALTER TABLE arte_ai_tool_execution_snapshot
    ADD COLUMN accepted_at bigint NOT NULL DEFAULT 0 COMMENT 'S6-02 受理时刻 UTC Unix 毫秒；0 表示历史查询投影尚未回填',
    ADD COLUMN observation text NULL COMMENT 'S6-02 v1 小型查询元数据，不含参数、配置、授权或输出；历史待回填为 null',
    ADD COLUMN observation_digest char(64) NULL COMMENT '查询元数据原文 SHA-256；读取时重验';
CREATE INDEX idx_tool_observation_time ON arte_ai_tool_execution_snapshot (owner_key, accepted_at, invocation_key);
CREATE INDEX idx_tool_observation_binding ON arte_ai_tool_execution_snapshot (owner_key, binding_key, accepted_at, invocation_key);

-- 回填段：每次最多 500 条。UTC Instant 原文按毫秒截取；保留原时刻，不用升级时刻代替。
-- 历史输入字节未从内部类型包装猜测，明确保存 null。损坏或不支持的原文应先人工核查，不补零或跳过。
UPDATE arte_ai_tool_execution_snapshot AS t
SET observation        = JSON_OBJECT(
        'schemaVersion', 1,
        'invocationId', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.request.context.executionId')),
        'toolId', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.request.input.tool.id')),
        'toolVersion', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.request.input.tool.version')),
        'bindingId', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.input.bindingId')),
        'title', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.binding.tool.tool.title')),
        'providerId', JSON_UNQUOTE(JSON_EXTRACT(snapshot, '$.body.binding.tool.tool.providerId')),
        'billingMode', IF(JSON_EXTRACT(snapshot, '$.body.binding.tool.tool.price') IS NULL
                              OR JSON_TYPE(JSON_EXTRACT(snapshot, '$.body.binding.tool.tool.price')) = 'NULL', 'FREE',
                          'CHARGE'),
        'inputBytes', NULL),
    observation_digest = SHA2(observation, 256),
    accepted_at        = TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', STR_TO_DATE(
            REPLACE(LEFT(CONCAT(SUBSTRING_INDEX((SELECT JSON_UNQUOTE(JSON_EXTRACT(i.workspace_snapshot, '$.body.acceptedAt'))
            FROM arte_ai_invocation i WHERE i.id_key=t.invocation_key), 'Z', 1), '.000'), 23), 'T', ' '),
            '%Y-%m-%d %H:%i:%s.%f')) DIV 1000
WHERE accepted_at = 0
ORDER BY invocation_key LIMIT 500;

-- 只读验收：全部回填后应为 0；未回填时查询响应及页面明确报告 historicalIncomplete。
SELECT COUNT(*) AS remaining_legacy_rows
FROM arte_ai_tool_execution_snapshot
WHERE accepted_at = 0;
