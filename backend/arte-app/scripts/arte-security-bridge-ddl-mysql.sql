-- 新安全接入层的增量表；不修改旧表，不自动执行，不赋予默认 AI／外发权限。
CREATE TABLE IF NOT EXISTS arte_security_member
(
    tenant_id
    VARCHAR
(
    64
) NOT NULL,
    workspace_id VARCHAR
(
    64
) NOT NULL,
    user_id INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    tenant_id,
    workspace_id,
    user_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_resource
(
    resource_type
    VARCHAR
(
    32
) NOT NULL,
    resource_id VARCHAR
(
    64
) NOT NULL,
    tenant_id VARCHAR
(
    64
) NOT NULL,
    workspace_id VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    resource_type,
    resource_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_resource_grant
(
    resource_type
    VARCHAR
(
    32
) NOT NULL,
    resource_id VARCHAR
(
    64
) NOT NULL,
    user_id INT NOT NULL,
    action_code VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    resource_type,
    resource_id,
    user_id,
    action_code
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 应用与绑定分别启用；每项动作都须同时满足二者。当前暂不依赖新 AI 的实体表。
CREATE TABLE IF NOT EXISTS arte_security_application_policy
(
    tenant_id
    VARCHAR
(
    64
) NOT NULL,
    workspace_id VARCHAR
(
    64
) NOT NULL,
    application_id VARCHAR
(
    64
) NOT NULL,
    binding_id VARCHAR
(
    64
) NOT NULL,
    action_code VARCHAR
(
    64
) NOT NULL,
    application_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    binding_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    tenant_id,
    workspace_id,
    application_id,
    binding_id,
    action_code
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_task
(
    context_key
    CHAR
(
    64
) NOT NULL PRIMARY KEY,
    application_id VARCHAR
(
    64
) NOT NULL,
    binding_id VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    valid_until TIMESTAMP
(
    6
) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_task_action
(
    context_key
    CHAR
(
    64
) NOT NULL,
    executor_type VARCHAR
(
    16
) NOT NULL,
    executor_id VARCHAR
(
    64
) NOT NULL,
    action_code VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    context_key,
    executor_type,
    executor_id,
    action_code
),
    FOREIGN KEY
(
    context_key
) REFERENCES arte_security_task
(
    context_key
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_service
(
    id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_task_resource_action
(
    context_key
    CHAR
(
    64
) NOT NULL,
    resource_key CHAR
(
    64
) NOT NULL,
    action_code VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    context_key,
    resource_key,
    action_code
),
    FOREIGN KEY
(
    context_key
) REFERENCES arte_security_task
(
    context_key
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_connection
(
    tenant_id
    VARCHAR
(
    64
) NOT NULL,
    workspace_id VARCHAR
(
    64
) NOT NULL,
    connection_key CHAR
(
    64
) NOT NULL,
    origin VARCHAR
(
    512
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    tenant_id,
    workspace_id,
    connection_key
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

CREATE TABLE IF NOT EXISTS arte_security_consent
(
    id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    request_digest CHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    valid_until TIMESTAMP
(
    6
) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;

-- 用途和连接独立限定；允许一次推理不自动开放训练、留存或其他用途。
CREATE TABLE IF NOT EXISTS arte_security_egress_rule
(
    tenant_id
    VARCHAR
(
    64
) NOT NULL,
    workspace_id VARCHAR
(
    64
) NOT NULL,
    application_id VARCHAR
(
    64
) NOT NULL,
    binding_id VARCHAR
(
    64
) NOT NULL,
    connection_key CHAR
(
    64
) NOT NULL,
    purpose VARCHAR
(
    64
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY
(
    tenant_id,
    workspace_id,
    application_id,
    binding_id,
    connection_key,
    purpose
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin;
