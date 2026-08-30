create table arte_ai_assistant
(
    id                 int auto_increment comment '主键'
        primary key,
    name               varchar(100)  not null comment '助手名称',
    avatar             varchar(32) null comment '头像 icon',
    system_prompt      longtext null comment '系统提示词',
    context_strategy   varchar(32) null comment '上下文策略：window；token；summary；full；',
    context_window     int null comment '上下文窗口',
    model_id           int null comment '模型 ID',
    reasoning_effort   varchar(16) null comment '推理力度：minimal；low；medium；high；',
    text_type          varchar(16) null comment '文本类型：raw；markdown；json；html；plain；',
    temperature        decimal(3, 2) null comment '温度参数，0 ~ 2.0',
    max_tokens         int null comment '最大生成 token 数',
    top_p              decimal(3, 2) null comment 'Top P 参数，0 ~ 1.0',
    top_k              int null comment 'Top K 参数：0 ~ 100',
    presence_penalty   decimal(3, 2) null comment '存在惩罚参数，-2.0 ~ 2.0',
    frequency_penalty  decimal(3, 2) null comment '频率惩罚参数，-2.0 ~ 2.0',
    global_memory_flag tinyint(1)           null comment '是否开启全局记忆功能',
    knowledge_base_id  varchar(32) null comment '关联知识库 ID',
    extra_param        json null comment '额外参数',
    sort_order         int default 0 not null comment '排序',
    status             tinyint(1) default 1 not null comment '状态: 1-启用；3-禁用；',
    pin_flag           tinyint(1) default 0 not null comment '是否置顶',
    query_rewrite_flag tinyint(1) default 0 null comment '是否开启查询重写功能',
    default_flag tinyint(1) default 0 not null comment '是否默认助手：0-否；1-是；',
    description        varchar(512) null comment '助手描述',
    create_by          varchar(64) null comment '创建人',
    update_by          varchar(64) null comment '更新人',
    create_time        datetime null comment '创建时间',
    update_time        datetime null comment '更新时间'
) comment 'AI 助手表';

create index idx_arte_ai_assistant_main
    on arte_ai_assistant (create_by, status, name(50), update_time);

create table arte_ai_conversation
(
    id                  int auto_increment comment '主键'
        primary key,
    conv_id             varchar(64)                           not null comment '会话业务ID(UUID)',
    title               varchar(200) null comment '会话标题',
    assistant_id        int null comment '关联助手ID',
    model_id            int null comment '当前使用模型',
    extra_param         json null comment '当前会话模型参数（覆盖助手默认）',
    system_prompt       text null comment '当前会话系统提示词（覆盖助手默认）',
    knowledge_base_id   varchar(32) null comment '关联知识库 ID',
    scene varchar(30) default 'chat_management' not null comment '场景: chat_management/basic_writing',
    interaction_type    varchar(30) default 'BACKEND'         not null comment '交互类型: FRONTEND/BACKEND',
    status              varchar(20) default 'ACTIVE'          not null comment '状态: ACTIVE/ARCHIVED/DELETED',
    pin_flag            tinyint(1)  default 0                 not null comment '是否置顶',
    query_rewrite_flag  tinyint(1)  default 0                 null comment '是否开启查询重写功能',
    last_message_id     varchar(64) null comment '最后一条消息 ID',
    last_message_at     datetime null comment '最后消息时间',
    last_message_digest varchar(500) null comment '最后消息摘要',
    message_count       int         default 0                 not null comment '消息总数',
    context_strategy    varchar(30) null comment '上下文策略：window；token；summary；full；',
    context_window      int null comment '滑动窗口时的上下文数，或 token 数，具体取决于 contextStrategy',
    reasoning_effort    varchar(16) null comment '推理力度：minimal；low；medium；high；',
    text_type           varchar(16) null comment '文本类型：raw；markdown；json；html；plain；',
    temperature         decimal(3, 2) null comment '温度参数，0 ~ 2.0',
    max_tokens          int null comment '最大生成 token 数',
    top_p               decimal(3, 2) null comment 'Top P 参数，0 ~ 1.0',
    top_k               int null comment 'Top K 参数：0 ~ 100',
    presence_penalty    decimal(3, 2) null comment '存在惩罚参数，-2.0 ~ 2.0',
    frequency_penalty   decimal(3, 2) null comment '频率惩罚参数，-2.0 ~ 2.0',
    global_memory_flag  tinyint(1)                            null comment '是否开启全局记忆功能',
    default_flag tinyint(1)  default 0                 not null comment '是否默认（临时）会话：0-否；1-是；',
    create_by           varchar(64) null comment '创建人',
    update_by           varchar(64) null comment '更新人',
    create_time         datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time         datetime    default CURRENT_TIMESTAMP not null comment '更新时间',
    constraint uk_conv_id
        unique (conv_id)
) comment 'AI 会话表';

