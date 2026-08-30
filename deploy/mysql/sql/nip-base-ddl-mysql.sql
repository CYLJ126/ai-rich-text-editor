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

create table if not exists nip_base_tag
(
    id          int auto_increment comment '主键'
        primary key,
    name        varchar(64)                         not null comment '标签名',
    status      varchar(8)                          null comment '状态',
    order_id    smallint                            null comment '顺序',
    description varchar(192)                        null comment '描述',
    father_id   int                                 null comment '父id，无则为顶级标签',
    create_by   varchar(32)                         not null comment '创建人id',
    update_by   varchar(32)                         not null comment '更新人id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null comment '更新时间'
)
    comment '标签表';

create table if not exists nip_base_tag_relation
(
    id        int auto_increment comment '主键'
        primary key,
    tag_type  varchar(32) not null comment '标签类型',
    tag_id    int         not null comment '标签 ID',
    source_id int         not null comment '源 ID',
    constraint nip_base_tag_relation_uk
        unique (source_id, tag_id)
)
    comment '标签关系表';

create table if not exists nip_base_summary
(
    id        int auto_increment comment '主键'
        primary key,
    type      varchar(16) not null comment '对应类型',
    target_id int         not null comment '目标 ID',
    content   text        not null comment '内容',
    constraint nip_base_summary_pk
        unique (target_id, type)
)
    comment '总结内容表';

CALL add_index_if_not_exists(
    'nip_base_summary',
    'content',
    'create fulltext index content on nip_base_summary (content)'
);

create table if not exists nip_dw_sticky
(
    id          int auto_increment comment '便笺id'
        primary key,
    title       varchar(60)                                    not null comment '标题',
    order_id    tinyint                                        null comment '顺序',
    fold_Flag   char                 default '1'               not null comment '折叠标记：0-折叠；1-展开；',
    show_type   varchar(16)                                    not null comment '表现形式',
    width       smallint                                       null comment '宽度',
    height      smallint                                       null comment '高度',
    content     text                                           null comment '内容',
    theme_color char(6)                                        null comment '标签主题色，默认为 #81d3f8',
    create_by   varchar(32)                                    not null comment '创建人id',
    update_by   varchar(32)                                    not null comment '更新人id',
    start_date  date                 default (curdate())       not null comment '开始日期',
    end_date    date                 default (curdate())       not null comment '结束日期',
    create_time timestamp            default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp            default CURRENT_TIMESTAMP not null comment '更新时间',
    status      enum ('1', '2', '3') default '1'               not null comment '状态，1-正常；2-完成；3-关闭；'
)
    comment '便笺表';

DROP PROCEDURE IF EXISTS add_index_if_not_exists;
