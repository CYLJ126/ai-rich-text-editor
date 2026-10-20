-- S4-02：已有 S3 库停写、备份后显式执行；新装完整基线已包含，不重复执行。
-- 独立 Run 推进基础，不改 Invocation、Turn、账本和现有 Outbox，不自动启动 Worker。
create table arte_ai_tool_chat_run
(
    run_key           char(64) not null primary key,
    owner_key         char(64) not null comment '租户／空间／主体的统一长度前缀摘要',
    idempotency_key   char(64) not null,
    submission_digest char(64) not null,
    input_digest      char(64) not null,
    input_snapshot    longtext not null comment '固定用户输入、模型、工具集合及权限上限，不含运行对象或 Secret 值',
    state_digest      char(64) not null,
    state_snapshot    longtext not null comment '权威检查点和累计计划数；子操作计划不表示实际执行',
    version           bigint   not null,
    deadline          bigint   not null comment 'Run 原总期限，UTC epoch millis；恢复不能延长',
    unique key uk_tool_chat_admission(owner_key,idempotency_key)
) engine=InnoDB comment '工具聊天根执行；不冒充通用 Invocation';

create table arte_ai_tool_chat_run_work
(
    run_key        char(64)     not null primary key,
    available_at   bigint       not null comment '下一次唤醒；park 最迟在原总期限醒来',
    enabled        boolean      not null,
    worker_id      varchar(256) not null,
    lease_until    bigint       not null,
    fence          bigint       not null comment '每次领取递增；过期及旧 token 不能写检查点',
    signal_version bigint       not null comment '持租期间收到的唤醒不能被稍后的 park 覆盖',
    index          ix_tool_chat_work_due(enabled,available_at,lease_until,run_key),
    constraint fk_tool_chat_work_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment 'Run 独立耐久唤醒与租约，不派发模型或业务工具';

create table arte_ai_tool_chat_run_step
(
    child_key      char(64) not null primary key comment '逻辑子执行 ID 的统一摘要，稳定且全局唯一',
    run_key        char(64) not null,
    payload_digest char(64) not null,
    snapshot       longtext not null comment 'round／kind／modelCallId 到子执行及领域操作键的不可变映射',
    index          ix_tool_chat_steps(run_key,child_key),
    constraint fk_tool_chat_step_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment '固定逻辑步骤及输入；实际子 Invocation 由后续受理幂等创建';

create table arte_ai_tool_chat_run_command
(
    run_key        char(64) not null,
    command_key    char(64) not null,
    request_digest char(64) not null,
    payload_digest char(64) not null,
    snapshot       longtext not null comment '同键同义返回原回执，跨租约重放不重复推进',
    primary key (run_key, command_key),
    constraint fk_tool_chat_command_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment '推进命令防重，与检查点、步骤和工作项同事务提交';
