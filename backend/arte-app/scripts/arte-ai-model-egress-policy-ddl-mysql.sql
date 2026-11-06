-- Apply to the app database. The initial policy denies all model origins.
CREATE TABLE IF NOT EXISTS arte_ai_model_egress_policy
(
    id
    int
    NOT
    NULL
    COMMENT
    '平台策略固定标识 1',
    version
    bigint
    NOT
    NULL
    COMMENT
    '出口策略版本，保存时递增',
    payload
    longtext
    NOT
    NULL
    COMMENT
    '规范化的精确 origin JSON 数组',
    PRIMARY
    KEY
(
    id
),
    CHECK
(
    id =
    1
    AND
    version
    >=
    0
)
    ) ENGINE=InnoDB COMMENT='平台模型 HTTP 出口策略';

INSERT INTO arte_ai_model_egress_policy (id, version, payload)
SELECT 1, 0, '[]' WHERE NOT EXISTS (SELECT 1 FROM arte_ai_model_egress_policy WHERE id = 1);
