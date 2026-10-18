-- 新 AI 独立存储表：由部署流程显式执行，不在应用启动时执行。
-- 表名统一使用 arte_ai_ 前缀；连接及数据库时区统一 UTC，所有表使用 InnoDB。
-- 独立工厂只注册 SQL 诊断拦截器，不继承旧业务 MyBatis-Plus 插件。
-- 新存储会话表使用 arte_ai_conversation_new，与旧 arte_ai_conversation 表隔离。TODO：改造完后去掉 _new 后缀。
-- *_key 为 UTF-8 长度前缀编码的 SHA-256；workspace_snapshot 为 schemaVersion=1 的内部 JSON 快照。
-- 对应 Java 字段／Mapper 参数使用 workspaceSnapshot；快照内容随各表对象而定，不代表公共空间实体快照。

create table arte_ai_lock
(
    lock_key char(64) not null comment '互斥作用域哈希，仅用于数据库行锁，不保存业务数据'
        primary key
) engine = InnoDB comment '新 AI 分布式事务锁表';

create table arte_ai_invocation
(
    id_key         char(64) not null comment '逻辑调用 ID 哈希'
        primary key,
    owner_key      char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    workspace_snapshot longtext not null comment '逻辑调用权威快照，包含状态、版本及执行上下文',
    next_attempt   int      not null comment '最近已分配的尝试序号，初始为 0',
    next_fence     bigint   not null comment '最近已分配的 fencing token，初始为 0',
    next_sequence  bigint   not null comment '最近已提交的事件序号，初始为 0',
    retained_after bigint   not null comment '已裁剪事件的最大序号，初始为 0',
    written_bytes  bigint   not null comment '累计输出批次编码字节数，裁剪事件不减少该值'
) engine = InnoDB comment '新 AI 逻辑调用表';

create table arte_ai_acceptance
(
    scope_key       char(64) not null comment '归属及 capability ID 组成的幂等作用域哈希',
    idempotency_key char(64) not null comment '幂等键哈希',
    request_digest  char(64) not null comment '可信准入层生成的规范化请求摘要',
    invocation_key  char(64) not null comment '首次受理的逻辑调用 ID 哈希',
    primary key (scope_key, idempotency_key)
) engine = InnoDB comment '新 AI 受理幂等记录表';

create table arte_ai_attempt
(
    id_key             char(64)    not null comment '执行尝试 ID 哈希'
        primary key,
    invocation_key     char(64)    not null comment '所属逻辑调用 ID 哈希',
    attempt_number     int         not null comment '该调用内的尝试序号，从 1 开始递增',
    purpose            varchar(16) not null comment '租约用途：EXECUTE-执行；RECONCILE-核对',
    workspace_snapshot longtext    not null comment '执行尝试快照，包含版本、Worker、租约及 fencing token',
    constraint uk_arte_ai_attempt_number
        unique (invocation_key, attempt_number)
) engine = InnoDB comment '新 AI 执行尝试表';

create table arte_ai_event
(
    invocation_key char(64) not null comment '所属逻辑调用 ID 哈希',
    sequence_no    bigint   not null comment '调用内连续递增的事件序号，从 1 开始',
    workspace_snapshot longtext not null comment '已提交事件快照，包含有界输出批次、状态或终态',
    primary key (invocation_key, sequence_no)
) engine = InnoDB comment '新 AI 耐久执行事件表';

create table arte_ai_operation
(
    invocation_key  char(64) not null comment '所属逻辑调用 ID 哈希',
    operation_key   char(64) not null comment '操作类型及业务防重键组成的哈希',
    digest          char(64) not null comment '首次提交的操作内容摘要',
    first_sequence bigint not null comment '首次提交的首条事件序号',
    event_count    int    not null comment '首次提交的事件数量',
    result_snapshot longtext null comment '完成调用或控制回执快照，用于幂等重放',
    evidence_ref    varchar(256) null comment '可信远端核对证据引用',
    primary key (invocation_key, operation_key)
) engine = InnoDB comment '新 AI 追加及完成操作防重表';

