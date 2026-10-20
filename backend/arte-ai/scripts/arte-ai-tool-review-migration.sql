-- S3-05：已有 S3-04 库停写、备份后显式执行；全新基线已包含，不重复执行。
-- 不修改业务／费用事实，不授予角色，不复用旧项目核对表。
create index ix_tool_outcome_scope on arte_ai_tool_outcome_check (space_key, invocation_key);

create table arte_ai_tool_outcome_review
(
    invocation_key char(64)      not null primary key,
    assignee_id    varchar(256)  not null comment '当前认领的稳定主体；空串为当前空间核对责任池',
    assigned_at    bigint        not null,
    next_step      varchar(1000) not null,
    due_at         bigint        not null comment '责任方处理截止 UTC epoch millis',
    alerted_at     bigint        not null comment '最近后台告警，避免每轮重复通知',
    index          ix_tool_review_due(due_at,invocation_key),
    constraint fk_tool_review_case foreign key (invocation_key) references arte_ai_tool_outcome_check (invocation_key)
) engine=InnoDB comment '人工责任方及下一步；原 Case 版本统一仲裁';

create table arte_ai_tool_outcome_review_command
(
    command_key        char(64)      not null primary key,
    invocation_key     char(64)      not null,
    actor_id           varchar(256)  not null,
    request_digest     char(64)      not null,
    action             varchar(32)   not null,
    outcome            varchar(32)   not null,
    case_version       bigint        not null,
    invocation_version bigint        not null,
    case_state         varchar(32)   not null,
    invocation_state   varchar(32)   not null,
    attempt_id         varchar(256)  not null,
    operation_key      varchar(256)  not null,
    source             varchar(32)   not null,
    decision           varchar(32)   not null,
    evidence_ref       varchar(256)  not null,
    evidence_digest    varchar(64)   not null,
    note               varchar(2100) not null comment '人工核验记录及决定原因；不得录入凭据',
    next_step          varchar(1000) not null,
    recorded_at        bigint        not null,
    index              ix_tool_review_history(invocation_key,recorded_at,command_key),
    constraint fk_tool_review_command foreign key (invocation_key) references arte_ai_tool_outcome_check (invocation_key)
) engine=InnoDB comment '核对命令防重与不可变人工证据审计，同键异义拒绝';
