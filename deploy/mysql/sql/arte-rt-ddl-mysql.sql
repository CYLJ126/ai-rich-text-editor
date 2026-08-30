-- auto-generated definition
DROP PROCEDURE IF EXISTS add_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE add_index_if_not_exists(
    IN p_table_name VARCHAR(64),
    IN p_index_name VARCHAR(64),
    IN p_index_ddl TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table_name
          AND index_name = p_index_name
    ) THEN
        SET @sql = p_index_ddl;
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

create table if not exists arte_rt_article
(
    id           bigint auto_increment comment 'ID'
        primary key,
    author       varchar(255)                         null comment '作者',
    title        varchar(255)                         not null comment '标题',
    summary      text                                 null comment '摘要',
    cover        varchar(512)                         null comment '文章封面图片URL',
    catalog_id   int                                  null comment '所属目录ID',
    content_json json                                 null comment '核心存储，Tiptap JSON内容',
    content_md   mediumtext                           null comment '辅助字段，markdown 纯文本',
    content_text mediumtext                           null comment '辅助字段，用于ES同步的纯文本',
    is_delete    tinyint(1) default 0                 null,
    is_public    tinyint(1) default 0                 not null comment '是否公开 0-私有 1-公共',
    create_by    varchar(32)                          not null comment '创建人id',
    update_by    varchar(32)                          not null comment '更新人id',
    create_time  timestamp  default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  timestamp  default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    word_count   int                                  null comment '字数',
    order_id     smallint   default 0                 not null comment '目录内排序序号',
    access_level varchar(16)                          null comment '访问权限级别',
    article_type varchar(16)                          null comment '文章类型',
    row_version  int        default 1                 not null comment '版本号（乐观锁）'
)
    comment '富文本文章表';

CALL add_index_if_not_exists(
    'arte_rt_article',
    'idx_article_catalog_id',
    'create index idx_article_catalog_id on arte_rt_article (catalog_id)'
);

CALL add_index_if_not_exists(
    'arte_rt_article',
    'idx_catalog_creator_order',
    'create index idx_catalog_creator_order on arte_rt_article (catalog_id, create_by, order_id)'
);

CALL add_index_if_not_exists(
    'arte_rt_article',
    'idx_content_text',
    'create fulltext index idx_content_text on arte_rt_article (content_text)'
);

create table if not exists arte_rt_article_history
(
    id            bigint auto_increment comment 'ID' primary key,
    article_id    bigint       not null comment '文章ID',
    version_no    int          not null comment '版本号',
    title         varchar(255) not null comment '版本标题',
    content       mediumtext   null comment '版本正文',
    modified_by   varchar(32)  not null comment '修改人',
    modified_time datetime     not null comment '修改时间',
    constraint uk_article_history_version unique (article_id, version_no)
)
    comment '文章历史版本表';

CALL add_index_if_not_exists(
    'arte_rt_article_history',
    'idx_article_history_article_time',
    'create index idx_article_history_article_time on arte_rt_article_history (article_id, modified_time)'
);

create table if not exists arte_rt_catalog
(
    id          int auto_increment comment 'ID'
        primary key,
    name        varchar(64)                          not null comment '目录名称',
    father_id   int                                  null comment '父目录ID，无则为顶级目录（文集）',
    order_id    smallint   default 0                 not null comment '同级排序序号',
    description varchar(256)                         null comment '描述',
    create_by   varchar(32)                          not null comment '创建人id',
    update_by   varchar(32)                          not null comment '更新人id',
    create_time timestamp  default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp  default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    is_public   tinyint(1) default 0                 not null comment '是否公开 0-私有 1-公共',
    is_delete   tinyint(1) default 0                 null comment '是否已逻辑删除：0-否；1-是；'
)
    comment '富文本目录表';

CALL add_index_if_not_exists(
    'arte_rt_catalog',
    'idx_catalog_father_id',
    'create index idx_catalog_father_id on arte_rt_catalog (father_id)'
);