create table arte_ai_outbox
(
    message_key    char(64)    not null comment '调用 ID、消息类型及事件序号组成的消息哈希'
        primary key,
    invocation_key char(64)    not null comment '所属逻辑调用 ID 哈希',
    invocation_id   varchar(256) not null comment '逻辑调用原始业务 ID',
    owner_tenant    varchar(256) not null comment '受理时的租户 ID',
    owner_workspace varchar(256) not null comment '受理时的工作空间 ID',
    owner_subject   varchar(256) not null comment '受理时的主体 ID',
    kind           varchar(16) not null comment '消息类型：DISPATCH-派发；EVENT-事件通知',
    sequence_no    bigint      not null comment '事件序号；派发消息为 0',
    worker_id       varchar(256) null comment '当前领取消息的 Worker ID',
    token          bigint      not null comment '消息租约 fencing token，初始为 0',
    lease_until    bigint      not null comment '租约截止时间，UTC Unix 毫秒，初始为 0',
    delivered      int         not null comment '是否已确认投递：0-否；1-是',
    constraint uk_arte_ai_outbox_event
        unique (invocation_key, kind, sequence_no)
) engine = InnoDB comment '新 AI 耐久派发及事件 Outbox 表';

create index idx_arte_ai_outbox_pending
    on arte_ai_outbox (kind, delivered, lease_until);

create table arte_ai_conversation_new
(
    id_key            char(64) not null comment '会话 ID 哈希'
        primary key,
    owner_key         char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    version_no        bigint   not null comment '会话版本，受理新调用时递增',
    active_invocation char(64) null comment '当前活跃调用 ID 哈希；已知终态释放，UNKNOWN 保留',
    workspace_snapshot longtext not null comment '会话权威快照'
) engine = InnoDB comment '新 AI 独立会话表';

create table arte_ai_turn
(
    id_key           char(64) not null comment '交流轮次 ID 哈希'
        primary key,
    conversation_key char(64) not null comment '所属会话 ID 哈希',
    sequence_no      bigint   not null comment '会话内连续递增的轮次序号，从 1 开始',
    workspace_snapshot longtext not null comment '轮次快照，包含固定用户输入、历史路径及调用候选',
    constraint uk_arte_ai_turn_sequence
        unique (conversation_key, sequence_no)
) engine = InnoDB comment '新 AI 交流轮次表';

create table arte_ai_account
(
    id_key    char(64) not null comment '预算账户引用哈希'
        primary key,
    owner_key char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    workspace_snapshot longtext not null comment '预算账户快照，包含币种、固定费率、限额、held、charged 及版本'
) engine = InnoDB comment '新 AI 预算账户表';

create table arte_ai_reservation
(
    id_key         char(64) not null comment '预算预留 ID 哈希'
        primary key,
    account_key    char(64) not null comment '所属预算账户引用哈希',
    invocation_key char(64) not null comment '所属逻辑调用 ID 哈希',
    attempt_key    char(64) not null comment '所属执行尝试 ID 哈希，每次尝试最多一个预留',
    workspace_snapshot longtext not null comment '预算预留快照，包含金额、费率、状态、版本及有效期',
    constraint uk_arte_ai_reservation_attempt
        unique (attempt_key)
) engine = InnoDB comment '新 AI 预算预留表';

create table arte_ai_settlement
(
    reservation_key    char(64)    not null comment '所属预算预留 ID 哈希',
    settlement_key     char(64)    not null comment '结算业务防重键哈希',
    digest             char(64)    not null comment '结算内容及证据组成的摘要',
    workspace_snapshot longtext    not null comment '首次提交的结算事实快照',
    result_snapshot    longtext    not null comment '该次结算首次提交的预留快照，用于幂等重放',
    evidence_kind      varchar(32) not null comment '证据类型：UNKNOWN_COST；PROVIDER_BILL；PROVEN_NOT_DISPATCHED',
    evidence_ref       varchar(256) null comment '可信账单或未发送证据引用',
    primary key (reservation_key, settlement_key)
) engine = InnoDB comment '新 AI 预算结算事实表';

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
    workspace_snapshot longtext not null comment 'schemaVersion=1 的不可变上下文快照'
) engine = InnoDB comment '新 AI 实际输入快照表';

