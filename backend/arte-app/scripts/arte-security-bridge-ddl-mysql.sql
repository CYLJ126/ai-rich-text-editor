-- 新安全接入层的增量表；不修改旧表，不自动执行，不赋予默认 AI／外发权限。
create table if not exists arte_security_member
(
    tenant_id    varchar(64) not null               comment '租户 ID',
    workspace_id varchar(64) not null               comment '工作空间 ID',
    user_id      int         not null               comment '现有账号主键',
    enabled      boolean     default false not null comment '是否启用，默认关闭',
    revision     bigint      default 1 not null     comment '记录修订版本',
    primary key (tenant_id, workspace_id, user_id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接工作空间成员表';

create table if not exists arte_security_resource
(
    resource_type varchar(32) not null               comment '资源类型',
    resource_id   varchar(64) not null               comment '资源业务 ID',
    tenant_id     varchar(64) not null               comment '租户 ID',
    workspace_id  varchar(64) not null               comment '工作空间 ID',
    enabled       boolean     default false not null comment '是否启用，默认关闭',
    revision      bigint      default 1 not null     comment '记录修订版本',
    primary key (resource_type, resource_id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接资源归属表';

create table if not exists arte_security_resource_grant
(
    resource_type varchar(32) not null               comment '资源类型',
    resource_id   varchar(64) not null               comment '资源业务 ID',
    user_id       int         not null               comment '现有账号主键',
    action_code   varchar(64) not null               comment '授权动作代码',
    enabled       boolean     default false not null comment '是否启用，默认关闭',
    revision      bigint      default 1 not null     comment '记录修订版本',
    primary key (resource_type, resource_id, user_id, action_code)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接用户资源动作授权表';

-- 应用与绑定分别启用；每项动作都须同时满足二者。当前暂不依赖新 AI 的实体表。
create table if not exists arte_security_application_policy
(
    tenant_id           varchar(64) not null               comment '租户 ID',
    workspace_id        varchar(64) not null               comment '工作空间 ID',
    application_id      varchar(64) not null               comment '应用业务 ID',
    binding_id          varchar(64) not null               comment '能力使用绑定 ID',
    action_code         varchar(64) not null               comment '授权动作代码',
    application_enabled boolean     default false not null comment '该动作的应用许可是否启用，默认关闭',
    binding_enabled     boolean     default false not null comment '该动作的绑定许可是否启用，默认关闭',
    revision            bigint      default 1 not null     comment '记录修订版本',
    primary key (tenant_id, workspace_id, application_id, binding_id, action_code)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接应用与绑定动作许可表';

create table if not exists arte_security_task
(
    context_key    char(64)     not null               comment '完整执行上下文的规范摘要',
    application_id varchar(64)  not null               comment '应用业务 ID',
    binding_id     varchar(64)  not null               comment '能力使用绑定 ID',
    enabled        boolean      default false not null comment '是否启用，默认关闭',
    valid_until    timestamp(6) not null               comment '有效期截止时间',
    revision       bigint       default 1 not null     comment '记录修订版本',
    primary key (context_key)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接执行任务登记表';

create table if not exists arte_security_task_action
(
    context_key   char(64)    not null               comment '完整执行上下文的规范摘要',
    executor_type varchar(16) not null               comment '执行主体类型：USER 或 SERVICE',
    executor_id   varchar(64) not null               comment '执行主体 ID',
    action_code   varchar(64) not null               comment '授权动作代码',
    enabled       boolean     default false not null comment '是否启用，默认关闭',
    revision      bigint      default 1 not null     comment '记录修订版本',
    primary key (context_key, executor_type, executor_id, action_code),
    constraint fk_security_task_action_task
    foreign key (context_key)
    references arte_security_task (context_key)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接任务执行主体动作许可表';

create table if not exists arte_security_service
(
    id       varchar(64) not null               comment '服务主体 ID',
    enabled  boolean     default false not null comment '是否启用，默认关闭',
    revision bigint      default 1 not null     comment '记录修订版本',
    primary key (id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接服务主体登记表';

create table if not exists arte_security_task_resource_action
(
    context_key  char(64)    not null               comment '完整执行上下文的规范摘要',
    resource_key char(64)    not null               comment '完整资源引用的规范摘要，包含版本、草稿及范围信息',
    action_code  varchar(64) not null               comment '授权动作代码',
    enabled      boolean     default false not null comment '是否启用，默认关闭',
    revision     bigint      default 1 not null     comment '记录修订版本',
    primary key (context_key, resource_key, action_code),
    constraint fk_security_task_resource_action_task
    foreign key (context_key)
    references arte_security_task (context_key)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接任务资源动作范围表';

create table if not exists arte_security_connection
(
    tenant_id      varchar(64)  not null               comment '租户 ID',
    workspace_id   varchar(64)  not null               comment '工作空间 ID',
    connection_key char(64)     not null               comment '固定连接引用的规范摘要',
    origin         varchar(512) not null               comment '获准访问的连接源站地址',
    enabled        boolean      default false not null comment '是否启用，默认关闭',
    revision       bigint       default 1 not null     comment '记录修订版本',
    primary key (tenant_id, workspace_id, connection_key)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接受控连接源站许可表';

create table if not exists arte_security_consent
(
    id             varchar(64)  not null               comment '外发同意记录 ID',
    request_digest char(64)     not null               comment '完整外发请求的 SHA-256 摘要，不含算法前缀',
    enabled        boolean      default false not null comment '是否启用，默认关闭',
    valid_until    timestamp(6) not null               comment '有效期截止时间',
    revision       bigint       default 1 not null     comment '记录修订版本',
    primary key (id)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接外发同意记录表';

-- 用途和连接独立限定；允许一次推理不自动开放训练、留存或其他用途。
create table if not exists arte_security_egress_rule
(
    tenant_id      varchar(64) not null               comment '租户 ID',
    workspace_id   varchar(64) not null               comment '工作空间 ID',
    application_id varchar(64) not null               comment '应用业务 ID',
    binding_id     varchar(64) not null               comment '能力使用绑定 ID',
    connection_key char(64)    not null               comment '固定连接引用的规范摘要',
    purpose        varchar(64) not null               comment '允许的外发用途',
    enabled        boolean     default false not null comment '是否启用，默认关闭',
    revision       bigint      default 1 not null     comment '记录修订版本',
    primary key (tenant_id, workspace_id, application_id, binding_id, connection_key, purpose)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '安全桥接外发用途许可表';
