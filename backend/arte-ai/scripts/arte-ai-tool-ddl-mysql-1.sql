-- 临时文件：AI 工具一期要实现的功能对应的数据表（完整功能上线后，合并一、二两个表）
create table arte_ai_tool_provider
(
    id                   bigint auto_increment comment '主键'
        primary key,
    provider_id          varchar(64)                   not null comment '提供者业务 ID',
    name                 varchar(100)                  not null comment '提供者名称',
    provider_type        varchar(32)                   not null comment '类型：LOCAL/MCP/HTTP/OPENAPI',
    endpoint             varchar(500) null comment '远程端点，不包含凭据',
    config               json null comment '非敏感提供者配置',
    credential_reference varchar(128) null comment '凭据引用',
    status               varchar(32) default 'enabled' not null comment '状态',
    last_sync_time       datetime(3) null comment '最后同步时间',
    last_error           text null comment '最后同步错误',
    create_by            varchar(64) null comment '创建人',
    update_by            varchar(64) null comment '更新人',
    create_time          datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time          datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_provider_id
        unique (provider_id)
) comment 'AI 工具提供者表';

create index idx_tool_provider_type_status
    on arte_ai_tool_provider (provider_type, status);

create table arte_ai_tool
(
    id              bigint auto_increment comment '主键'
        primary key,
    tool_id         varchar(64)                 not null comment '工具业务 ID',
    namespace       varchar(100)                not null comment '工具命名空间',
    name            varchar(100)                not null comment '工具名称',
    provider_id     varchar(64)                 not null comment '提供者业务 ID',
    title           varchar(200)                not null comment '展示名称',
    description     varchar(1000) null comment '工具描述',
    latest_version  varchar(64) null comment '最新发布版本',
    lifecycle_state varchar(32) default 'draft' not null comment '生命周期状态',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_id
        unique (tool_id),
    constraint uk_tool_identity
        unique (namespace, name)
) comment 'AI 工具目录表';

create index idx_tool_provider_state
    on arte_ai_tool (provider_id, lifecycle_state);

create table arte_ai_tool_version
(
    id              bigint auto_increment comment '主键'
        primary key,
    tool_id         varchar(64)                 not null comment '工具业务 ID',
    version         varchar(64)                 not null comment '工具版本',
    title           varchar(200)                not null comment '展示名称',
    description     varchar(1000) null comment '工具描述',
    input_schema    json                        not null comment '输入 JSON Schema',
    output_schema   json                        not null comment '输出 JSON Schema',
    capabilities    json                        not null comment '工具能力快照',
    risk_profile    json                        not null comment '风险画像快照',
    default_configuration json null comment '工具默认运行配置',
    default_policy  json                        not null comment '默认执行策略',
    tags            json null comment '标签',
    checksum        varchar(128)                not null comment '定义校验和',
    lifecycle_state varchar(32) default 'draft' not null comment '生命周期状态',
    published_at    datetime(3) null comment '发布时间',
    row_version     bigint      default 0       not null comment '乐观锁版本',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_version
        unique (tool_id, version),
    constraint uk_tool_version_checksum
        unique (tool_id, checksum)
) comment 'AI 工具版本表';

create index idx_tool_version_state
    on arte_ai_tool_version (tool_id, lifecycle_state, published_at);

create table arte_ai_tool_binding
(
    id                   bigint auto_increment comment '主键'
        primary key,
    binding_id           varchar(64)      not null comment '绑定业务 ID',
    owner_id             varchar(64)      not null comment '所有者用户 ID',
    workspace_id         varchar(64) null comment '工作空间 ID',
    workspace_scope varchar(64) generated always as (coalesce(workspace_id, '')) stored comment '工作空间唯一键辅助列',
    tool_id              varchar(64)      not null comment '工具业务 ID',
    tool_version         varchar(64)      not null comment '固定工具版本',
    credential_reference varchar(128) null comment '凭据引用，不保存明文',
    configuration        json null comment '运行配置',
    policy_override      json null comment '执行策略覆盖',
    enabled              tinyint(1) default 1                 not null comment '是否启用',
    row_version          bigint default 0 not null comment '乐观锁版本',
    create_by            varchar(64) null comment '创建人',
    update_by            varchar(64) null comment '更新人',
    create_time          datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time          datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_binding_id
        unique (binding_id),
    constraint uk_tool_binding_scope
        unique (owner_id, workspace_scope, tool_id, tool_version)
) comment 'AI 工具用户绑定表';

