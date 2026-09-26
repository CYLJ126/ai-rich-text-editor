-- 临时文件：AI 工具二期要实现的功能对应的数据表（完整功能上线后，合并一、二两个表）
create table arte_ai_tool_access_policy
(
    id              bigint auto_increment comment '主键'
        primary key,
    policy_id       varchar(64)   not null comment '策略业务 ID',
    owner_id        varchar(64)   not null comment '所有者用户 ID',
    subject_type    varchar(32)   not null comment '主体类型：USER/ROLE/SERVICE/ASSISTANT',
    subject_id      varchar(64)   not null comment '主体 ID',
    resource_type   varchar(32)   not null comment '资源类型：TOOL/TOOL_BINDING/WORKFLOW',
    resource_id     varchar(64)   not null comment '资源 ID',
    effect          varchar(16)   not null comment 'ALLOW/DENY',
    required_scopes json null comment '要求的权限范围',
    conditions      json null comment '附加条件',
    priority        int default 0 not null comment '优先级',
    enabled         tinyint(1) default 1                 not null comment '是否启用',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_access_policy
        unique (policy_id)
) comment 'AI 工具访问策略表';

create index idx_tool_policy_subject
    on arte_ai_tool_access_policy (owner_id, subject_type, subject_id, enabled, priority);

create index idx_tool_policy_resource
    on arte_ai_tool_access_policy (resource_type, resource_id, enabled);

create table arte_ai_tool_guardrail
(
    id             bigint auto_increment comment '主键'
        primary key,
    guardrail_id   varchar(64)                  not null comment 'Guardrail 业务 ID',
    owner_id       varchar(64) null comment '所有者用户 ID，空表示平台级',
    name           varchar(100)                 not null comment '名称',
    implementation varchar(200)                 not null comment '实现 Bean 或处理器标识',
    phases         json                         not null comment '支持阶段',
    configuration  json null comment '非敏感配置',
    priority       int         default 0        not null comment '默认优先级',
    fail_mode      varchar(16) default 'closed' not null comment '失败模式：OPEN/CLOSED',
    enabled        tinyint(1) default 1                 not null comment '是否启用',
    row_version    bigint      default 0        not null comment '乐观锁版本',
    create_by      varchar(64) null comment '创建人',
    update_by      varchar(64) null comment '更新人',
    create_time    datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time    datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_guardrail
        unique (guardrail_id)
) comment 'AI 工具 Guardrail 定义表';

create index idx_tool_guardrail_owner
    on arte_ai_tool_guardrail (owner_id, enabled, priority);

create table arte_ai_tool_guardrail_binding
(
    id           bigint auto_increment comment '主键'
        primary key,
    guardrail_id varchar(64)   not null comment 'Guardrail 业务 ID',
    scope_type   varchar(32)   not null comment 'GLOBAL/TENANT/ASSISTANT/TOOL/TOOL_BINDING/WORKFLOW',
    scope_id     varchar(64)   not null comment '作用域 ID，全局使用 global',
    priority     int default 0 not null comment '绑定优先级',
    fail_mode    varchar(16) null comment '绑定级失败模式覆盖',
    enabled      tinyint(1) default 1                 not null comment '是否启用',
    create_by    varchar(64) null comment '创建人',
    update_by    varchar(64) null comment '更新人',
    create_time  datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time  datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_guardrail_binding
        unique (guardrail_id, scope_type, scope_id)
) comment 'AI 工具 Guardrail 绑定表';

create index idx_guardrail_binding_scope
    on arte_ai_tool_guardrail_binding (scope_type, scope_id, enabled, priority);

create table arte_ai_tool_credential
(
    id                 bigint auto_increment comment '主键'
        primary key,
    credential_id      varchar(64)                  not null comment '凭据业务 ID',
    owner_id           varchar(64)                  not null comment '所有者用户 ID',
    name               varchar(100)                 not null comment '凭据名称',
    credential_type    varchar(32)                  not null comment 'API_KEY/OAUTH2/BASIC/CERTIFICATE',
    secret_reference   varchar(256) null comment '外部密钥系统引用',
    encrypted_payload  longblob null comment '使用 KMS 加密后的密文',
    encryption_key_ref varchar(256) null comment '加密密钥引用',
    masked_identifier  varchar(128) null comment '脱敏标识',
    status             varchar(32) default 'active' not null comment '状态',
    expires_at         datetime(3) null comment '过期时间',
    last_rotated_at    datetime(3) null comment '最后轮换时间',
    row_version        bigint      default 0        not null comment '乐观锁版本',
    create_by          varchar(64) null comment '创建人',
    update_by          varchar(64) null comment '更新人',
    create_time        datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time        datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_credential
        unique (credential_id)
) comment 'AI 工具凭据元数据表';

