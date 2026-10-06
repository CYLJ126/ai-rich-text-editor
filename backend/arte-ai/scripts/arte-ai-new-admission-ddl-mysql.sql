-- 第 1～3 步新增的持久化表；已有数据库只需显式执行本增量脚本。
-- 与 arte-ai-new-ddl-mysql.sql 中的三张新增表一致，不在应用启动时执行。

create table arte_ai_conversation_creation
(
    owner_key        char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    command_key      char(64) not null comment '创建会话操作幂等键哈希',
    request_digest   char(64) not null comment '创建参数的规范化摘要，不含新分配 ID 或时间',
    conversation_key char(64) not null comment '首次创建的会话 ID 哈希',
    primary key (owner_key, command_key)
) engine = InnoDB comment '新 AI 会话创建幂等记录表';

create table arte_ai_context_snapshot
(
    id_key         char(64) not null comment '归属及上下文快照 ID 组成的哈希'
        primary key,
    owner_key      char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    payload_digest char(64) not null comment '实际存储 JSON 字节的 SHA-256',
    snapshot       longtext not null comment 'schemaVersion=1 的不可变上下文快照'
) engine = InnoDB comment '新 AI 实际输入快照表';

create table arte_ai_result
(
    id_key         char(64) not null comment '归属、调用、尝试及结果防重键组成的哈希，同时作为 ResultRef ID'
        primary key,
    owner_key      char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    invocation_key char(64) not null comment '结果所属调用 ID 哈希',
    attempt_key    char(64) not null comment '结果所属尝试 ID 哈希',
    result_key     char(64) not null comment '同一尝试内的结果防重键哈希',
    result_type    varchar(64) not null comment '受信结果类型别名，不使用 Java 类名',
    schema_version int not null comment '结果字节编码版本',
    partial        int not null comment '是否部分结果：0-完整；1-部分或失败结果',
    payload_digest char(64) not null comment '实际存储 JSON 字节的 SHA-256',
    snapshot       longtext not null comment '不可变的专有结果类型信封',
    constraint uk_arte_ai_result_operation
        unique (owner_key, invocation_key, attempt_key, result_key)
) engine = InnoDB comment '新 AI 专有结果字节表';
