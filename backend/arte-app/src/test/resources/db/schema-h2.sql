create table arte_rbac_user
(
    id          int auto_increment comment '主键',
    name        varchar(64)                         not null comment '用户名',
    status      char                                not null comment '状态，0-初始（未激活）；1-正常；2-注销；',
    mobile      varchar(11)                         not null comment '手机号',
    email       varchar(32)                         not null comment '邮箱',
    password    varchar(255)                        not null comment '密码',
    description varchar(255) null comment '描述',
    create_by   varchar(32)                         not null comment '创建人id',
    update_by   varchar(32)                         not null comment '更新人id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null comment '更新时间',
    row_version smallint  default 0                 not null comment '版本号'
);