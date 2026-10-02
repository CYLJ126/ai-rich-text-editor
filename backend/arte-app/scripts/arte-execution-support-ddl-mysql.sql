-- 新执行支撑的增量表，不改旧审计／文件表，不自动执行。
CREATE TABLE IF NOT EXISTS arte_execution_audit
(
    event_id
    VARCHAR
(
    128
) NOT NULL PRIMARY KEY,
    scope_key CHAR
(
    64
) NOT NULL,
    event_type VARCHAR
(
    128
) NOT NULL,
    payload BLOB NOT NULL,
    content_digest CHAR
(
    71
) NOT NULL,
    recorded_at TIMESTAMP
(
    6
) NOT NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_execution_artifact
(
    artifact_id
    CHAR
(
    36
) NOT NULL PRIMARY KEY,
    scope_key CHAR
(
    64
) NOT NULL,
    owner_ref BLOB NOT NULL,
    media_type VARCHAR
(
    256
) NOT NULL,
    size_bytes BIGINT NOT NULL,
    content_digest CHAR
(
    71
) NOT NULL,
    status VARCHAR
(
    32
) NOT NULL,
    revision BIGINT NOT NULL,
    created_at TIMESTAMP
(
    6
) NOT NULL,
    expires_at TIMESTAMP
(
    6
) NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;