create table if not exists arte_rt_section_summary
(
    id           bigint auto_increment
        primary key,
    article_id   bigint                              not null,
    heading_id   varchar(64)                         null comment 'Tiptap heading 节点 ID',
    heading_text varchar(500)                        null comment '章节标题',
    summary      text                                null comment '章节摘要（LLM 生成）',
    embedding_id varchar(64)                         null comment '对应 ES 中的 chunk ID',
    level        int                                 null comment 'heading level',
    order_idx    int                                 null,
    create_by    varchar(32)                         not null comment '创建人id',
    update_by    varchar(32)                         not null comment '更新人id',
    create_time  timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间'
)
    comment '章节摘要表';

CALL add_index_if_not_exists(
    'arte_rt_section_summary',
    'idx_rt_summary_article_id',
    'create index idx_rt_summary_article_id on arte_rt_section_summary (article_id)'
);

-- auto-generated definition
create table if not exists arte_rt_share
(
    id                 bigint auto_increment comment '主键ID'
        primary key,
    resource_type      varchar(16)                           not null comment '资源类型：CATALOG / ARTICLE',
    resource_id        bigint                                not null comment '目录ID或文章ID',
    target_type        varchar(16) default 'USER'            not null comment '分享目标类型：USER / ROLE',
    target_user        varchar(32)                           null comment '被分享目标用户名',
    target_role        varchar(32)                           null comment '被分享目标角色编码',
    permission         varchar(16) default 'READ'            not null comment '权限：READ / READ_WRITE',
    article_permission varchar(16)                           null comment '目录分享下已有文章权限：READ/COMMENT/READ_WRITE/FULL_CONTROL',
    create_by          varchar(32)                           not null comment '分享创建人',
    create_time        timestamp   default CURRENT_TIMESTAMP not null comment '分享时间',
    constraint uk_share_role
        unique (resource_type, resource_id, target_type, target_role),
    constraint uk_share_user
        unique (resource_type, resource_id, target_type, target_user)
)
    comment '富文本分享关系表';

CALL add_index_if_not_exists(
    'arte_rt_share',
    'idx_share_resource',
    'create index idx_share_resource on arte_rt_share (resource_type, resource_id)'
);

CALL add_index_if_not_exists(
    'arte_rt_share',
    'idx_share_target_role',
    'create index idx_share_target_role on arte_rt_share (target_role)'
);

CALL add_index_if_not_exists(
    'arte_rt_share',
    'idx_share_target_user',
    'create index idx_share_target_user on arte_rt_share (target_user)'
);

create table if not exists arte_rt_article_comment_thread
(
    id          bigint auto_increment comment '主键'
        primary key,
    article_id  bigint                               not null comment '文章ID',
    thread_id   varchar(80)                          not null comment '批注线程ID，对应文章JSON中的comments mark attrs.threadId',
    resolved_at datetime                             null comment '解决时间，非空表示已解决',
    is_delete   tinyint(1) default 0                 not null comment '是否删除',
    create_by   varchar(64)                          not null comment '创建人',
    update_by   varchar(64)                          not null comment '更新人',
    create_time datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP not null comment '更新时间',
    constraint uk_arte_rt_comment_thread
        unique (article_id, thread_id)
)
    comment '文章批注线程';

CALL add_index_if_not_exists(
    'arte_rt_article_comment_thread',
    'idx_arte_rt_comment_thread_article',
    'create index idx_arte_rt_comment_thread_article on arte_rt_article_comment_thread (article_id)'
);

create table if not exists arte_rt_article_comment
(
    id          bigint auto_increment comment '主键'
        primary key,
    article_id  bigint                               not null comment '文章ID',
    thread_id   varchar(80)                          not null comment '批注线程ID',
    comment_id  varchar(80)                          not null comment '评论ID',
    content     text                                 not null comment '评论内容',
    deleted_at  datetime                             null comment '删除时间，非空表示已删除',
    is_delete   tinyint(1) default 0                 not null comment '是否删除',
    create_by   varchar(64)                          not null comment '创建人',
    update_by   varchar(64)                          not null comment '更新人',
    create_time datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP not null comment '更新时间',
    constraint uk_arte_rt_comment
        unique (article_id, comment_id)
)
    comment '文章批注评论';

CALL add_index_if_not_exists(
    'arte_rt_article_comment',
    'idx_arte_rt_comment_thread',
    'create index idx_arte_rt_comment_thread on arte_rt_article_comment (article_id, thread_id)'
);

DROP PROCEDURE IF EXISTS add_index_if_not_exists;