create index idx_tool_credential_owner
    on arte_ai_tool_credential (owner_id, status, expires_at);

create table arte_ai_tool_artifact
(
    id              bigint auto_increment comment '主键'
        primary key,
    artifact_id     varchar(64)   not null comment '产物业务 ID',
    call_id         varchar(64)   not null comment '调用 ID',
    task_id         varchar(64) null comment '任务 ID',
    workflow_run_id varchar(64) null comment '工作流运行 ID',
    name            varchar(255)  not null comment '产物名称',
    media_type      varchar(128) null comment 'MIME 类型',
    uri             varchar(1000) not null comment '资源 URI',
    size_bytes      bigint null comment '大小',
    checksum        varchar(128) null comment '内容校验和',
    metadata        json null comment '扩展元数据',
    expires_at      datetime(3) null comment '过期时间',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_artifact
        unique (artifact_id)
) comment 'AI 工具产物表';

create index idx_tool_artifact_call
    on arte_ai_tool_artifact (call_id, create_time);

create index idx_tool_artifact_expire
    on arte_ai_tool_artifact (expires_at);

create table arte_ai_tool_evaluation_suite
(
    id          bigint auto_increment comment '主键'
        primary key,
    suite_id    varchar(64)                 not null comment '评估套件业务 ID',
    version     varchar(64)                 not null comment '套件版本',
    name        varchar(200)                not null comment '套件名称',
    description varchar(1000) null comment '套件描述',
    evaluators  json                        not null comment '评估器及配置',
    status      varchar(32) default 'draft' not null comment '状态',
    row_version bigint      default 0       not null comment '乐观锁版本',
    create_by   varchar(64) null comment '创建人',
    update_by   varchar(64) null comment '更新人',
    create_time datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_evaluation_suite
        unique (suite_id, version)
) comment 'AI 工具评估套件表';

create table arte_ai_tool_evaluation_case
(
    id               bigint auto_increment comment '主键'
        primary key,
    case_id          varchar(64)   not null comment '评估用例业务 ID',
    suite_id         varchar(64)   not null comment '套件业务 ID',
    suite_version    varchar(64)   not null comment '套件版本',
    name             varchar(200)  not null comment '用例名称',
    tool_call        json          not null comment '工具调用输入',
    expected_outcome json null comment '预期结果',
    criteria         json null comment '评分条件',
    enabled          tinyint(1) default 1                 not null comment '是否启用',
    sort_order       int default 0 not null comment '排序',
    create_by        varchar(64) null comment '创建人',
    update_by        varchar(64) null comment '更新人',
    create_time      datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time      datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_evaluation_case
        unique (case_id),
    constraint uk_tool_evaluation_suite_case
        unique (suite_id, suite_version, case_id)
) comment 'AI 工具评估用例表';

create index idx_tool_evaluation_case_suite
    on arte_ai_tool_evaluation_case (suite_id, suite_version, enabled, sort_order);

create table arte_ai_tool_evaluation_run
(
    id              bigint auto_increment comment '主键'
        primary key,
    run_id          varchar(64)                  not null comment '评估运行业务 ID',
    suite_id        varchar(64)                  not null comment '套件业务 ID',
    suite_version   varchar(64)                  not null comment '套件版本',
    status          varchar(32) default 'queued' not null comment '运行状态',
    total_cases     int         default 0        not null comment '用例总数',
    completed_cases int         default 0        not null comment '已完成数',
    passed_cases    int         default 0        not null comment '通过数',
    failed_cases    int         default 0        not null comment '失败数',
    configuration   json null comment '运行配置',
    started_at      datetime(3) null comment '开始时间',
    completed_at    datetime(3) null comment '完成时间',
    error_message   text null comment '运行错误',
    row_version     bigint      default 0        not null comment '乐观锁版本',
    create_by       varchar(64) null comment '创建人',
    update_by       varchar(64) null comment '更新人',
    create_time     datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time     datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_evaluation_run
        unique (run_id)
) comment 'AI 工具评估运行表';

create index idx_tool_evaluation_run_suite
    on arte_ai_tool_evaluation_run (suite_id, suite_version, create_time);

create index idx_tool_evaluation_run_status
    on arte_ai_tool_evaluation_run (status, update_time);