create table arte_ai_result
(
    id_key             char(64)    not null comment '归属、调用、尝试及结果防重键组成的哈希，同时作为 ResultRef ID'
        primary key,
    owner_key          char(64)    not null comment '租户、工作空间、主体组成的归属哈希',
    invocation_key     char(64)    not null comment '结果所属调用 ID 哈希',
    attempt_key        char(64)    not null comment '结果所属尝试 ID 哈希',
    result_key         char(64)    not null comment '同一尝试内的结果防重键哈希',
    result_type        varchar(64) not null comment '受信结果类型别名，不使用 Java 类名',
    schema_version     int         not null comment '结果字节编码版本',
    partial            int         not null comment '是否部分结果：0-完整；1-部分或失败结果',
    payload_digest     char(64)    not null comment '实际存储 JSON 字节的 SHA-256',
    workspace_snapshot longtext    not null comment '不可变的专有结果类型信封',
    constraint uk_arte_ai_result_operation
        unique (owner_key, invocation_key, attempt_key, result_key)
) engine = InnoDB comment '新 AI 专有结果字节表';

-- 派发限流：并发 Permit 跟随 Outbox 租约，频率窗口使用数据库时钟；同数据库的所有实例共享。
create table arte_ai_dispatch_permit
(
    message_key char(64) not null comment '所属派发 Outbox 的消息哈希'
        primary key,
    token       bigint   not null comment '所属 Outbox 当前领取的 fencing token，用于续租及释放时防重',
    tenant_key  char(64) not null comment '租户 ID 哈希，跨工作空间及主体共享并发额度',
    user_key    char(64) not null comment '租户 ID 与主体 ID 组成的哈希，跨工作空间共享并发额度',
    model_key   char(64) not null comment '连接定义 ID 与远端模型标识组成的限流作用域哈希',
    lease_until bigint   not null comment '并发许可租约截止时间，UTC Unix 毫秒，与 Outbox 同事务续租'
) engine = InnoDB comment '新 AI 派发并发许可表';

-- 按租户统计尚未过期的并发许可。
create index idx_dispatch_tenant
    on arte_ai_dispatch_permit (tenant_key, lease_until);

-- 按租户及主体统计尚未过期的并发许可。
create index idx_dispatch_user
    on arte_ai_dispatch_permit (user_key, lease_until);

-- 按连接及模型统计尚未过期的并发许可。
create index idx_dispatch_model
    on arte_ai_dispatch_permit (model_key, lease_until);

-- 按租约截止时间回收过期并发许可，并统计全局有效许可。
create index idx_dispatch_expiry
    on arte_ai_dispatch_permit (lease_until);

create table arte_ai_dispatch_rate
(
    bucket_key char(64) not null comment '限流维度与作用域组成的频率桶哈希：global；tenant；user；model'
        primary key,
    starts_at  bigint   not null comment '当前固定频率窗口的起始时间，UTC Unix 毫秒，使用数据库时钟',
    requests   int      not null comment '当前固定窗口内已获派发许可的尝试数，限流拒绝不计数'
) engine = InnoDB comment '新 AI 派发请求频率窗口表';