create index idx_create_by_status_time
    on arte_ai_conversation (create_by asc, status asc, last_message_at desc);

create table arte_ai_conversation_memory
(
    id            bigint auto_increment
        primary key,
    session_id    varchar(64)                         not null comment '会话 ID',
    user_id       bigint null,
    memory_json   json null comment '记忆 JSON: {intent, confirmed_facts, entities, history_summary}',
    message_count int default 0 null,
    last_active   datetime null,
    create_by     varchar(32)                         not null comment '创建人id',
    update_by     varchar(32)                         not null comment '更新人id',
    create_time   timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint ai_conversation_session
        unique (session_id)
) comment '对话记忆表';

create index idx_ai_conversation_user_id
    on arte_ai_conversation_memory (user_id);

create table arte_ai_conversation_summary
(
    id                    bigint auto_increment comment '主键'
        primary key,
    conv_id               varchar(64)   not null comment '会话ID',
    summary_content       longtext      not null comment '摘要内容',
    start_message_id      varchar(64) null comment '摘要起始消息ID',
    end_message_id        varchar(64) null comment '摘要结束消息ID',
    covered_message_count int null comment '覆盖消息数',
    tokens_before         int null comment '压缩前token数',
    tokens_after          int null comment '压缩后token数',
    row_version           int default 1 not null comment '摘要版本',
    create_by             varchar(64) null comment '创建人',
    update_by             varchar(64) null comment '更新人',
    create_time           datetime null comment '创建时间',
    update_time           datetime null comment '更新时间'
) comment '会话摘要压缩表';

create index idx_conv_id
    on arte_ai_conversation_summary (conv_id);

create table arte_ai_message
(
    id                int auto_increment comment '主键'
        primary key,
    message_id        varchar(64)                           not null comment '消息业务ID(UUID)',
    conv_id           varchar(64)                           not null comment '会话业务ID',
    parent_message_id varchar(64) null comment '父消息ID(分支)',
    branch_id         varchar(64) null comment '分支ID',
    branch_index      int         default 0                 not null comment '同级分支序号',
    role              varchar(20)                           not null comment '角色: user/assistant/system/tool',
    content           longtext null comment '消息文本内容',
    optimized_content longtext null comment '优化后的内容',
    text_type         varchar(30) default 'TEXT' not null comment '内容类型: TEXT/MULTIMODAL',
    model_id          int null comment '生成此消息的模型 ID',
    model_param       json null comment '调用实际参数',
    reasoning_content longtext null comment '思考内容',
    tool_calls        json null comment '工具调用信息',
    quoted_message_id varchar(64) null comment '引用消息ID',
    quoted_snapshot   text null comment '引用消息快照(防历史变更)',
    status            varchar(20) default 'completed'       not null comment '状态: pending/streaming/completed/failed/stopped',
    like_status       tinyint     default 0                 not null comment '点赞状态: 1点赞/-1点踩/0无',
    delete_flag       tinyint(1)  default 0                 not null comment '是否逻辑删除',
    finish_reason     varchar(50) null comment '结束原因: stop/length/tool_calls/content_filter',
    prompt_token      int null comment 'Prompt Token 数',
    completion_token  int null comment 'Completion Token 数',
    total_token       int null comment 'Total Token 数',
    reasoning_token   int null comment '推理 Token 数',
    latency_ms        int null comment '响应延迟（ms）',
    first_token_ms    int null comment '首Token延迟(ms)',
    prompt_cost       decimal(10, 6) null comment '输入成本',
    completion_cost   decimal(10, 6) null comment '输出成本',
    currency          varchar(20) null comment '币种',
    error_code        varchar(50) null comment '错误码',
    error_message     text null comment '错误信息',
    retry_count       int         default 0                 not null comment '重试次数',
    request_id        varchar(100) null comment '模型侧请求ID',
    create_by         varchar(64) null comment '创建人',
    update_by         varchar(64) null comment '更新人',
    create_time       datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time       datetime    default CURRENT_TIMESTAMP not null comment '更新时间',
    constraint uk_message_id
        unique (message_id)
) comment 'AI 消息表';

create index idx_branch_id
    on arte_ai_message (branch_id);

create index idx_conv_id
    on arte_ai_message (conv_id, create_time);

create index idx_create_info
    on arte_ai_message (create_by, create_time);

create index idx_parent_message_id
    on arte_ai_message (parent_message_id);