create index idx_tool_binding_owner
    on arte_ai_tool_binding (owner_id, workspace_id, enabled);

create index idx_tool_binding_tool
    on arte_ai_tool_binding (tool_id, tool_version, enabled);

create table arte_ai_assistant_tool
(
    id              bigint auto_increment comment '主键'
        primary key,
    assistant_id    int           not null comment '助手 ID',
    binding_id      varchar(64)   not null comment '工具绑定业务 ID',
    enabled         tinyint(1) default 1                not null comment '是否启用',
    policy_override json null comment '助手级执行策略覆盖',
    sort_order      int default 0 not null comment '排序',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_assistant_tool
        unique (assistant_id, binding_id)
) comment 'AI 助手工具关联表';

create index idx_assistant_tool_enabled
    on arte_ai_assistant_tool (assistant_id, enabled, sort_order);

create table arte_ai_tool_call
(
    id                 bigint auto_increment comment '主键'
        primary key,
    call_id            varchar(64)      not null comment '调用业务 ID',
    trace_id           varchar(64)      not null comment '链路 ID',
    span_id            varchar(64) null comment '跨度 ID',
    owner_id           varchar(64)      not null comment '所有者用户 ID',
    subject_id         varchar(64)      not null comment '调用主体 ID',
    conv_id            varchar(64) null comment '会话 ID',
    message_id         varchar(64) null comment '消息 ID',
    source_type        varchar(32) null comment '来源：MODEL/REST/MCP/WORKFLOW/AGENT',
    source_id          varchar(64) null comment '来源业务 ID',
    tool_id            varchar(64)      not null comment '工具业务 ID',
    tool_version       varchar(64)      not null comment '工具版本',
    binding_id         varchar(64) null comment '工具绑定 ID',
    execution_mode     varchar(32)      not null comment '执行模式',
    arguments_digest   varchar(128)     not null comment '参数摘要',
    arguments_snapshot json null comment '脱敏参数快照',
    context_snapshot   json null comment '执行上下文快照',
    policy_snapshot    json null comment '生效策略快照',
    status             varchar(32)      not null comment '调用状态',
    idempotency_key    varchar(128) null comment '幂等键',
    started_at         datetime(3) null comment '开始时间',
    completed_at       datetime(3) null comment '完成时间',
    latency_ms         bigint null comment '执行耗时毫秒',
    error_code         varchar(64) null comment '错误码',
    error_category     varchar(32) null comment '错误分类',
    error_message      text null comment '错误信息',
    input_tokens       int    default 0 not null comment '输入 Token',
    output_tokens      int    default 0 not null comment '输出 Token',
    total_tokens       int    default 0 not null comment '总 Token',
    metadata           json null comment '扩展元数据',
    row_version        bigint default 0 not null comment '乐观锁版本',
    create_by          varchar(64) null comment '创建人',
    update_by          varchar(64) null comment '更新人',
    create_time        datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time        datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_call_id
        unique (call_id),
    constraint uk_tool_call_idempotent
        unique (owner_id, idempotency_key)
) comment 'AI 工具调用主记录表';

create index idx_tool_call_trace
    on arte_ai_tool_call (trace_id, create_time);

create index idx_tool_call_tool
    on arte_ai_tool_call (tool_id, tool_version, create_time);

create index idx_tool_call_status
    on arte_ai_tool_call (status, update_time);

create index idx_tool_call_owner_status_create
    on arte_ai_tool_call (owner_id, status, create_time, id);

create index idx_tool_call_owner_tool_create
    on arte_ai_tool_call (owner_id, tool_id, create_time, id);

create index idx_tool_call_message
    on arte_ai_tool_call (conv_id, message_id);