-- 会话目录 V1：MySQL 8 / InnoDB；显式部署，不在启动时执行。
create table arte_ai_conversation_metadata
(
    id_key                char(64)                                               not null comment '会话 ID 哈希，与会话权威表的 id_key 一致' primary key,
    owner_key             char(64)                                               not null comment '租户、工作空间、主体组成的归属哈希',
    conversation_id       varchar(256) character set utf8mb4 collate utf8mb4_bin not null comment '会话原始业务 ID，使用二进制排序规则作为游标排序的次级键',
    title                 mediumtext                                             not null comment '当前会话标题，支持自动命名及人工修改',
    active                boolean                                                not null comment '会话是否活跃：0-否；1-是，仅活跃会话进入列表',
    metadata_version      bigint                                                 not null comment '标题或置顶状态变更时递增的元数据版本，初始为 0，与会话执行版本独立',
    pinned                boolean                                                not null comment '是否置顶：0-否；1-是，同一归属最多置顶 7 个会话',
    title_manually_edited boolean                                                not null comment '是否保护标题免于自动改名：0-否；1-是，人工改名及旧会话初始化时置为 1',
    auto_title_resolved   boolean                                                not null comment '是否已结束首次用户消息自动命名：0-待处理；1-已结束，旧会话初始化时置为 1',
    preview_text          mediumtext null comment '最近用户轮次的输入摘要，按 30 个扩展字素簇截断，超长追加省略号，无摘要时为 NULL',
    preview_sequence      bigint                                                 not null default 0 comment '当前预览对应的轮次序号，初始为 0，防止历史回填覆盖较新预览',
    user_message_count    bigint null comment '按消息身份去重后的用户消息数，空会话为 0，历史统计尚未完成时为 NULL',
    last_message_micros   bigint null comment '最近消息生成时间，UTC Unix 微秒，无消息或历史生成时间无法完整恢复时为 NULL',
    sort_micros           bigint                                                 not null comment '列表活动排序时间，UTC Unix 微秒，按降序读取，无可用消息时间时使用会话创建时间',
    created_micros        bigint                                                 not null comment '会话创建时间，UTC Unix 微秒',
    statistics_ready      boolean                                                not null comment '消息统计是否完整可用：0-待回填或历史数据不完整；1-完整可用',
    index                 idx_ai_conversation_list (owner_key,pinned,active,sort_micros desc,conversation_id desc)
) engine=InnoDB comment '新 AI 会话列表元数据及消息统计表';

create table arte_ai_conversation_metadata_command
(
    owner_key          char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    command_key        char(64) not null comment '元数据修改命令幂等键哈希，改名与置顶操作共用同一归属下的键空间',
    request_digest     char(64) not null comment '会话 ID、预期元数据版本、操作类型及目标值组成的规范化请求摘要',
    workspace_snapshot longtext not null comment '首次提交的命令结果快照，包含结果码及可选成功回执，用于幂等重放',
    primary key (owner_key, command_key)
) engine=InnoDB comment '新 AI 会话元数据修改命令幂等回执表';

-- 只保存消息身份与时间，不存消息正文；用户按 messageId、助手按 invocation/attempt 去重。
create table arte_ai_conversation_message_fact
(
    conversation_key char(64)    not null comment '所属会话 ID 哈希',
    fact_key         char(64)    not null comment '消息角色及身份组成的去重哈希，用户按消息 ID、助手按调用 ID 与尝试 ID 去重',
    message_role     varchar(16) not null comment '消息角色：USER-用户；ASSISTANT-助手',
    generated_micros bigint      not null comment '消息生成时间，UTC Unix 微秒，用户取轮次创建时间，助手取首次可见文本事件时间',
    primary key (conversation_key, fact_key)
) engine=InnoDB comment '新 AI 会话消息身份及生成时间去重事实表';

-- 可恢复的有界离线回填任务；不在用户列表请求中遍历历史。
create table arte_ai_conversation_projection_job
(
    id_key           char(64) not null comment '待回填会话 ID 哈希，每个会话最多一个任务' primary key,
    turn_sequence    bigint   not null comment '已完成回填的轮次序号，初始为 0，后续从更大序号的轮次继续',
    invocation_index int      not null comment '当前待回填轮次内的调用列表索引，从 0 开始，轮次完成后重置为 0',
    event_sequence   bigint   not null comment '当前调用已扫描的事件序号，初始为 0，切换调用或轮次后重置为 0',
    incomplete       boolean  not null comment '历史统计是否存在缺失：0-未发现；1-调用缺失或事件已裁剪，生成时间无法完整恢复'
) engine=InnoDB comment '新 AI 会话历史消息统计可恢复回填任务表';

