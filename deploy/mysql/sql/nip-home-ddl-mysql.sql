create table if not exists nip_home_website_info
(
    id                   int auto_increment comment 'ID'
        primary key,
    name                 varchar(32)          not null comment '网站名',
    module               varchar(32)          null comment '模块',
    type                 varchar(16)          null comment '新闻类型',
    website_order        int                  null comment '顺序',
    website_status       tinyint              null comment '状态，1-显示；3-不显示',
    logo_url             varchar(256)         null comment '图标地址',
    module_url           varchar(256)         null comment '模块地址',
    request_type         varchar(8)           null comment '请求类型：如 POST、GET等',
    resolve_type         varchar(16)          null comment '解析类型，解析 json 还是 html',
    information_url      varchar(256)         null comment '资讯列表地址',
    formatter            varchar(256)         null comment '格式化模板，用于替换生成最终的新闻访问链接',
    data_path            varchar(256)         null comment '新闻列表json路径',
    field_mapping        varchar(128)         null comment '映射字段',
    condition_path       varchar(64)          null comment '条件值路径',
    condition_value      varchar(10)          null comment '条件路径下的值等于此值时才认为请求成功',
    request_headers      varchar(256)         null comment '请求头',
    logo_request_headers varchar(256)         null comment 'Logo 获取时要加的请求头',
    request_body         varchar(256)         null comment '请求体',
    proxy                tinyint(1) default 0 not null comment '是否需要代理'
)
    comment '资讯网站信息';


