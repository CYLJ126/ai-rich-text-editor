-- 独立新模型调用；不修改旧 AI 表。不自动创建预算／许可记录。
create table if not exists arte_ai_new_budget
(
    scope_key       char(64)       not null               comment '租户、工作空间及主体的作用域规范摘要',
    amount_limit    decimal(24, 8) not null               comment '累计预算额度上限',
    reserved_amount decimal(24, 8) default 0 not null     comment '当前尚未结算的预留金额',
    spent_amount    decimal(24, 8) default 0 not null     comment '已确认消耗的金额',
    currency        varchar(16)    not null               comment '费用币种',
    enabled         boolean        default false not null comment '是否开通预算，默认关闭',
    revision        bigint         default 1 not null     comment '预算账本修订版本，从 1 开始',
    primary key (scope_key)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 主体预算账本表';

create table if not exists arte_ai_new_execution
(
    execution_id       char(36)       not null comment '执行 ID，使用 UUID',
    attempt_id         char(36)       not null comment '调用尝试 ID，使用 UUID',
    scope_key          char(64)       not null comment '租户、工作空间及主体的作用域规范摘要',
    identity_key       char(64)       not null comment '作用域、模型操作及幂等键的规范摘要',
    request_digest     char(71)       not null comment '规范请求 SHA-256 摘要，含 sha256: 前缀',
    capability_id      varchar(128)   not null comment '固定能力定义 ID',
    capability_version varchar(64)    not null comment '固定能力定义版本',
    binding_id         varchar(128)   not null comment '能力使用绑定 ID',
    binding_version    varchar(64)    not null comment '固定能力使用绑定版本',
    connection_id      varchar(128)   not null comment '固定受控连接 ID',
    connection_version varchar(64)    not null comment '固定受控连接版本',
    status             varchar(32)    not null comment '模型执行状态，区别于聊天提交状态',
    revision           bigint         not null comment '执行记录乐观锁版本',
    dispatched         boolean        not null comment '是否已开始向供应商派发请求',
    accepted_at        timestamp(6)   not null comment '可靠受理时间',
    reserved_amount    decimal(24, 8) not null comment '本次执行的费用预留金额',
    currency           varchar(16)    not null comment '费用币种',
    budget_status      varchar(32)    not null comment '预算状态：RESERVED、SETTLED、PENDING_RECONCILIATION 或 RELEASED',
    resource_context_json mediumtext  null     comment '固定资料上下文 JSON，含实际来源、范围、预算与摘要；纯文本旧调用为空，读取及外发须重新授权',
    result_json        mediumtext     null     comment '类型化模型结果 JSON，尚无结果时为空',
    error_json         text           null     comment '稳定执行错误信封 JSON，无错误时为空',
    primary key (execution_id),
    constraint uq_ai_new_execution_identity
    unique (identity_key),
    constraint uq_ai_new_execution_scope
    unique (execution_id, scope_key),
    index ix_ai_new_scope (scope_key, accepted_at)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 最小模型执行表：受理、单次尝试、结果及预算结算';

create table if not exists arte_ai_new_event
(
    execution_id char(36)     not null comment '执行 ID，使用 UUID',
    attempt_id   char(36)     not null comment '调用尝试 ID，使用 UUID',
    sequence_no  bigint       not null comment '执行内事件序号，从 0 开始',
    status       varchar(32)  not null comment '该事件对应的模型执行状态',
    occurred_at  timestamp(6) not null comment '事件发生时间',
    result_json  mediumtext   null     comment '类型化模型结果 JSON，尚无结果时为空',
    error_json   text         null     comment '稳定执行错误信封 JSON，无错误时为空',
    primary key (execution_id, sequence_no),
    constraint fk_ai_new_event_execution
    foreign key (execution_id)
    references arte_ai_new_execution (execution_id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 模型执行事件表：耐久输出及事件重放';

-- 第三批流式输出：增量与状态事件同事务提交，部分正文缓存供刷新恢复。
create table if not exists arte_ai_new_stream_output
(
    execution_id  char(36)   not null comment '模型执行 ID，使用 UUID，与模型执行记录关联',
    partial_text  mediumtext not null comment '已持久化的累计回答正文，用于刷新恢复，不代表调用成功',
    utf8_bytes    int        not null comment '累计回答正文的 UTF-8 字节数，用于限制流式输出大小',
    last_sequence bigint     not null comment '已缓存的最后一条文本增量事件序号，用于续传去重',
    primary key (execution_id),
    constraint fk_ai_new_stream_execution
        foreign key (execution_id)
        references arte_ai_new_execution (execution_id)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 流式回答缓存表：累计正文、字节数及增量续传游标';

create table if not exists arte_ai_new_stream_delta
(
    execution_id char(36) not null comment '模型执行 ID，使用 UUID，与模型执行事件关联',
    sequence_no  bigint   not null comment '关联的模型执行事件序号，与状态事件共用执行内递增序列',
    text_delta   text     not null comment '本批新增的模型回答文本，按事件序号拼接，不是累计正文',
    primary key (execution_id, sequence_no),
    constraint fk_ai_new_stream_event
        foreign key (execution_id, sequence_no)
        references arte_ai_new_event (execution_id, sequence_no)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 流式文本增量表：与执行事件原子提交，支持按游标重放';