-- AI TOOL S1-04：与 arte-ai-tool-catalog-migration.sql 的建表段保持一致。
create table arte_ai_tool_provider
(
    id_key             char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key       char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key        char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    payload_digest     char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros     bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_provider_version unique (identity_key, version_key)
) engine=InnoDB comment 'AI TOOL 提供者不可变固定版本表';

create table arte_ai_tool_schema
(
    id_key             char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key       char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key        char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    payload_digest     char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros     bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_schema_version unique (identity_key, version_key)
) engine=InnoDB comment 'AI TOOL Schema不可变固定版本表';

create table arte_ai_tool_definition
(
    id_key                   char(64) not null comment '固定类型、ID、版本的长度前缀 SHA-256' primary key,
    identity_key             char(64) not null comment '类型与稳定 ID 的 SHA-256，不受数据库文本大小写规则影响',
    version_key              char(64) not null comment '精确版本标签的 SHA-256，不表示新旧关系',
    provider_key             char(64) not null comment '固定提供者版本主键',
    input_schema_key         char(64) not null comment '固定输入 Schema 版本主键',
    output_schema_key        char(64) not null comment '固定输出 Schema 版本主键',
    configuration_schema_key char(64) not null comment '固定配置 Schema 版本主键',
    payload_digest           char(64) not null comment '实际内部快照字节的 SHA-256，读取时重新验证',
    workspace_snapshot       longtext not null comment '白名单 schemaVersion=1 的不可变内部快照，不是公共工作空间数据',
    created_micros           bigint   not null comment '首次登记时的 UTC Unix 微秒，重放不改写',
    constraint uk_tool_definition_version unique (identity_key, version_key),
    index                    ix_tool_definition_provider (provider_key, id_key),
    constraint fk_tool_definition_provider foreign key (provider_key) references arte_ai_tool_provider (id_key),
    constraint fk_tool_definition_input foreign key (input_schema_key) references arte_ai_tool_schema (id_key),
    constraint fk_tool_definition_output foreign key (output_schema_key) references arte_ai_tool_schema (id_key),
    constraint fk_tool_definition_config foreign key (configuration_schema_key) references arte_ai_tool_schema (id_key)
) engine=InnoDB comment 'AI TOOL 工具定义不可变固定版本表';

-- AI TOOL S1-06：与 arte-ai-tool-release-migration.sql 的建表段保持一致。
create table arte_ai_tool_release
(
    tool_key            char(64)    not null comment '固定工具引用的长度前缀 SHA-256，与不可变定义表同键' primary key,
    compatible_from_key char(64) null comment '已发布兼容前驱固定工具键；首次发布冻结，不按标签排序',
    row_version         bigint      not null comment '治理乐观版本，从首次成功管理变更的 1 开始',
    state               varchar(16) not null comment 'PUBLISHED、DEPRECATED 或 DISABLED；无记录派生 DRAFT',
    payload_digest      char(64)    not null comment '实际白名单快照的 SHA-256，读取时核对',
    release_snapshot    longtext    not null comment 'tool-release v1 当前治理状态，首次发布材料及契约不可原地替换',
    updated_micros      bigint      not null comment '最近成功变更的 UTC Unix 微秒；重放和拒绝不推进',
    constraint uk_tool_release_successor unique (compatible_from_key),
    constraint fk_tool_release_definition foreign key (tool_key) references arte_ai_tool_definition (id_key),
    constraint fk_tool_release_predecessor foreign key (compatible_from_key) references arte_ai_tool_definition (id_key),
    constraint ck_tool_release_version check (row_version > 0),
    constraint ck_tool_release_state check (state in ('PUBLISHED', 'DEPRECATED', 'DISABLED'))
) engine=InnoDB comment 'AI TOOL 当前生命周期与固定发布契约表';