create table arte_ai_tool_task
(
    id                    bigint auto_increment comment '主键'
        primary key,
    task_id               varchar(64)                  not null comment '任务业务 ID',
    call_id               varchar(64)                  not null comment '调用业务 ID',
    status                varchar(32) default 'queued' not null comment '任务状态',
    arguments_snapshot    json                         not null comment '可恢复参数快照',
    execution_policy      json                         not null comment '执行策略快照',
    owner_id              varchar(64)                  not null comment '所有者用户 ID',
    subject_id            varchar(64)                  not null comment '调用主体 ID',
    trace_id              varchar(64)                  not null comment '链路 ID',
    idempotency_key       varchar(128) null comment '幂等键',
    credential_binding_id varchar(64) null comment '凭据绑定 ID',
    progress              decimal(6, 5) null comment '进度：0~1',
    progress_message      varchar(500) null comment '进度描述',
    resume_token_hash     varchar(128) null comment '恢复令牌摘要',
    attempt               int         default 0        not null comment '执行次数',
    next_attempt_at       datetime(3) null comment '下次执行时间',
    worker_id             varchar(64) null comment '持有租约的 Worker',
    lease_until           datetime(3) null comment '租约截止时间',
    metadata              json null comment '扩展元数据',
    row_version           bigint      default 0        not null comment '乐观锁版本',
    create_by             varchar(64) null comment '创建人',
    update_by             varchar(64) null comment '更新人',
    create_time           datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time           datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_task_id
        unique (task_id),
    constraint uk_tool_task_call
        unique (call_id)
) comment 'AI 工具延迟执行任务表';

create index idx_tool_task_recover
    on arte_ai_tool_task (status, next_attempt_at, lease_until);

create index idx_tool_task_worker
    on arte_ai_tool_task (worker_id, lease_until);

create index idx_tool_task_resume
    on arte_ai_tool_task (resume_token_hash);

create index idx_tool_task_owner_update
    on arte_ai_tool_task (owner_id, update_time, id);

create index idx_tool_task_owner_status_update
    on arte_ai_tool_task (owner_id, status, update_time, id);

create table arte_ai_tool_call_result
(
    id           bigint auto_increment comment '主键'
        primary key,
    result_id    varchar(64) not null comment '结果业务 ID',
    call_id      varchar(64) not null comment '调用业务 ID',
    task_id      varchar(64) null comment '任务业务 ID',
    status       varchar(32) not null comment '结果状态',
    output       json null comment '结构化输出',
    content      json null comment '内容块',
    artifacts    json null comment '产物快照',
    usage_info   json null comment '用量信息',
    error_info   json null comment '错误信息',
    metadata     json null comment '扩展元数据',
    completed_at datetime(3)                           not null comment '完成时间',
    create_by    varchar(64) null comment '创建人',
    update_by    varchar(64) null comment '更新人',
    create_time  datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time  datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_result_id
        unique (result_id),
    constraint uk_tool_result_call
        unique (call_id),
    constraint uk_tool_result_task
        unique (task_id)
) comment 'AI 工具调用结果表';

create table arte_ai_tool_approval
(
    id                bigint auto_increment comment '主键'
        primary key,
    request_id        varchar(64)                   not null comment '审批请求 ID',
    call_id           varchar(64)                   not null comment '调用 ID',
    task_id           varchar(64) null comment '任务 ID',
    workflow_run_id   varchar(64) null comment '工作流运行 ID',
    tool_id           varchar(64)                   not null comment '工具 ID',
    tool_version      varchar(64)                   not null comment '工具版本',
    arguments_digest  varchar(128)                  not null comment '参数摘要',
    display_arguments json null comment '脱敏展示参数',
    summary           varchar(1000) null comment '审批摘要',
    status            varchar(32) default 'pending' not null comment 'PENDING/APPROVED/REJECTED/EXPIRED',
    approver_id       varchar(64) null comment '审批人',
    decision_reason   varchar(1000) null comment '审批理由',
    expires_at        datetime(3)                           not null comment '过期时间',
    decided_at        datetime(3) null comment '决定时间',
    create_by         varchar(64) null comment '创建人',
    update_by         varchar(64) null comment '更新人',
    create_time       datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time       datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_approval_request
        unique (request_id),
    constraint uk_tool_approval_task
        unique (task_id)
) comment 'AI 工具人工审批表';