create table arte_ai_message_attachment
(
    id            bigint auto_increment comment '主键'
        primary key,
    message_id    varchar(64)                           not null comment '消息业务 ID',
    conv_id       varchar(64)                           not null comment '会话 ID',
    file_name     varchar(255) null comment '原始文件名',
    file_size     bigint null comment '文件大小（bytes）',
    file_type     varchar(50) null comment '文件 MIME 类型',
    attach_type   varchar(20)                           not null comment '附件类型：IMAGE/FILE/AUDIO/VIDEO',
    storage_path  varchar(500) null comment '存储路径',
    access_url    varchar(500) null comment '访问 URL',
    thumbnail_url varchar(500) null comment '缩略图 URL',
    status        varchar(20) default 'UPLOADED'        not null comment '状态',
    create_by     varchar(64) null comment '创建人',
    update_by     varchar(64) null comment '更新人',
    create_time   datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime    default CURRENT_TIMESTAMP not null comment '更新时间'
) comment '消息附件表';

create index idx_conv_id
    on arte_ai_message_attachment (conv_id);

create index idx_create_by
    on arte_ai_message_attachment (create_by);

create index idx_message_id
    on arte_ai_message_attachment (message_id);

create table arte_ai_message_raw_log
(
    id            int auto_increment comment '主键 ID'
        primary key,
    trace_id      varchar(64) null comment '链路追踪 ID',
    conv_id       varchar(64) null comment '会话 ID',
    message_id    varchar(64) null comment '消息 ID',
    provider      varchar(100) null comment '模型提供商',
    model_id      varchar(100) null comment '模型ID',
    status_code   smallint null comment 'HTTP 状态码',
    request_body  longtext null comment '原始请求体（JSON）',
    response_body longtext null comment '原始响应体（JSON）',
    create_time   datetime default CURRENT_TIMESTAMP null comment '创建时间'
) comment 'AI 消息原始请求/响应日志表' collate = utf8mb4_unicode_ci;

create index idx_conv_msg
    on arte_ai_message_raw_log (conv_id, message_id);

create index idx_create_time
    on arte_ai_message_raw_log (create_time);

create index idx_provider_model
    on arte_ai_message_raw_log (provider, model_id);

create index idx_trace_id
    on arte_ai_message_raw_log (trace_id);

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
    description         varchar(512) null comment '描述',
    create_by           varchar(64) null comment '创建人',
    update_by           varchar(64) null comment '更新人',
    create_time         datetime null comment '创建时间',
    update_time         datetime null comment '更新时间',
    constraint uk_model_id
        unique (provider, model_id, create_by)
) comment 'AI 模型配置表';

create
fulltext index ft_model_search
    on arte_ai_model_config (model_name, model_id);

create index idx_model_type
    on arte_ai_model_config (model_type);

create index idx_provider_type_status
    on arte_ai_model_config (provider, model_type, status);


create table arte_ai_prompt_template
(
    id          bigint auto_increment comment 'ID'
        primary key,
    name        varchar(100)                        not null comment '模板名称（唯一键）',
    stage       varchar(64)                         not null comment '阶段: query_rewrite/sub_question/answer_gen/critique/rerank 等',
    template    text                                not null comment 'Prompt 模板，支持{variable}占位符',
    variables   json null comment '变量说明',
    description varchar(500) null comment '描述',
    is_active   tinyint(1) default 1                 null,
    row_version int       default 1 null comment '版本号，乐观锁',
    create_by   varchar(32)                         not null comment '创建人id',
    update_by   varchar(32)                         not null comment '更新人id',
    create_time timestamp default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time timestamp default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_name
        unique (name)
) comment '提示词模板表';

create index idx_ai_prompt_stage
    on arte_ai_prompt_template (stage);

create table arte_ai_token_usage
(
    id               bigint auto_increment comment '主键'
        primary key,
    usage_date       date          not null comment '统计日期',
    user_name        varchar(64)   not null comment '用户',
    model_id         int           not null comment '模型 ID',
    conv_id     varchar(64) null comment '会话 ID',
    message_id  varchar(64) null comment '消息 ID',
    input_tokens     int default 0 not null comment '总输入 Tokens',
    output_tokens    int default 0 not null comment '总输出 Tokens',
    reasoning_tokens int default 0 not null comment '总思考 Tokens',
    total_tokens     int default 0 not null comment '总 Tokens',
    create_by   varchar(64) null comment '创建人',
    create_time datetime null comment '创建时间',
    update_time datetime null comment '更新时间'
) comment 'Token 用量统计表';

create index idx_model_date
    on arte_ai_token_usage (model_id, usage_date);

create index idx_user_date
    on arte_ai_token_usage (user_name, usage_date);