create table arte_ai_tool_release_command
(
    command_key      char(64)    not null comment '真实主体类别、主体及幂等键的长度前缀 SHA-256' primary key,
    actor_key        char(64)    not null comment '真实稳定主体类别与身份摘要，不使用用户名或执行归属',
    tool_key         char(64)    not null comment '请求的固定工具引用摘要；允许保存不存在工具的拒绝回执',
    request_digest   char(64)    not null comment '真实主体与完整命令语义摘要，不包含重算预检时间',
    outcome          varchar(32) not null comment '首次领域决定 APPLIED、NOT_FOUND、VERSION_CONFLICT 或 INVALID_STATE',
    action           varchar(16) not null comment 'PUBLISH、DEPRECATE、DISABLE 或 REENABLE',
    payload_digest   char(64)    not null comment '首次审计回执实际快照 SHA-256',
    receipt_snapshot longtext    not null comment 'tool-release-receipt v1：实际操作者、原因、审核证据及原始决定，不可变',
    recorded_micros  bigint      not null comment '首次决定的 UTC Unix 微秒，重放不改写',
    index            ix_tool_release_history (tool_key, command_key)
) engine=InnoDB comment 'AI TOOL 管理命令防重与不可变变更审计回执表';

-- AI TOOL S1-07 增量：在 S1-04 目录及 S1-06 发布表之后显式部署。
-- 停写、备份并核对四张目标表不存在；不修改已有聊天绑定，不自动建绑或启用 TOOL。
-- 固定解析材料不是 Invocation 快照；受理／派发仍须在 S2 接线并复核当前授权。

create table arte_ai_tool_binding
(
    binding_key    char(64)     not null comment '服务端稳定绑定 ID 的长度前缀 SHA-256' primary key,
    owner_key      char(64)     not null comment '真实租户、工作空间、主体的长度前缀 SHA-256',
    tool_id        varchar(256) not null comment '不可更换的稳定工具身份；固定工具版本在修订表中',
    row_version    bigint       not null comment '当前不可变 rN 修订的乐观版本，从 1 开始',
    payload_digest char(64)     not null comment '当前绑定白名单快照 SHA-256',
    snapshot       longtext     not null comment 'tool-binding v1：owner、名称、启停、当前版本及创建更新时间',
    index          ix_tool_binding_owner (owner_key, binding_key),
    constraint ck_tool_binding_version check (row_version > 0)
) engine=InnoDB comment 'AI TOOL 私有绑定稳定身份及当前修订指针';

create table arte_ai_tool_binding_revision
(
    artifact_key   char(64) not null comment 'binding/rN 固定修订引用摘要' primary key,
    binding_key    char(64) not null comment '稳定绑定身份摘要',
    revision_key   char(64) not null comment '与 artifact_key 相同的源修订键，读取时核对',
    tool_key       char(64) not null comment '固定工具基线引用摘要，不表示已解析的最新版本',
    payload_digest char(64) not null comment '不可变修订快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-revision v1：完整覆盖配置、选择规则、策略与 Secret 引用；不存凭据值',
    index          ix_tool_binding_revision (binding_key, artifact_key),
    constraint fk_tool_binding_revision_owner foreign key (binding_key) references arte_ai_tool_binding (binding_key),
    constraint fk_tool_binding_revision_tool foreign key (tool_key) references arte_ai_tool_definition (id_key)
) engine=InnoDB comment 'AI TOOL 不可变完整绑定修订';

