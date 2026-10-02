-- 最小聊天：先部署 arte-ai-new-model-ddl-mysql.sql，再执行本脚本。
-- 不修改旧 AI 表、不迁移历史、不创建账号、权限或默认会话。
-- 此幂等前置块只为新 execution 表添加作用域复合候选键，供聊天关联的外键使用。
SET
@arte_chat_scope_index_sql = IF(
    EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'arte_ai_new_execution'
          AND index_name = 'uq_ai_new_execution_scope'
    ),
    'SELECT 1',
    'ALTER TABLE arte_ai_new_execution ADD UNIQUE KEY uq_ai_new_execution_scope (execution_id, scope_key)'
);
PREPARE arte_chat_scope_index_statement FROM @arte_chat_scope_index_sql;
EXECUTE arte_chat_scope_index_statement;
DEALLOCATE PREPARE arte_chat_scope_index_statement;

-- CHAT_TABLES_BEGIN：以下表结构也是 H2 约束测试读取的生产定义。
CREATE TABLE IF NOT EXISTS arte_ai_new_conversation
(
    conversation_id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    scope_key CHAR
(
    64
) NOT NULL,
    tenant_id VARCHAR
(
    128
) NOT NULL,
    workspace_id VARCHAR
(
    128
) NOT NULL,
    principal_type VARCHAR
(
    16
) NOT NULL,
    principal_id VARCHAR
(
    128
) NOT NULL,
    title VARCHAR
(
    256
) NOT NULL,
    model_binding_type VARCHAR
(
    32
) NOT NULL,
    model_binding_id VARCHAR
(
    128
) NOT NULL,
    model_binding_version VARCHAR
(
    64
) NOT NULL,
    status VARCHAR
(
    16
) NOT NULL,
    row_version BIGINT NOT NULL,
    resource_refs_json JSON NOT NULL,
    created_at TIMESTAMP
(
    6
) NOT NULL,
    updated_at TIMESTAMP
(
    6
) NOT NULL,
    deleted_at TIMESTAMP
(
    6
),
    CONSTRAINT uq_ai_new_conversation_scope UNIQUE
(
    conversation_id,
    scope_key
),
    CONSTRAINT ck_ai_new_conversation_owner CHECK
(
    principal_type
    IN
(
    'USER',
    'SERVICE'
)),
    CONSTRAINT ck_ai_new_conversation_title CHECK
(
    CHAR_LENGTH (
    TRIM
(
    title
)) > 0),
    CONSTRAINT ck_ai_new_conversation_binding CHECK
(
    model_binding_type =
    'ai-binding'
    AND
    model_binding_version
    <>
    'latest'
),
    CONSTRAINT ck_ai_new_conversation_version CHECK
(
    row_version >
    0
),
    CONSTRAINT ck_ai_new_conversation_times CHECK
(
    updated_at
    >=
    created_at
),
    CONSTRAINT ck_ai_new_conversation_status CHECK
(
(
    status =
    'ACTIVE'
    AND
    deleted_at
    IS
    NULL
)
    OR
(
    status =
    'DELETED'
    AND
    deleted_at
    IS
    NOT
    NULL
    AND
    deleted_at
    >=
    created_at
    AND
    deleted_at
    <=
    updated_at
)
    ),
    INDEX ix_ai_new_conversation_list
(
    scope_key,
    status,
    updated_at,
    conversation_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin COMMENT='新 AI 聊天会话：记录归属作用域、标题、固定模型绑定、会话版本和软删除状态';

CREATE TABLE IF NOT EXISTS arte_ai_new_context_snapshot
(
    snapshot_id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR
(
    64
) NOT NULL,
    scope_key CHAR
(
    64
) NOT NULL,
    conversation_version BIGINT NOT NULL,
    model_binding_type VARCHAR
(
    32
) NOT NULL,
    model_binding_id VARCHAR
(
    128
) NOT NULL,
    model_binding_version VARCHAR
(
    64
) NOT NULL,
    payload_format VARCHAR
(
    32
) NOT NULL,
    messages_json JSON NOT NULL,
    fragments_json JSON NOT NULL,
    history_refs_json JSON NOT NULL,
    input_byte_limit INT NOT NULL,
    used_input_bytes INT NOT NULL,
    output_token_reserve INT NOT NULL,
    content_digest CHAR
(
    71
) NOT NULL,
    created_at TIMESTAMP
(
    6
) NOT NULL,
    expires_at TIMESTAMP
(
    6
) NOT NULL,
    CONSTRAINT uq_ai_new_snapshot_placement UNIQUE
(
    snapshot_id,
    conversation_id,
    scope_key,
    conversation_version
),
    CONSTRAINT fk_ai_new_snapshot_conversation FOREIGN KEY
(
    conversation_id,
    scope_key
)
    REFERENCES arte_ai_new_conversation
(
    conversation_id,
    scope_key
),
    CONSTRAINT ck_ai_new_snapshot_version CHECK
(
    conversation_version >
    0
),
    CONSTRAINT ck_ai_new_snapshot_binding CHECK
(
    model_binding_type =
    'ai-binding'
    AND
    model_binding_version
    <>
    'latest'
),
    CONSTRAINT ck_ai_new_snapshot_format CHECK
(
    payload_format =
    'arte.chat.context.v1'
),
    CONSTRAINT ck_ai_new_snapshot_budget CHECK
(
    input_byte_limit >
    0
    AND
    used_input_bytes
    >=
    0
    AND
    used_input_bytes
    <=
    input_byte_limit
    AND
    output_token_reserve >
    0
),
    CONSTRAINT ck_ai_new_snapshot_expiry CHECK
(
    expires_at >
    created_at
),
    CONSTRAINT ck_ai_new_snapshot_digest CHECK
(
    CHAR_LENGTH
(
    content_digest
) = 71 AND SUBSTRING
(
    content_digest,
    1,
    7
) = 'sha256:'),
    INDEX ix_ai_new_snapshot_expiry
(
    expires_at,
    snapshot_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin COMMENT='新 AI 聊天上下文快照：固定实际消息、来源片段、历史引用、容量预算、内容摘要和有效期';

CREATE TABLE IF NOT EXISTS arte_ai_new_turn
(
    turn_id
    VARCHAR
(
    64
) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR
(
    64
) NOT NULL,
    scope_key CHAR
(
    64
) NOT NULL,
    sequence_no BIGINT NOT NULL,
    conversation_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    kind VARCHAR
(
    16
) NOT NULL,
    status VARCHAR
(
    16
) NOT NULL,
    payload_format VARCHAR
(
    32
) NOT NULL,
    input_json JSON NOT NULL,
    model_options_json JSON NOT NULL,
    regenerates_turn_id VARCHAR
(
    64
),
    context_snapshot_id VARCHAR
(
    64
),
    execution_id CHAR
(
    36
),
    idempotency_operation VARCHAR
(
    64
) NOT NULL,
    idempotency_key VARCHAR
(
    128
) NOT NULL,
    request_digest CHAR
(
    71
) NOT NULL,
    rejection_code VARCHAR
(
    128
),
    rejection_stage VARCHAR
(
    64
),
    rejection_retryable BOOLEAN,
    rejection_side_effect_status VARCHAR
(
    16
),
    rejection_result_certainty VARCHAR
(
    16
),
    rejection_correlation_id VARCHAR
(
    128
),
    created_at TIMESTAMP
(
    6
) NOT NULL,
    updated_at TIMESTAMP
(
    6
) NOT NULL,
    slot_released_at TIMESTAMP
(
    6
),
    -- NULL 不参与唯一冲突；已释放的历史记录不阻塞后续提交。
    active_conversation_id VARCHAR
(
    64
) GENERATED ALWAYS AS
(
    CASE
    WHEN
    slot_released_at
    IS
    NULL
    THEN
    conversation_id
    ELSE
    NULL
    END
) STORED,
    CONSTRAINT uq_ai_new_turn_scope UNIQUE
(
    turn_id,
    conversation_id,
    scope_key
),
    CONSTRAINT uq_ai_new_turn_sequence UNIQUE
(
    conversation_id,
    sequence_no
),
    CONSTRAINT uq_ai_new_turn_idempotency UNIQUE
(
    scope_key,
    idempotency_operation,
    idempotency_key
),
    CONSTRAINT uq_ai_new_turn_execution UNIQUE
(
    execution_id
),
    CONSTRAINT uq_ai_new_turn_active UNIQUE
(
    scope_key,
    active_conversation_id
),
    CONSTRAINT fk_ai_new_turn_conversation FOREIGN KEY
(
    conversation_id,
    scope_key
)
    REFERENCES arte_ai_new_conversation
(
    conversation_id,
    scope_key
),
    CONSTRAINT fk_ai_new_turn_snapshot FOREIGN KEY
(
    context_snapshot_id,
    conversation_id,
    scope_key,
    conversation_version
)
    REFERENCES arte_ai_new_context_snapshot
(
    snapshot_id,
    conversation_id,
    scope_key,
    conversation_version
),
    CONSTRAINT fk_ai_new_turn_execution FOREIGN KEY
(
    execution_id,
    scope_key
)
    REFERENCES arte_ai_new_execution
(
    execution_id,
    scope_key
),
    CONSTRAINT fk_ai_new_turn_regeneration FOREIGN KEY
(
    regenerates_turn_id,
    conversation_id,
    scope_key
)
    REFERENCES arte_ai_new_turn
(
    turn_id,
    conversation_id,
    scope_key
),
    CONSTRAINT ck_ai_new_turn_versions CHECK
(
    sequence_no >
    0
    AND
    conversation_version >
    0
    AND
    row_version >
    0
),
    CONSTRAINT ck_ai_new_turn_format CHECK
(
    payload_format =
    'arte.chat.turn.v1'
),
    CONSTRAINT ck_ai_new_turn_kind CHECK
(
(
    kind =
    'MESSAGE'
    AND
    regenerates_turn_id
    IS
    NULL
    AND
    idempotency_operation =
    'chat.turn.submit'
)
    OR
(
    kind =
    'REGENERATION'
    AND
    regenerates_turn_id
    IS
    NOT
    NULL
    AND
    regenerates_turn_id
    <>
    turn_id
    AND
    idempotency_operation =
    'chat.turn.regenerate'
)
    ),
    CONSTRAINT ck_ai_new_turn_digest CHECK
(
    CHAR_LENGTH
(
    request_digest
) = 71 AND SUBSTRING
(
    request_digest,
    1,
    7
) = 'sha256:'),
    CONSTRAINT ck_ai_new_turn_times CHECK
(
    updated_at
    >=
    created_at
    AND (
    slot_released_at
    IS
    NULL
    OR
(
    slot_released_at
    >=
    created_at
    AND
    slot_released_at
    <=
    updated_at
))),
    CONSTRAINT ck_ai_new_turn_status CHECK
(
(
    status =
    'PREPARING'
    AND
    context_snapshot_id
    IS
    NULL
    AND
    execution_id
    IS
    NULL
    AND
    slot_released_at
    IS
    NULL
)
    OR
(
    status =
    'READY'
    AND
    context_snapshot_id
    IS
    NOT
    NULL
    AND
    execution_id
    IS
    NULL
    AND
    slot_released_at
    IS
    NULL
)
    OR
(
    status =
    'ACCEPTED'
    AND
    context_snapshot_id
    IS
    NOT
    NULL
    AND
    execution_id
    IS
    NOT
    NULL
)
    OR
(
    status =
    'REJECTED'
    AND
    execution_id
    IS
    NULL
    AND
    slot_released_at
    IS
    NOT
    NULL
)
    ),
    CONSTRAINT ck_ai_new_turn_rejection CHECK
(
(
    status =
    'REJECTED'
    AND
    rejection_code
    IS
    NOT
    NULL
    AND
    rejection_stage
    IS
    NOT
    NULL
    AND
    rejection_retryable
    IS
    NOT
    NULL
    AND
    rejection_side_effect_status
    IS
    NOT
    NULL
    AND
    rejection_side_effect_status =
    'NONE'
    AND
    rejection_result_certainty
    IS
    NOT
    NULL
    AND
    rejection_result_certainty =
    'CONFIRMED'
)
    OR
(
    status
    <>
    'REJECTED'
    AND
    rejection_code
    IS
    NULL
    AND
    rejection_stage
    IS
    NULL
    AND
    rejection_retryable
    IS
    NULL
    AND
    rejection_side_effect_status
    IS
    NULL
    AND
    rejection_result_certainty
    IS
    NULL
    AND
    rejection_correlation_id
    IS
    NULL
)
    )
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE =utf8mb4_bin COMMENT='新 AI 聊天轮次提交：记录用户输入、幂等身份、重新生成关联、上下文与执行关联及串行占位';