create index idx_tool_approval_pending
    on arte_ai_tool_approval (status, expires_at);

create index idx_tool_approval_call
    on arte_ai_tool_approval (call_id, create_time);

create index idx_tool_approval_owner_status_create
    on arte_ai_tool_approval (create_by, status, create_time, id);

create table arte_ai_tool_execution_event
(
    id              bigint auto_increment comment '主键'
        primary key,
    event_id        varchar(64) not null comment '事件业务 ID',
    event_type      varchar(64) not null comment '事件类型',
    occurred_at     datetime(3)                           not null comment '发生时间',
    trace_id        varchar(64) not null comment '链路 ID',
    span_id         varchar(64) null comment '跨度 ID',
    call_id         varchar(64) not null comment '调用 ID',
    task_id         varchar(64) null comment '任务 ID',
    workflow_run_id varchar(64) null comment '工作流运行 ID',
    tool_id         varchar(64) not null comment '工具 ID',
    tool_version    varchar(64) not null comment '工具版本',
    attributes      json null comment '低基数、已脱敏事件属性',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '更新时间',
    constraint uk_tool_event_id
        unique (event_id)
) comment 'AI 工具执行事件表';

create index idx_tool_event_trace
    on arte_ai_tool_execution_event (trace_id, occurred_at, id);

create index idx_tool_event_call
    on arte_ai_tool_execution_event (call_id, occurred_at, id);

create index idx_tool_event_run
    on arte_ai_tool_execution_event (workflow_run_id, occurred_at, id);

create table arte_ai_workflow
(
    id              bigint auto_increment comment '主键'
        primary key,
    workflow_id     varchar(64)                 not null comment '工作流业务 ID',
    owner_id        varchar(64)                 not null comment '所有者用户 ID',
    name            varchar(200)                not null comment '工作流名称',
    description     varchar(1000) null comment '工作流描述',
    latest_version  varchar(64) null comment '最新发布版本',
    lifecycle_state varchar(32) default 'draft' not null comment '生命周期状态',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_workflow_id
        unique (workflow_id)
) comment 'AI 工作流目录表';

create index idx_workflow_owner_state
    on arte_ai_workflow (owner_id, lifecycle_state, update_time);

create table arte_ai_workflow_version
(
    id              bigint auto_increment comment '主键'
        primary key,
    workflow_id     varchar(64)                 not null comment '工作流业务 ID',
    version         varchar(64)                 not null comment '版本',
    name            varchar(200)                not null comment '版本名称',
    description     varchar(1000) null comment '版本描述',
    tags             json null comment '工作流标签',
    input_schema    json                        not null comment '输入 Schema',
    output_schema   json                        not null comment '输出 Schema',
    execution_policy json not null comment '执行步数、超时、重试和并行预算',
    nodes           json                        not null comment '节点 DSL',
    edges           json                        not null comment '边 DSL',
    compiled_plan   json null comment '编译后的执行计划',
    pinned_tools    json null comment '固定工具版本映射',
    entry_node_id   varchar(64) null comment '入口节点 ID',
    checksum        varchar(128)                not null comment '版本校验和',
    lifecycle_state varchar(32) default 'draft' not null comment '生命周期状态',
    published_at    datetime(3) null comment '发布时间',
    row_version     bigint      default 0       not null comment '乐观锁版本',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_workflow_version
        unique (workflow_id, version)
) comment 'AI 工作流版本表';

create index idx_workflow_version_state
    on arte_ai_workflow_version (workflow_id, lifecycle_state, published_at);

