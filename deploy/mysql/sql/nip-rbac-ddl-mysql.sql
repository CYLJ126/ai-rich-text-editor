-- auto-generated definition
create table if not exists nip_rbac_menu
(
    id          int auto_increment comment 'ID'
        primary key,
    menu_code   varchar(32)      not null comment '菜单编码',
    menu_name   varchar(64)      not null comment '菜单名称',
    icon        varchar(64)      null comment '菜单图标',
    menu_url    varchar(128)     not null comment '菜单请求地址',
    father_id   int              null comment '上级菜单 ID',
    order_id    tinyint          null comment '顺序',
    status      int              not null comment '状态，参考 StatusEnum，1-启用；3-停用',
    description varchar(128)     null comment '描述',
    show_flag   char default '1' not null comment '是否在菜单树展示：0-否，1-是',
    row_version int  default 0   not null comment '版本号',
    create_by   varchar(32)      null comment '创建人',
    create_time datetime         not null comment '创建时间',
    update_by   varchar(32)      null comment '更新人',
    update_time datetime         null comment '更新时间'
)
    comment '菜单表';

-- auto-generated definition
create table if not exists nip_rbac_menu_operation
(
    id             int auto_increment comment 'ID'
        primary key,
    menu_code      varchar(32)   not null comment '菜单代码',
    operation_code varchar(32)   not null comment '菜单操作代码',
    operation_name varchar(64)   not null comment '菜单操作名称',
    status         int           not null comment '状态，参考 StatusEnum，1-启用；3-停用',
    description    varchar(256)  null comment '描述',
    row_version    int default 0 not null comment '版本号',
    create_by      varchar(32)   null comment '创建人',
    create_time    datetime      not null comment '创建时间',
    update_by      varchar(32)   null comment '更新人',
    update_time    datetime      null comment '更新时间',
    constraint UK_rbac_menu_operation
        unique (menu_code, operation_code)
)
    comment '菜单操作表';

-- auto-generated definition
create table if not exists nip_rbac_relation
(
    id           int auto_increment comment 'ID'
        primary key,
    source       varchar(32) not null comment '源对象',
    target       varchar(32) not null comment '绑定对象',
    binding_type varchar(32) not null comment '见 RbacRelationEnum',
    create_by    varchar(32) null comment '创建人',
    create_time  datetime    not null comment '创建时间',
    constraint UK_rbac_relation
        unique (source, binding_type, target)
)
    comment 'RBAC 关系表';

-- auto-generated definition
create table if not exists nip_rbac_role
(
    id          int auto_increment comment '主键'
        primary key,
    role_code   varchar(32)                         not null comment '角色编码',
    role_name   varchar(64)                         not null comment '角色名',
    status      char                                not null comment '状态：0-初始（未激活）；1-正常；3-注销；',
    description varchar(255)                        null comment '描述',
    create_by   varchar(32)                         not null comment '创建人id',
    update_by   varchar(32)                         not null comment '更新人id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null comment '更新时间',
    row_version smallint  default 0                 not null comment '版本号',
    constraint uniq_role_code
        unique (role_code),
    constraint uniq_role_name
        unique (role_name)
)
    comment '角色表';

-- auto-generated definition
create table if not exists nip_rbac_user
(
    id          int auto_increment comment '主键'
        primary key,
    user_name   varchar(64)                         not null comment '用户名',
    status      char                                not null comment '状态：0-初始（未激活）；1-正常；3-注销；',
    mobile      varchar(11)                         not null comment '手机号',
    email       varchar(32)                         not null comment '邮箱',
    password    varchar(255)                        not null comment '密码',
    description varchar(255)                        null comment '描述',
    create_by   varchar(32)                         not null comment '创建人id',
    update_by   varchar(32)                         not null comment '更新人id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null comment '更新时间',
    row_version smallint  default 0                 not null comment '版本号',
    constraint uniq_user_email
        unique (email),
    constraint uniq_user_mobile
        unique (mobile),
    constraint uniq_user_name
        unique (user_name)
)
    comment '用户表';


