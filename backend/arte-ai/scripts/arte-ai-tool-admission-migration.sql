-- AI TOOL S2-03：已有 S1-04／06／07／08 及通用新 AI 执行表后显式执行。
-- 停写、备份并核对目标表尚不存在；运行时不建表。本脚本不启用受理、Worker 或工具。
create table arte_ai_tool_execution_snapshot
(
    invocation_key    char(64) not null comment '所属 Invocation ID 的长度前缀 SHA-256，一对一主键' primary key,
    owner_key         char(64) not null comment '租户、空间、主体的长度前缀 SHA-256',
    binding_key       char(64) not null comment '稳定绑定 ID 的长度前缀 SHA-256，重放不重新选择版本',
    idempotency_key   char(64) not null comment '业务幂等键的长度前缀 SHA-256',
    submission_digest char(64) not null comment '原始参数及请求上限摘要，不含重新分配 ID、时刻或当前配置',
    request_digest    char(64) not null comment '固定实际版本、配置、Schema、策略和权限上限的执行摘要',
    payload_digest    char(64) not null comment '实际内部快照字节的 SHA-256，读取时重验',
    snapshot          longtext not null comment 'tool-execution-snapshot v1：独立本地免费只读执行材料，无运行资源或凭据值',
    constraint uk_tool_admission_replay unique (owner_key, binding_key, idempotency_key),
    constraint fk_tool_admission_invocation foreign key (invocation_key) references arte_ai_invocation (id_key)
) engine=InnoDB comment 'AI TOOL 与通用受理同事务的不可变执行快照';
