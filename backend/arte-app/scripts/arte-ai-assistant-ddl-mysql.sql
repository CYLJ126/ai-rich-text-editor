-- AS1. Install after platform workspace and resource ACL tables.
CREATE TABLE arte_ai_assistant_resource
(
    assistant_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY COMMENT '助手唯一 ID，也是公共资源授权使用的资源 ID',
    tenant_id               VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属租户 ID',
    workspace_id            VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '所属工作空间 ID，与租户 ID 共同定位工作空间',
    chat_profile_id         CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '稳定的聊天配置 ID，与发布版本共同构成会话绑定的精确引用',
    state                   INT                                                    NOT NULL COMMENT '生命周期状态，0 为草稿（DRAFT），1 为已发布（ACTIVE），2 为已停用（DISABLED）',
    current_release_version BIGINT NULL COMMENT '当前发布版本号，首次发布前为空，停用后保留原值',
    version                 BIGINT                                                 NOT NULL COMMENT '助手资源的乐观锁版本号，用于并发更新校验',
    created_by              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '创建助手的稳定主体 ID',
    created_at              DATETIME(6) NOT NULL COMMENT '创建时间，UTC，微秒精度',
    updated_by              VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最近一次更新助手资源的稳定主体 ID',
    updated_at              DATETIME(6) NOT NULL COMMENT '最近一次更新助手资源的时间，UTC，微秒精度',
    UNIQUE KEY uk_ai_assistant_profile (chat_profile_id),
    KEY                     ix_ai_assistant_scope (tenant_id, workspace_id, assistant_id),
    KEY                     ix_ai_assistant_state (tenant_id, workspace_id, state, assistant_id),
    FOREIGN KEY (tenant_id, workspace_id) REFERENCES arte_platform_workspace (tenant_id, id),
    CHECK (state BETWEEN 0 AND 2),
    CHECK (version >= 0 AND updated_at >= created_at),
    CHECK (current_release_version IS NULL OR current_release_version > 0),
    CHECK (state <> 1 OR current_release_version IS NOT NULL),
    CHECK (state <> 0 OR current_release_version IS NULL)
) ENGINE=InnoDB COMMENT='AI 助手资源，记录工作空间归属、生命周期和当前发布版本';

CREATE TABLE arte_ai_assistant_draft
(
    assistant_id       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY COMMENT '所属助手 ID，同一助手只保留一份当前草稿',
    draft_revision     BIGINT                                                 NOT NULL COMMENT '当前草稿修订号，从 1 开始递增，用于并发编辑与试用版本校验',
    display_name       VARCHAR(80)                                            NOT NULL COMMENT '草稿中的助手名称，可暂留空字符串',
    description        VARCHAR(500)                                           NOT NULL COMMENT '草稿中的助手用途说明，可暂留空字符串',
    system_instruction TEXT                                                   NOT NULL COMMENT '草稿中的工作指令，试用时由服务端组装为系统消息',
    expected_output    VARCHAR(1000)                                          NOT NULL COMMENT '草稿中的预期输出约定，试用时与工作指令共同组成系统消息',
    example_prompts    JSON                                                   NOT NULL COMMENT '按显示顺序保存的纯文本示例问题 JSON 数组，草稿可为空数组',
    updated_by         VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '最近一次保存草稿的稳定主体 ID',
    updated_at         DATETIME(6) NOT NULL COMMENT '最近一次保存草稿的时间，UTC，微秒精度',
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    CHECK (draft_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手当前工作草稿，每个助手保留一份可编辑草稿';

CREATE TABLE arte_ai_assistant_trial
(
    trial_id            CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL PRIMARY KEY COMMENT '试用记录唯一 ID',
    assistant_id        CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '试用的助手 ID',
    draft_revision      BIGINT                                                 NOT NULL COMMENT '试用时锁定的草稿修订号',
    actor_id            VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '发起试用并持有本次执行的稳定主体 ID',
    idempotency_digest  CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '由试用操作、租户、空间、主体及原幂等键生成的摘要，用于定位原试用',
    request_digest      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '试用请求及预算引用的摘要，用于校验同一幂等键下的请求是否一致',
    content_digest      CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '试用时固定的助手草稿内容摘要，用于发布时核验草稿一致性',
    context_snapshot_id VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '试用的上下文快照 ID，引用冻结的执行输入',
    invocation_id       VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '为试用预先分配的执行 ID，受理后用于查询执行状态、结果和费用',
    created_at          DATETIME(6) NOT NULL COMMENT '试用上下文快照的创建时间，UTC，微秒精度',
    UNIQUE KEY uk_ai_assistant_trial_owner (assistant_id, trial_id),
    UNIQUE KEY uk_ai_assistant_trial_request (idempotency_digest),
    UNIQUE KEY uk_ai_assistant_trial_invocation (invocation_id),
    KEY                 ix_ai_assistant_trial_revision (assistant_id, draft_revision, created_at, trial_id),
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    CHECK (draft_revision > 0)
) ENGINE=InnoDB COMMENT='AI 助手草稿试用记录，固定试用修订和执行引用，供原键恢复及发布校验';

CREATE TABLE arte_ai_assistant_release
(
    assistant_id          CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '所属助手 ID，与发布版本号共同标识一条发布记录',
    release_version       BIGINT                                                 NOT NULL COMMENT '助手发布版本号，在同一助手内从 1 开始递增',
    source_draft_revision BIGINT                                                 NOT NULL COMMENT '本次发布采用的草稿修订号，同一修订最多发布一次',
    trial_id              CHAR(36) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '通过发布校验的同助手成功试用记录 ID',
    schema_version        INT                                                    NOT NULL COMMENT '发布内容的结构版本号，用于解析兼容性',
    content_digest        CHAR(64) CHARACTER SET ascii COLLATE ascii_bin         NOT NULL COMMENT '发布时固定的助手内容摘要，用于核验发布配置',
    published_by          VARCHAR(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '执行发布操作的稳定主体 ID',
    published_at          DATETIME(6) NOT NULL COMMENT '发布时间，UTC，微秒精度',
    display_name          VARCHAR(80)                                            NOT NULL COMMENT '发布时固定的助手名称，用于可用目录与会话摘要',
    description           VARCHAR(500)                                           NOT NULL COMMENT '发布时固定的助手用途说明，用于可用目录与会话摘要',
    system_instruction    TEXT                                                   NOT NULL COMMENT '发布时固定的工作指令，由服务端组装为会话的系统消息',
    expected_output       VARCHAR(1000)                                          NOT NULL COMMENT '发布时固定的预期输出约定，与工作指令共同组成系统消息',
    example_prompts       JSON                                                   NOT NULL COMMENT '发布时按显示顺序固定的纯文本示例问题 JSON 数组',
    PRIMARY KEY (assistant_id, release_version),
    UNIQUE KEY uk_ai_assistant_release_draft (assistant_id, source_draft_revision),
    FOREIGN KEY (assistant_id) REFERENCES arte_ai_assistant_resource (assistant_id),
    FOREIGN KEY (assistant_id, trial_id) REFERENCES arte_ai_assistant_trial (assistant_id, trial_id),
    CHECK (release_version > 0 AND source_draft_revision > 0 AND schema_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 助手不可变发布版本，保存试用证据及会话使用的固定配置';
