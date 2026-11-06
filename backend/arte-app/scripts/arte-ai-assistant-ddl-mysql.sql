-- AS1. Install after platform workspace and resource ACL tables.
CREATE TABLE arte_ai_assistant_resource
(
    assistant_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY,
    tenant_id               VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    workspace_id            VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    chat_profile_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    state                   INT                                                    NOT NULL COMMENT '0=DRAFT, 1=ACTIVE, 2=DISABLED',
    current_release_version BIGINT NULL,
    version                 BIGINT                                                 NOT NULL,
    created_by              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at              DATETIME(6) NOT NULL,
    updated_by              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    updated_at              DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ai_assistant_profile (chat_profile_id),
    KEY                     ix_ai_assistant_scope (tenant_id, workspace_id, assistant_id),
    KEY                     ix_ai_assistant_state (tenant_id, workspace_id, state, assistant_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (state BETWEEN 0 AND 2),
    CHECK (version >= 0 AND updated_at >= created_at),
    CHECK (current_release_version IS NULL OR current_release_version > 0),
    CHECK (state <> 1 OR current_release_version IS NOT NULL),
    CHECK (state <> 0 OR current_release_version IS NULL)
) ENGINE=InnoDB;

CREATE TABLE arte_ai_assistant_draft
(
    assistant_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY,
    draft_revision     BIGINT                                                 NOT NULL,
    display_name       VARCHAR(80)                                            NOT NULL,
    description        VARCHAR(500)                                           NOT NULL,
    system_instruction TEXT                                                   NOT NULL,
    expected_output    VARCHAR(1000)                                          NOT NULL,
    example_prompts    JSON                                                   NOT NULL,
    updated_by         VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    updated_at         DATETIME(6) NOT NULL,
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    CHECK (draft_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE arte_ai_assistant_trial
(
    trial_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY,
    assistant_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    draft_revision      BIGINT                                                 NOT NULL,
    actor_id            VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    idempotency_digest  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    request_digest      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    content_digest      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    context_snapshot_id VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    invocation_id       VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ai_assistant_trial_owner (assistant_id, trial_id),
    UNIQUE KEY uk_ai_assistant_trial_request (idempotency_digest),
    UNIQUE KEY uk_ai_assistant_trial_invocation (invocation_id),
    KEY                 ix_ai_assistant_trial_revision (assistant_id, draft_revision, created_at, trial_id),
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    CHECK (draft_revision > 0)
) ENGINE=InnoDB;

CREATE TABLE arte_ai_assistant_release
(
    assistant_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    release_version       BIGINT                                                 NOT NULL,
    source_draft_revision BIGINT                                                 NOT NULL,
    trial_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    schema_version        INT                                                    NOT NULL,
    content_digest        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL,
    published_by          VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    published_at          DATETIME(6) NOT NULL,
    display_name          VARCHAR(80)                                            NOT NULL,
    description           VARCHAR(500)                                           NOT NULL,
    system_instruction    TEXT                                                   NOT NULL,
    expected_output       VARCHAR(1000)                                          NOT NULL,
    example_prompts       JSON                                                   NOT NULL,
    PRIMARY KEY (assistant_id, release_version),
    UNIQUE KEY uk_ai_assistant_release_draft (assistant_id, source_draft_revision),
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    FOREIGN KEY (assistant_id, trial_id) REFERENCES arte_ai_assistant_trial (assistant_id, trial_id),
    CHECK (release_version > 0 AND source_draft_revision > 0 AND schema_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
