-- AI TOOL S1-04 增量：在现有 arte-ai-new-ddl-mysql.sql 基线上显式执行。
-- 仅新增不可变的提供者／Schema／定义版本；未包含发现、发布、绑定、审批或执行表。
-- 先停写并备份，核对三张目标表不存在后执行；不使用 IF NOT EXISTS 掩盖结构漂移。
-- arte_ai_lock 复用现有基线，运行时代码不执行 DDL。时间为 UTC Unix 微秒，不代表版本排序。

create table arte_ai_tool_provider
(
    id_key             char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key       char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key        char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    payload_digest     char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros     bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_provider_version unique (identity_key, version_key)
) engine=InnoDB comment 'AI TOOL 提供者不可变固定版本表';

create table arte_ai_tool_schema
(
    id_key             char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key       char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key        char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    payload_digest     char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros     bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_schema_version unique (identity_key, version_key)
) engine=InnoDB comment 'AI TOOL Schema不可变固定版本表';

create table arte_ai_tool_definition
(
    id_key                   char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key             char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key              char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    provider_key             char(64) not null comment '固定提供者版本主键',
    input_schema_key         char(64) not null comment '固定输入 Schema 版本主键',
    output_schema_key        char(64) not null comment '固定输出 Schema 版本主键',
    configuration_schema_key char(64) not null comment '固定配置 Schema 版本主键',
    payload_digest           char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot       longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros           bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_definition_version unique (identity_key, version_key),
    index                    ix_tool_definition_provider (provider_key, id_key),
    constraint fk_tool_definition_provider foreign key (provider_key) references arte_ai_tool_provider (id_key),
    constraint fk_tool_definition_input foreign key (input_schema_key) references arte_ai_tool_schema (id_key),
    constraint fk_tool_definition_output foreign key (output_schema_key) references arte_ai_tool_schema (id_key),
    constraint fk_tool_definition_config foreign key (configuration_schema_key) references arte_ai_tool_schema (id_key)
) engine=InnoDB comment 'AI TOOL 工具定义不可变固定版本表';
