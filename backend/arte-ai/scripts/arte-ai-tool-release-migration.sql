-- AI TOOL S1-06 增量：先部署 S1-04 目录表，本脚本仅新增生命周期及管理回执审计表。
-- 停写并备份，核对两张目标表不存在后显式执行；运行时代码不执行 DDL。
-- 已存在的工具定义保持不变；没有治理行的固定定义派生为 version=0 的 DRAFT，不需要回填伪发布。

create table arte_ai_tool_release
(
    tool_key            char(64)    not null comment '固定工具引用的长度前缀 SHA-256，与不可变定义表同键' primary key,
    compatible_from_key char(64) null comment '已发布兼容前驱固定工具键；首次发布冻结，不按标签排序',
    row_version         bigint      not null comment '治理乐观版本，从首次成功管理变更的 1 开始',
    state               varchar(16) not null comment 'PUBLISHED、DEPRECATED 或 DISABLED；无记录派生 DRAFT',
    payload_digest      char(64)    not null comment '实际白名单快照的 SHA-256，读取时核对',
    release_snapshot    longtext    not null comment 'tool-release v1 当前治理状态，首次发布材料及契约不可原地替换',
    updated_micros      bigint      not null comment '最近成功变更的 UTC Unix 微秒；重放和拒绝不推进',
    constraint uk_tool_release_successor unique (compatible_from_key),
    constraint fk_tool_release_definition foreign key (tool_key) references arte_ai_tool_definition (id_key),
    constraint fk_tool_release_predecessor foreign key (compatible_from_key) references arte_ai_tool_definition (id_key),
    constraint ck_tool_release_version check (row_version > 0),
    constraint ck_tool_release_state check (state in ('PUBLISHED', 'DEPRECATED', 'DISABLED'))
) engine=InnoDB comment 'AI TOOL 当前生命周期与固定发布契约表';

create table arte_ai_tool_release_command
(
    command_key      char(64)    not null comment '真实主体类别、主体及幂等键的长度前缀 SHA-256' primary key,
    actor_key        char(64)    not null comment '真实稳定主体类别与身份摘要，不使用用户名或执行归属',
    tool_key         char(64)    not null comment '请求的固定工具引用摘要；允许保存不存在工具的拒绝回执',
    request_digest   char(64)    not null comment '真实主体与完整命令语义摘要，不包含重算预检时间',
    outcome          varchar(32) not null comment '首次领域决定 APPLIED、NOT_FOUND、VERSION_CONFLICT 或 INVALID_STATE',
    action           varchar(16) not null comment 'PUBLISH、DEPRECATE、DISABLE 或 REENABLE',
    payload_digest   char(64)    not null comment '首次审计回执实际快照 SHA-256',
    receipt_snapshot longtext    not null comment 'tool-release-receipt v1：实际操作者、原因、审核证据及原始决定，不可变',
    recorded_micros  bigint      not null comment '首次决定的 UTC Unix 微秒，重放不改写',
    index            ix_tool_release_history (tool_key, command_key)
) engine=InnoDB comment 'AI TOOL 管理命令防重与不可变变更审计回执表';