create table arte_ai_tool_evaluation_result
(
    id             bigint auto_increment comment '主键'
        primary key,
    result_id      varchar(64)  not null comment '评估结果业务 ID',
    run_id         varchar(64)  not null comment '评估运行 ID',
    suite_id       varchar(64)  not null comment '套件 ID',
    case_id        varchar(64)  not null comment '用例 ID',
    evaluator_name varchar(100) not null comment '评估器名称',
    call_id        varchar(64) null comment '被评估调用 ID',
    trace_id       varchar(64) null comment '被评估链路 ID',
    passed         tinyint(1)                            not null comment '是否通过',
    scores         json null comment '各维度分数',
    feedback       text null comment '评估反馈',
    evidence       json null comment '证据',
    create_by      varchar(64) null comment '创建人',
    update_by      varchar(64) null comment '更新人',
    create_time    datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time    datetime(3) default CURRENT_TIMESTAMP(3) not null comment '更新时间',
    constraint uk_tool_evaluation_result
        unique (result_id),
    constraint uk_tool_evaluation_case_evaluator
        unique (run_id, case_id, evaluator_name)
) comment 'AI 工具评估结果表';

create index idx_tool_evaluation_result_run
    on arte_ai_tool_evaluation_result (run_id, passed);

create table arte_ai_tool_audit_log
(
    id            bigint auto_increment comment '主键'
        primary key,
    audit_id      varchar(64) not null comment '审计业务 ID',
    owner_id      varchar(64) not null comment '所有者用户 ID',
    actor_id      varchar(64) not null comment '操作主体 ID',
    action        varchar(64) not null comment '操作动作',
    resource_type varchar(32) not null comment '资源类型',
    resource_id   varchar(64) not null comment '资源 ID',
    trace_id      varchar(64) null comment '链路 ID',
    outcome       varchar(32) not null comment '结果',
    ip_address    varchar(64) null comment '来源 IP',
    user_agent    varchar(500) null comment 'User-Agent',
    detail        json null comment '已脱敏详情',
    create_by     varchar(64) null comment '创建人',
    update_by     varchar(64) null comment '更新人',
    create_time   datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time   datetime(3) default CURRENT_TIMESTAMP(3) not null comment '更新时间',
    constraint uk_tool_audit
        unique (audit_id)
) comment 'AI 工具审计日志表';

create index idx_tool_audit_resource
    on arte_ai_tool_audit_log (owner_id, resource_type, resource_id, create_time);

create index idx_tool_audit_actor
    on arte_ai_tool_audit_log (owner_id, actor_id, create_time);

create index idx_tool_audit_trace
    on arte_ai_tool_audit_log (trace_id);

create table arte_ai_tool_metric_daily
(
    id               bigint auto_increment comment '主键'
        primary key,
    metric_date      date             not null comment '统计日期',
    owner_id         varchar(64)      not null comment '所有者用户 ID',
    tool_id          varchar(64)      not null comment '工具 ID',
    tool_version     varchar(64)      not null comment '工具版本',
    call_count       bigint default 0 not null comment '调用次数',
    success_count    bigint default 0 not null comment '成功次数',
    failure_count    bigint default 0 not null comment '失败次数',
    denied_count     bigint default 0 not null comment '拒绝次数',
    timeout_count    bigint default 0 not null comment '超时次数',
    retry_count      bigint default 0 not null comment '重试次数',
    total_latency_ms bigint default 0 not null comment '总延迟毫秒',
    max_latency_ms   bigint default 0 not null comment '最大延迟毫秒',
    p50_latency_ms   bigint null comment 'P50 延迟',
    p95_latency_ms   bigint null comment 'P95 延迟',
    p99_latency_ms   bigint null comment 'P99 延迟',
    input_tokens     bigint default 0 not null comment '输入 Token',
    output_tokens    bigint default 0 not null comment '输出 Token',
    create_by        varchar(64) null comment '创建人',
    update_by        varchar(64) null comment '更新人',
    create_time      datetime(3) default CURRENT_TIMESTAMP(3) not null comment '创建时间',
    update_time      datetime(3) default CURRENT_TIMESTAMP(3) not null on update CURRENT_TIMESTAMP(3) comment '更新时间',
    constraint uk_tool_metric_daily
        unique (metric_date, owner_id, tool_id, tool_version)
) comment 'AI 工具每日统计表';

create index idx_tool_metric_tool_date
    on arte_ai_tool_metric_daily (tool_id, tool_version, metric_date);
