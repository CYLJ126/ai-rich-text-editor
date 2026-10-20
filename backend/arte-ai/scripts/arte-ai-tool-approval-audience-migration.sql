-- S3-02：已有 S3-01 库停写备份后执行一次，再执行同目录 audience-backfill-mysql.sql。
-- 先运行 arte-ai-tool-approval-schema-check-mysql.sql；audience 建表成功不证明同名 approval 已是 S3-01 结构。
-- 全新库使用全量 DDL；此处不开放审批、不授予角色、不在运行时自动执行。
create table arte_ai_tool_approval_audience
(
    actor_key      char(64) not null comment '可见参与者租户、空间、稳定主体的长度前缀 SHA-256',
    invocation_key char(64) not null comment '审批所属原 Invocation ID 哈希',
    pending        int      not null default 1 comment '待办定位投影；唯一决定与该标记清理同事务，读取仍核验审批权威状态',
    primary key (actor_key, invocation_key),
    index          ix_tool_approval_actor_pending (actor_key,pending,invocation_key),
    index          ix_tool_approval_audience_inv (invocation_key),
    constraint fk_tool_approval_audience_inv foreign key (invocation_key) references arte_ai_invocation (id_key)
) engine=InnoDB comment '审批参与者定位索引，不替代当前成员、操作或资源授权';
