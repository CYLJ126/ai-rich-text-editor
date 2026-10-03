-- 最小聊天：先部署 arte-ai-new-model-ddl-mysql.sql，再执行本脚本。
-- 不修改旧 AI 表、不迁移历史、不创建账号、权限或默认会话。
-- 显式声明时间默认值，兼容 explicit_defaults_for_timestamp=OFF 且禁止零日期的 MySQL。
-- 不声明 on update；业务代码显式写入时间，创建时间不随其他字段更新而变化。
-- 新建执行表的复合唯一键已在模型 DDL 中声明；以下仅兼容此前已建、尚缺该键的执行表。
-- create table if not exists 不会修改现存表，因此升级兼容仍需条件 alter table。
set @arte_chat_scope_index_sql = if(
    exists (
        select 1
        from information_schema.statistics
        where table_schema = database()
          and table_name = 'arte_ai_new_execution'
          and index_name = 'uq_ai_new_execution_scope'
    ),
    'select 1',
    'alter table arte_ai_new_execution add constraint uq_ai_new_execution_scope unique (execution_id, scope_key)'
);
prepare arte_chat_scope_index_statement from @arte_chat_scope_index_sql;
execute arte_chat_scope_index_statement;
deallocate prepare arte_chat_scope_index_statement;

-- CHAT_TABLES_BEGIN：以下表结构也是 H2 约束测试读取的生产定义。
create table if not exists arte_ai_new_conversation
(
    conversation_id       varchar(64)  not null comment '会话业务 ID',
    scope_key             char(64)     not null comment '租户、工作空间及主体的作用域规范摘要',
    tenant_id             varchar(128) not null comment '租户 ID',
    workspace_id          varchar(128) not null comment '工作空间 ID',
    principal_type        varchar(16)  not null comment '归属主体类型：USER 或 SERVICE',
    principal_id          varchar(128) not null comment '归属主体 ID',
    title                 varchar(256) not null comment '会话标题',
    model_binding_type    varchar(32)  not null comment '模型绑定引用类型，固定为 ai-binding',
    model_binding_id      varchar(128) not null comment '模型绑定业务 ID',
    model_binding_version varchar(64)  not null comment '固定模型绑定版本，不使用 latest',
    status                varchar(16)  not null comment '会话状态：ACTIVE 或 DELETED',
    row_version           bigint       not null comment '乐观锁版本，从 1 开始',
    resource_refs_json    json         not null comment '关联资源引用列表 JSON，关联不授予访问权限',
    created_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '创建时间',
    updated_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '更新时间，由应用显式更新',
    deleted_at timestamp
(
    6
) null default null comment '软删除时间，活跃会话为空',
    primary key (conversation_id),
    constraint uq_ai_new_conversation_scope
    unique (conversation_id, scope_key),
    constraint ck_ai_new_conversation_owner
    check (principal_type in ('USER', 'SERVICE')),
    constraint ck_ai_new_conversation_title
    check (char_length(trim(title)) > 0),
    constraint ck_ai_new_conversation_binding
    check (model_binding_type = 'ai-binding' and model_binding_version <> 'latest'),
    constraint ck_ai_new_conversation_version
    check (row_version > 0),
    constraint ck_ai_new_conversation_times
    check (updated_at >= created_at),
    constraint ck_ai_new_conversation_status
    check (
(status = 'ACTIVE' and deleted_at is null)
    or (status = 'DELETED' and deleted_at is not null
        and deleted_at >= created_at and deleted_at <= updated_at)
    ),
    index ix_ai_new_conversation_list (scope_key, status, updated_at, conversation_id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 聊天会话表：归属、模型绑定、会话版本及软删除';

create table if not exists arte_ai_new_context_snapshot
(
    snapshot_id           varchar(64)  not null comment '上下文快照业务 ID',
    conversation_id       varchar(64)  not null comment '会话业务 ID',
    scope_key             char(64)     not null comment '租户、工作空间及主体的作用域规范摘要',
    conversation_version  bigint       not null comment '准备上下文所依据的会话版本',
    model_binding_type    varchar(32)  not null comment '模型绑定引用类型，固定为 ai-binding',
    model_binding_id      varchar(128) not null comment '模型绑定业务 ID',
    model_binding_version varchar(64)  not null comment '固定模型绑定版本，不使用 latest',
    payload_format        varchar(32)  not null comment '持久化格式版本：arte.chat.context.v1 或 v2',
    messages_json         json         not null comment '实际组装的模型输入消息列表 JSON',
    fragments_json        json         not null comment '实际来源片段、引用标识及裁剪说明 JSON',
    history_refs_json     json         not null comment '选入的历史提交版本及执行引用列表 JSON',
    input_byte_limit      int          not null comment '消息文本 UTF-8 字节上限',
    used_input_bytes      int          not null comment '实际使用的消息文本 UTF-8 字节数',
    output_token_reserve  int          not null comment '为模型输出预留的 token 数',
    content_digest        char(71)     not null comment '规范上下文 SHA-256 摘要，含 sha256: 前缀',
    created_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '创建时间',
    expires_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '逻辑过期时间，由应用显式写入，不自动删除数据',
    primary key (snapshot_id),
    constraint uq_ai_new_snapshot_placement
    unique (snapshot_id, conversation_id, scope_key, conversation_version),
    constraint fk_ai_new_snapshot_conversation
    foreign key (conversation_id, scope_key)
    references arte_ai_new_conversation (conversation_id, scope_key),
    constraint ck_ai_new_snapshot_version
    check (conversation_version > 0),
    constraint ck_ai_new_snapshot_binding
    check (model_binding_type = 'ai-binding' and model_binding_version <> 'latest'),
    constraint ck_ai_new_snapshot_format
    check (payload_format in ('arte.chat.context.v1','arte.chat.context.v2')),
    constraint ck_ai_new_snapshot_budget
    check (
              input_byte_limit > 0 and used_input_bytes >= 0
              and used_input_bytes <= input_byte_limit and output_token_reserve > 0
          ),
    constraint ck_ai_new_snapshot_expiry
    check (expires_at > created_at),
    constraint ck_ai_new_snapshot_digest
    check (char_length(content_digest) = 71 and substring(content_digest, 1, 7) = 'sha256:'),
    index ix_ai_new_snapshot_expiry (expires_at, snapshot_id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 聊天上下文快照表：实际输入、来源、历史选择及容量事实';

create table if not exists arte_ai_new_turn
(
    turn_id                      varchar(64)  not null comment '聊天提交业务 ID',
    conversation_id              varchar(64)  not null comment '会话业务 ID',
    scope_key                    char(64)     not null comment '租户、工作空间及主体的作用域规范摘要',
    sequence_no                  bigint       not null comment '会话内提交顺序，从 1 开始，重新生成也独立计序',
    conversation_version         bigint       not null comment '准备上下文所依据的会话版本',
    row_version                  bigint       not null comment '乐观锁版本，从 1 开始',
    kind                         varchar(16)  not null comment '提交种类：MESSAGE 或 REGENERATION',
    status                       varchar(16)  not null comment '提交状态：PREPARING、READY、ACCEPTED 或 REJECTED',
    payload_format               varchar(32)  not null comment '持久化格式版本，固定为 arte.chat.turn.v1',
    input_json                   json         not null comment '本次用户文本消息列表 JSON，不包含服务端历史',
    model_options_json           json         not null comment '类型化模型生成参数 JSON',
    regenerates_turn_id          varchar(64)  null     comment '重新生成所关联的原提交 ID，普通提交为空',
    context_snapshot_id          varchar(64)  null     comment '已准备的上下文快照 ID',
    execution_id                 char(36)     null     comment '已可靠关联的模型执行 UUID，受理前为空',
    idempotency_operation        varchar(64)  not null comment '幂等操作：chat.turn.submit 或 chat.turn.regenerate',
    idempotency_key              varchar(128) not null comment '调用方幂等键，与作用域及操作共同去重',
    request_digest               char(71)     not null comment '规范请求 SHA-256 摘要，含 sha256: 前缀',
    rejection_code               varchar(128) null     comment '已确认未受理的稳定错误代码',
    rejection_stage              varchar(64)  null     comment '受理前失败阶段',
    rejection_retryable          boolean      null     comment '受理前失败是否可能允许重试',
    rejection_side_effect_status varchar(16)  null     comment '拒绝提交的副作用状态，必须为 NONE',
    rejection_result_certainty   varchar(16)  null     comment '拒绝提交的结果确定性，必须为 CONFIRMED',
    rejection_correlation_id     varchar(128) null     comment '受理前错误关联 ID',
    created_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '创建时间',
    updated_at timestamp
(
    6
) default current_timestamp
(
    6
) not null comment '更新时间，由应用显式更新',
    slot_released_at timestamp
(
    6
) null default null comment '会话串行提交占位的释放时间，未释放时为空',
    active_conversation_id       varchar(64)  generated always as (
                                                                      case when slot_released_at is null then conversation_id else null end
                                                                  ) stored comment '未释放时为会话 ID，释放后为空，用于约束单个活跃提交',
    primary key (turn_id),
    constraint uq_ai_new_turn_scope
    unique (turn_id, conversation_id, scope_key),
    constraint uq_ai_new_turn_sequence
    unique (conversation_id, sequence_no),
    constraint uq_ai_new_turn_idempotency
    unique (scope_key, idempotency_operation, idempotency_key),
    constraint uq_ai_new_turn_execution
    unique (execution_id),
    constraint uq_ai_new_turn_active
    unique (scope_key, active_conversation_id),
    constraint fk_ai_new_turn_conversation
    foreign key (conversation_id, scope_key)
    references arte_ai_new_conversation (conversation_id, scope_key),
    constraint fk_ai_new_turn_snapshot
    foreign key (context_snapshot_id, conversation_id, scope_key, conversation_version)
    references arte_ai_new_context_snapshot (snapshot_id, conversation_id, scope_key, conversation_version),
    constraint fk_ai_new_turn_execution
    foreign key (execution_id, scope_key)
    references arte_ai_new_execution (execution_id, scope_key),
    constraint fk_ai_new_turn_regeneration
    foreign key (regenerates_turn_id, conversation_id, scope_key)
    references arte_ai_new_turn (turn_id, conversation_id, scope_key),
    constraint ck_ai_new_turn_versions
    check (sequence_no > 0 and conversation_version > 0 and row_version > 0),
    constraint ck_ai_new_turn_format
    check (payload_format = 'arte.chat.turn.v1'),
    constraint ck_ai_new_turn_kind
    check (
(kind = 'MESSAGE' and regenerates_turn_id is null
 and idempotency_operation = 'chat.turn.submit')
    or (kind = 'REGENERATION' and regenerates_turn_id is not null
        and regenerates_turn_id <> turn_id and idempotency_operation = 'chat.turn.regenerate')
    ),
    constraint ck_ai_new_turn_digest
    check (char_length(request_digest) = 71 and substring(request_digest, 1, 7) = 'sha256:'),
    constraint ck_ai_new_turn_times
    check (
              updated_at >= created_at
              and (slot_released_at is null or (slot_released_at >= created_at and slot_released_at <= updated_at))
    ),
    constraint ck_ai_new_turn_status
    check (
(status = 'PREPARING' and context_snapshot_id is null and execution_id is null and slot_released_at is null)
    or (status = 'READY' and context_snapshot_id is not null and execution_id is null and slot_released_at is null)
    or (status = 'ACCEPTED' and context_snapshot_id is not null and execution_id is not null)
    or (status = 'REJECTED' and execution_id is null and slot_released_at is not null)
    ),
    constraint ck_ai_new_turn_rejection
    check (
(status = 'REJECTED' and rejection_code is not null and rejection_stage is not null
 and rejection_retryable is not null
 and rejection_side_effect_status is not null and rejection_side_effect_status = 'NONE'
 and rejection_result_certainty is not null and rejection_result_certainty = 'CONFIRMED')
    or (status <> 'REJECTED' and rejection_code is null and rejection_stage is null
        and rejection_retryable is null and rejection_side_effect_status is null
        and rejection_result_certainty is null and rejection_correlation_id is null)
    )
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 聊天轮次提交表：输入、幂等、重新生成、执行关联及串行占位';

-- v2 上下文 Token 计量侧表；保留原有 v1 快照，不改写已提交上下文。
create table if not exists arte_ai_new_context_token_budget
(
    snapshot_id            char(36)     not null comment '关联的 v2 上下文快照 ID，使用 UUID，与快照一对一',
    context_window_tokens  int          not null comment '应用侧上下文 Token 总容量，包含输入、输出预留及安全余量',
    input_token_limit      int          not null comment '输入 Token 上限，等于总容量减去输出预留及安全余量',
    estimated_input_tokens int          not null comment '实际选入消息的输入 Token 保守估算值，不是供应商计费用量',
    safety_token_reserve   int          not null comment '为分词及协议开销额外保留的安全 Token 数',
    estimator_version      varchar(128) not null comment 'Token 估算策略版本，与计量事实共同参与快照摘要校验',
    primary key (snapshot_id),
    constraint fk_ai_new_token_snapshot
        foreign key (snapshot_id)
        references arte_ai_new_context_snapshot (snapshot_id)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 上下文 Token 预算表：v2 快照的容量、输入估算及安全预留事实';
