create table arte_ai_model_config
(
    id                     int auto_increment comment '主键'
        primary key,
    provider            varchar(50)       not null comment '模型提供商: DEEPSEEK/QIANWEN/OPENROUTER/...',
    model_id            varchar(100)      not null comment '模型唯一标识',
    model_name          varchar(100)      not null comment '模型显示名称',
    model_type          varchar(20) null comment '模型类型: CHAT/EMBEDDING/IMAGE/AUDIO',
    api_key             varchar(500) null comment 'API Key（加密存储）',
    api_base_url        varchar(500) null comment 'API 基础 URL',
    api_version         varchar(50) null comment 'API 版本号',
    org_id              int null comment '组织 ID',
    default_param       json null comment '默认模型参数（JSON 格式，如温度、topP 等）',
    context_window      int null comment '上下文窗口大小（token 数）',
    max_tokens          int null comment '最大输出 token 数',
    support_vision         tinyint(1) default 0  not null comment '是否支持视觉：0-否；1-是',
    support_function       tinyint(1) default 0  not null comment '是否支持函数调用：0-否；1-是',
    support_thinking       tinyint(1) default 0  not null comment '是否支持深度思考：0-否；1-是',
    support_search         tinyint(1) default 0  not null comment '是否支持联网搜索：0-否；1-是',
    support_prompt_caching tinyint(1) default 0  not null comment '是否支持提示缓存',
    input_unit_price    decimal(20, 6) null comment '输入价格（元/千 tokens）',
    output_unit_price   decimal(20, 6) null comment '输出价格（元/千 tokens）',
    price_currency      varchar(16) null comment '货币单位（CNY/USD）',
    timeout_seconds     int     default 60 null comment '请求超时时间（秒）',
    max_retries         tinyint default 2 null comment '请求失败重试次数',
    proxy               varchar(128) null comment '代理，如 127.0.0.1:7897，不配则不走代理，配 127.0.0.1 开头走服务器代理',
    requests_per_minute int null comment '每分钟最大请求数 (RPM)',
    tokens_per_minute   int null comment '每分钟最大 Token 数 (TPM)',
    daily_request_limit int null comment '每日最大请求数',
    concurrency_limit   int null comment '并发请求数限制',
    icon                varchar(32) null comment '模型图标',
    status              tinyint(1) default 1  not null comment '状态: 1-启用；3-禁用',
    pin_flag            tinyint(1) default 0  not null comment '是否置顶',
    sort_order          int     default 0 not null comment '排序权重',
    default_flag        tinyint(1) default 0  not null comment '是否默认模型：0-否；1-是；',
    public_flag         tinyint(1) default 0 not null comment '是否服务端公共模型',
    description         varchar(512) null comment '描述',
    create_by           varchar(64) null comment '创建人',
    update_by           varchar(64) null comment '更新人',
    create_time         datetime null comment '创建时间',
    update_time         datetime null comment '更新时间',
    constraint uk_model_id
        unique (provider, model_id, create_by)
) comment 'AI 模型配置表';
create table arte_ai_model_access
(
    model_config_id int not null,
    subject_type varchar(8) not null comment 'user 或 role',
    subject varchar(64) not null comment '用户名或角色编码',
    primary key (model_config_id, subject_type, subject),
    constraint fk_model_access_config foreign key (model_config_id)
        references arte_ai_model_config (id) on delete cascade
) comment '公共模型授权；无授权记录时所有登录用户可用';
CREATE TABLE arte_rbac_user (name VARCHAR(64) PRIMARY KEY, status INT);
CREATE TABLE arte_rbac_role (role_code VARCHAR(64) PRIMARY KEY, role_name VARCHAR(64), status INT);
CREATE TABLE arte_rbac_relation (source VARCHAR(64), target VARCHAR(64), binding_type VARCHAR(32));
INSERT INTO arte_rbac_user VALUES ('owner', 1), ('alice', 1), ('bob', 1);
INSERT INTO arte_rbac_role VALUES ('reader', 'Reader', 1), ('disabled', 'Disabled', 3);
INSERT INTO arte_rbac_relation VALUES ('bob', 'reader', 'user_to_role'), ('eve', 'disabled', 'user_to_role');
