-- 独立新模型调用；不修改旧 AI 表。不自动创建预算／许可记录。
CREATE TABLE IF NOT EXISTS arte_ai_new_budget
(
    scope_key
    CHAR
(
    64
) NOT NULL PRIMARY KEY,
    amount_limit DECIMAL
(
    24,
    8
) NOT NULL,
    reserved_amount DECIMAL
(
    24,
    8
) NOT NULL DEFAULT 0,
    spent_amount DECIMAL
(
    24,
    8
) NOT NULL DEFAULT 0,
    currency VARCHAR
(
    16
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;
CREATE TABLE IF NOT EXISTS arte_ai_new_execution
(
    execution_id
    CHAR
(
    36
) NOT NULL PRIMARY KEY,
    attempt_id CHAR
(
    36
) NOT NULL,
    scope_key CHAR
(
    64
) NOT NULL,
    identity_key CHAR
(
    64
) NOT NULL UNIQUE,
    request_digest CHAR
(
    71
) NOT NULL,
    capability_id VARCHAR
(
    128
) NOT NULL,
    capability_version VARCHAR
(
    64
) NOT NULL,
    binding_id VARCHAR
(
    128
) NOT NULL,
    binding_version VARCHAR
(
    64
) NOT NULL,
    connection_id VARCHAR
(
    128
) NOT NULL,
    connection_version VARCHAR
(
    64
) NOT NULL,
    status VARCHAR
(
    32
) NOT NULL,
    revision BIGINT NOT NULL,
    dispatched BOOLEAN NOT NULL,
    accepted_at TIMESTAMP
(
    6
) NOT NULL,
    reserved_amount DECIMAL
(
    24,
    8
) NOT NULL,
    currency VARCHAR
(
    16
) NOT NULL,
    budget_status VARCHAR
(
    32
) NOT NULL,
    result_json MEDIUMTEXT,
    error_json TEXT,
    INDEX ix_ai_new_scope
(
    scope_key,
    accepted_at
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;
CREATE TABLE IF NOT EXISTS arte_ai_new_event
(
    execution_id
    CHAR
(
    36
) NOT NULL,
    attempt_id CHAR
(
    36
) NOT NULL,
    sequence_no BIGINT NOT NULL,
    status VARCHAR
(
    32
) NOT NULL,
    occurred_at TIMESTAMP
(
    6
) NOT NULL,
    result_json MEDIUMTEXT,
    error_json TEXT,
    PRIMARY KEY
(
    execution_id,
    sequence_no
),
    CONSTRAINT fk_ai_new_event_execution FOREIGN KEY
(
    execution_id
) REFERENCES arte_ai_new_execution
(
    execution_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;