create table arte_ai_workflow_run
(
    id                bigint auto_increment comment '主键'
        primary key,
    run_id            varchar(64)                   not null comment '运行业务 ID',
    workflow_id       varchar(64)                   not null comment '工作流 ID',
    workflow_version  varchar(64)                   not null comment '工作流版本',
    owner_id          varchar(64)                   not null comment '所有者用户 ID',
    subject_id        varchar(64)                   not null comment '执行主体 ID',
    trace_id          varchar(64)                   not null comment '链路 ID',
    status            varchar(32) default 'created' not null comment '运行状态',
    inputs            json null comment '输入快照',
    variables         json null comment '运行变量',
    active_node_ids   json null comment '当前活动节点',
    outputs           json null comment '最终输出',
    error_info        json null comment '错误信息',
    resume_token_hash varchar(128) null comment '恢复令牌摘要',
    maximum_steps     int         default 100       not null comment '最大步骤数',
    current_steps     int         default 0         not null comment '当前步骤数',
    started_at        datetime(3) null comment '开始时间',
    completed_at      datetime(3) null comment '完成时间',
    deadline_at datetime(3) null comment '执行预算截止时间',
    worker_id   varchar(64) null comment '持有执行权的节点',
    lease_until datetime(3) null comment '执行租约截止时间',
    row_version       bigint      default 0         not null comment '乐观锁版本',
    create_by         varchar(64) null comment '创建人',
    update_by         varchar(64) null comment '更新人',
    create_time       datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time       datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_workflow_run_id
        unique (run_id)
) comment 'AI 工作流运行表';

create index idx_workflow_run_workflow
    on arte_ai_workflow_run (workflow_id, workflow_version, create_time);

create index idx_workflow_run_owner_workflow_create
    on arte_ai_workflow_run (owner_id, workflow_id, create_time, id);

create index idx_workflow_run_status
    on arte_ai_workflow_run (owner_id, status, update_time);

create index idx_workflow_run_trace
    on arte_ai_workflow_run (trace_id);

create index idx_workflow_run_recover
    on arte_ai_workflow_run (status, lease_until, update_time);

create index idx_workflow_run_resume
    on arte_ai_workflow_run (resume_token_hash);

create table arte_ai_workflow_node_run
(
    id           bigint auto_increment comment '主键'
        primary key,
    node_run_id  varchar(64)      not null comment '节点运行业务 ID',
    run_id       varchar(64)      not null comment '工作流运行 ID',
    node_id      varchar(64)      not null comment '节点 ID',
    node_type    varchar(32)      not null comment '节点类型',
    attempt      int    default 1 not null comment '执行次数',
    status       varchar(32)      not null comment '节点状态',
    call_id      varchar(64) null comment '关联工具调用 ID',
    inputs       json null comment '节点输入快照',
    outputs      json null comment '节点输出快照',
    error_info   json null comment '错误信息',
    started_at   datetime(3) null comment '开始时间',
    completed_at datetime(3) null comment '完成时间',
    latency_ms   bigint null comment '执行耗时毫秒',
    row_version  bigint default 0 not null comment '乐观锁版本',
    create_by    varchar(64) null comment '创建人',
    update_by    varchar(64) null comment '更新人',
    create_time  datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time  datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_workflow_node_run_id
        unique (node_run_id),
    constraint uk_workflow_node_attempt
        unique (run_id, node_id, attempt)
) comment 'AI 工作流节点运行表';

create index idx_workflow_node_run
    on arte_ai_workflow_node_run (run_id, create_time);

create index idx_workflow_node_call
    on arte_ai_workflow_node_run (call_id);

create table arte_ai_workflow_checkpoint
(
    id            bigint auto_increment comment '主键'
        primary key,
    checkpoint_id varchar(64) not null comment '检查点业务 ID',
    run_id        varchar(64) not null comment '工作流运行 ID',
    sequence      bigint      not null comment '检查点序号',
    state         json        not null comment '可恢复状态快照',
    create_by     varchar(64) null comment '创建人',
    update_by     varchar(64) null comment '更新人',
    create_time   datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time   datetime(3) default CURRENT_TIMESTAMP(3) not null comment '更新时间',
    constraint uk_workflow_checkpoint_id
        unique (checkpoint_id),
    constraint uk_workflow_checkpoint_sequence
        unique (run_id, sequence)
) comment 'AI 工作流检查点表';

create index idx_workflow_checkpoint_latest
    on arte_ai_workflow_checkpoint (run_id, sequence desc);