create table arte_ai_tool_binding_resolution
(
    artifact_key   char(64) not null comment 'binding/p1-内容摘要固定引用的长度前缀 SHA-256' primary key,
    binding_key    char(64) not null comment '稳定绑定身份摘要',
    revision_key   char(64) not null comment '产生本材料的不可变 binding/rN 修订键',
    tool_key       char(64) not null comment '本次解析的实际固定工具版本摘要',
    payload_digest char(64) not null comment '不可变解析材料白名单快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-resolution v1：实际工具、合并配置、配置摘要和约束，不含调用参数及运行身份',
    index          ix_tool_binding_resolution (binding_key, artifact_key),
    constraint fk_tool_binding_resolution_owner foreign key (binding_key) references arte_ai_tool_binding (binding_key),
    constraint fk_tool_binding_resolution_revision foreign key (revision_key) references arte_ai_tool_binding_revision (artifact_key),
    constraint fk_tool_binding_resolution_tool foreign key (tool_key) references arte_ai_tool_definition (id_key)
) engine=InnoDB comment 'AI TOOL 内容寻址的固定解析材料';

create table arte_ai_tool_binding_command
(
    command_key    char(64) not null comment 'owner、实际主体类别及幂等键的长度前缀 SHA-256' primary key,
    owner_key      char(64) not null comment '可信个人归属摘要，读取时核对原始 owner',
    binding_key    char(64) not null comment '请求的稳定绑定身份摘要，允许记录不存在或越界拒绝',
    request_digest char(64) not null comment '完整原始命令语义摘要，集合先排序',
    payload_digest char(64) not null comment '原始审计回执白名单快照 SHA-256',
    snapshot       longtext not null comment 'tool-binding-receipt v1：原始命令、原因、实际操作者及成功或拒绝决定，不可变',
    index          ix_tool_binding_history (owner_key, binding_key, command_key)
) engine=InnoDB comment 'AI TOOL 个人绑定管理防重与不可变审计回执';

-- AI TOOL S1-08：完整本地目录刷新命令与不可变审计；在 S1-04/06/07 后显式部署。
-- 停写、备份、确认目标表不存在；运行时不执行 DDL，不回填发布、不创建绑定。
create table arte_ai_tool_discovery_command
(
    command_key    char(64) not null comment '实际主体、类别与幂等键的长度前缀 SHA-256' primary key,
    request_digest char(64) not null comment '完整本地刷新意图、可信 Scope 与原因的语义摘要',
    payload_digest char(64) not null comment '原始白名单审计回执的字节摘要',
    snapshot       longtext not null comment 'tool-discovery-receipt v1；首次观察、原始决定、实际操作者和原因，不包含目录内容或秘密'
) engine=InnoDB comment 'AI TOOL 完整本地目录刷新管理防重与不可变审计';

-- AI TOOL S2-03：独立本地免费只读执行快照，与通用受理同事务提交。
create table arte_ai_tool_execution_snapshot
(
    invocation_key    char(64) not null comment '所属 Invocation ID 的长度前缀 SHA-256，一对一主键' primary key,
    owner_key         char(64) not null comment '租户、空间、主体的长度前缀 SHA-256',
    binding_key       char(64) not null comment '稳定绑定 ID 的长度前缀 SHA-256，重放不重新选择版本',
    idempotency_key   char(64) not null comment '业务幂等键的长度前缀 SHA-256',
    submission_digest char(64) not null comment '原始参数及请求上限摘要，不含重新分配 ID、时刻或当前配置',
    request_digest    char(64) not null comment '固定实际版本、配置、Schema、策略和权限上限的执行摘要',
    payload_digest    char(64) not null comment '实际内部快照字节的 SHA-256，读取时重验',
    snapshot          longtext not null comment 'tool-execution-snapshot v1：独立本地免费只读执行材料，无运行资源或凭据值',
    constraint uk_tool_admission_replay unique (owner_key, binding_key, idempotency_key),
    constraint fk_tool_admission_invocation foreign key (invocation_key) references arte_ai_invocation (id_key)
) engine=InnoDB comment 'AI TOOL 与通用受理同事务的不可变执行快照';
