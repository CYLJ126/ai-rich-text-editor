-- 新安装与已有新 AI 实例均执行此增量脚本；先部署模型及耐久工作队列表，不创建额外许可或预算。
create table if not exists arte_ai_new_action
(
    action_id       char(36)     not null comment '独立动作 ID，使用 UUID；模型执行以 action:动作ID 作为独立幂等键',
    scope_key       char(64)     not null comment '租户、工作空间及主体的规范 SHA-256 摘要，用于隔离查询与提交幂等',
    tenant_id       varchar(128) not null comment '动作所属租户 ID，由已认证执行上下文确定',
    workspace_id    varchar(128) not null comment '动作所属工作空间 ID，由已认证执行上下文确定',
    principal_type  varchar(32)  not null comment '提交主体类型，与主体 ID 一起隔离动作输入及结果',
    principal_id    varchar(128) not null comment '提交主体 ID，由登录身份确定，不接受请求体指定',
    submission_key  varchar(128) not null comment '客户端提交幂等键，同一主体范围内唯一；重新生成使用新键',
    request_digest  char(71)     not null comment '动作定义、固定输入、模型版本、选项及再生成来源的 SHA-256 摘要，含 sha256: 前缀',
    payload_json    mediumtext   not null comment 'arte.action.input.v1 格式的不可变输入 JSON，含指令、原文、要求、固定定义版本及可空再生成来源；不存储凭据',
    created_at      timestamp(6) not null default current_timestamp(6) comment '动作输入登记时间，微秒精度；不代表模型已受理或生成成功',
    primary key (action_id),
    constraint uq_ai_new_action_submission unique (scope_key, submission_key),
    index ix_ai_new_action_scope (scope_key, created_at)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 独立动作固定输入表，执行状态与结果由统一模型账本提供';
