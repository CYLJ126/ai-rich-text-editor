-- AI TOOL S1-07 增量：在 S1-04 目录及 S1-06 发布表之后显式部署。
-- 停写、备份并核对四张目标表不存在；不修改已有聊天绑定，不自动建绑或启用 TOOL。
-- 固定解析材料不是 Invocation 快照；受理／派发仍须在 S2 接线并复核当前授权。

create table arte_ai_tool_binding
(
    binding_key    char(64)     not null comment '服务端稳定绑定 ID 的长度前缀 SHA-256' primary key,
    owner_key      char(64)     not null comment '真实租户、工作空间、主体的长度前缀 SHA-256',
    tool_id        varchar(256) not null comment '不可更换的稳定工具身份；固定工具版本在修订表中',
    row_version    bigint       not null comment '当前不可变 rN 修订的乐观版本，从 1 开始',
    payload_digest char(64)     not null comment '当前绑定白名单快照 SHA-256',
    snapshot       longtext     not null comment 'tool-binding v1：owner、名称、启停、当前版本及创建更新时间',
    index          ix_tool_binding_owner (owner_key, binding_key),
    constraint ck_tool_binding_version check (row_version > 0)
) engine=InnoDB comment 'AI TOOL 私有绑定稳定身份及当前修订指针';

create table arte_ai_tool_binding_revision
(
    artifact_key   char(64) not null comment 'binding/rN 固定修订引用摘要' primary key,
    binding_key    char(64) not null comment '稳定绑定身份摘要',
    revision_key   char(64) not null comment '与 artifact_key 相同的源修订键，读取时核对',
    tool_key       char(64) not null comment '固定工具基线引用摘要，不表示已解析的最新版本',
    payload_digest char(64) not null comment '不可变修订快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-revision v1：完整覆盖配置、选择规则、策略与 Secret 引用；不存凭据值',
    index          ix_tool_binding_revision (binding_key, artifact_key),
    constraint fk_tool_binding_revision_owner foreign key (binding_key) references arte_ai_tool_binding (binding_key),
    constraint fk_tool_binding_revision_tool foreign key (tool_key) references arte_ai_tool_definition (id_key)
) engine=InnoDB comment 'AI TOOL 不可变完整绑定修订';

create table arte_ai_tool_binding_resolution
(
    artifact_key   char(64) not null comment 'binding/p1-内容摘要固定引用的长度前缀 SHA-256' primary key,
    binding_key    char(64) not null comment '稳定绑定身份摘要',
    revision_key   char(64) not null comment '产生本材料的不可变 binding/rN 修订键',
    tool_key       char(64) not null comment '本次解析的实际固定工具版本摘要',
    payload_digest char(64) not null comment '不可变解析材料白名单快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-resolution v1：实际工具、合并配置、配置摘要和约束，不含调用参数及运行身份',
    index          ix_tool_binding_resolution (binding_key, artifact_key),
    constraint fk_tool_binding_resolution_owner foreign key (binding_key) references arte_ai_tool_binding (binding_key),
    constraint fk_tool_binding_resolution_revision foreign key (revision_key) references arte_ai_tool_binding_revision (artifact_key),
    constraint fk_tool_binding_resolution_tool foreign key (tool_key) references arte_ai_tool_definition (id_key)
) engine=InnoDB comment 'AI TOOL 内容寻址的固定解析材料';

create table arte_ai_tool_binding_command
(
    command_key    char(64) not null comment 'owner、实际主体类别及幂等键的长度前缀 SHA-256' primary key,
    owner_key      char(64) not null comment '可信个人归属摘要，读取时核对原始 owner',
    binding_key    char(64) not null comment '请求的稳定绑定身份摘要，允许记录不存在或越界拒绝',
    request_digest char(64) not null comment '完整原始命令语义摘要，集合先排序',
    payload_digest char(64) not null comment '原始审计回执白名单快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-receipt v1：原始命令、原因、实际操作者及成功或拒绝决定，不可变',
    index          ix_tool_binding_history (owner_key, binding_key, command_key)
) engine=InnoDB comment 'AI TOOL 个人绑定管理防重与不可变审计回执';
