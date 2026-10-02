-- 新执行支撑的增量表，不改旧审计／文件表，不自动执行。
create table if not exists arte_execution_audit
(
    event_id
    varchar
(
    128
) not null comment '审计事件 ID，用于耐久追加去重',
    scope_key char
(
    64
) not null comment '租户、工作空间及主体的作用域规范摘要',
    event_type varchar
(
    128
) not null comment '审计事件类型',
    payload blob not null comment '审计事件的规范编码内容',
    content_digest char
(
    71
) not null comment '内容 SHA-256 摘要，含 sha256: 前缀',
    recorded_at timestamp
(
    6
) not null comment '耐久存储记录时间',
    primary key
(
    event_id
)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate =utf8mb4_bin
    comment '公共执行审计事件表：耐久追加及内容校验';

create table if not exists arte_execution_artifact
(
    artifact_id
    char
(
    36
) not null comment '产物 UUID',
    scope_key char
(
    64
) not null comment '租户、工作空间及主体的作用域规范摘要',
    owner_ref blob not null comment '产物归属资源引用的编码快照',
    media_type varchar
(
    256
) not null comment '产物媒体类型',
    size_bytes bigint not null comment '产物实际字节数',
    content_digest char
(
    71
) not null comment '内容 SHA-256 摘要，含 sha256: 前缀',
    status varchar
(
    32
) not null comment '产物生命周期状态，包括隔离、验证、可用及删除',
    revision bigint not null comment '产物元数据乐观锁版本',
    created_at timestamp
(
    6
) not null comment '创建时间',
    expires_at timestamp
(
    6
) null comment '逻辑过期时间，不自动删除数据',
    primary key
(
    artifact_id
)
    )
    engine=InnoDB
    default charset=utf8mb4
    collate =utf8mb4_bin
    comment '公共执行产物元数据表：归属、校验、生命周期及有效期';
