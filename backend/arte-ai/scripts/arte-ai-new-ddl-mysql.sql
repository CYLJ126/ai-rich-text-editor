-- 新 AI 独立存储表：由部署流程显式执行，不在应用启动时执行。
-- 表名统一使用 arte_ai_ 前缀；连接及数据库时区统一 UTC，所有表使用 InnoDB。
-- 不添加 @MybatisParams 注解，因为 ExecutionSqlSessionFactory 中并未添加拦截器。
-- 新存储会话表使用 arte_ai_conversation_new，与旧 arte_ai_conversation 表隔离。TODO：改造完后去掉 _new 后缀。
-- *_key 为 UTF-8 长度前缀编码的 SHA-256；snapshot 为 schemaVersion=1 的内部 JSON 快照。

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
    snapshot       longtext not null comment '逻辑调用权威快照，包含状态、版本及执行上下文',
    next_attempt   int not null comment '最近已分配的尝试序号，初始为 0',
    next_fence     bigint not null comment '最近已分配的 fencing token，初始为 0',
    next_sequence  bigint not null comment '最近已提交的事件序号，初始为 0',
    retained_after bigint not null comment '已裁剪事件的最大序号，初始为 0',
    written_bytes  bigint not null comment '累计输出批次编码字节数，裁剪事件不减少该值'
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
    id_key         char(64) not null comment '执行尝试 ID 哈希'
        primary key,
    invocation_key char(64) not null comment '所属逻辑调用 ID 哈希',
    attempt_number int not null comment '该调用内的尝试序号，从 1 开始递增',
    purpose        varchar(16) not null comment '租约用途：EXECUTE-执行；RECONCILE-核对',
    snapshot       longtext not null comment '执行尝试快照，包含版本、Worker、租约及 fencing token',
    constraint uk_arte_ai_attempt_number
        unique (invocation_key, attempt_number)
) engine = InnoDB comment '新 AI 执行尝试表';

create table arte_ai_event
(
    invocation_key char(64) not null comment '所属逻辑调用 ID 哈希',
    sequence_no    bigint not null comment '调用内连续递增的事件序号，从 1 开始',
    snapshot       longtext not null comment '已提交事件快照，包含有界输出批次、状态或终态',
    primary key (invocation_key, sequence_no)
) engine = InnoDB comment '新 AI 耐久执行事件表';

create table arte_ai_operation
(
    invocation_key  char(64) not null comment '所属逻辑调用 ID 哈希',
    operation_key   char(64) not null comment '操作类型及业务防重键组成的哈希',
    digest          char(64) not null comment '首次提交的操作内容摘要',
    first_sequence  bigint not null comment '首次提交的首条事件序号',
    event_count     int not null comment '首次提交的事件数量',
    result_snapshot longtext null comment '完成操作首次提交的调用快照，用于幂等重放',
    evidence_ref    varchar(256) null comment '可信远端核对证据引用',
    primary key (invocation_key, operation_key)
) engine = InnoDB comment '新 AI 追加及完成操作防重表';

create table arte_ai_outbox
(
    message_key     char(64) not null comment '调用 ID、消息类型及事件序号组成的消息哈希'
        primary key,
    invocation_key  char(64) not null comment '所属逻辑调用 ID 哈希',
    invocation_id   varchar(256) not null comment '逻辑调用原始业务 ID',
    owner_tenant    varchar(256) not null comment '受理时的租户 ID',
    owner_workspace varchar(256) not null comment '受理时的工作空间 ID',
    owner_subject   varchar(256) not null comment '受理时的主体 ID',
    kind            varchar(16) not null comment '消息类型：DISPATCH-派发；EVENT-事件通知',
    sequence_no     bigint not null comment '事件序号；派发消息为 0',
    worker_id       varchar(256) null comment '当前领取消息的 Worker ID',
    token           bigint not null comment '消息租约 fencing token，初始为 0',
    lease_until     bigint not null comment '租约截止时间，UTC Unix 毫秒，初始为 0',
    delivered       int not null comment '是否已确认投递：0-否；1-是',
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
    version_no        bigint not null comment '会话版本，受理新调用时递增',
    active_invocation char(64) null comment '当前活跃调用 ID 哈希；已知终态释放，UNKNOWN 保留',
    snapshot          longtext not null comment '会话权威快照'
) engine = InnoDB comment '新 AI 独立会话表';

create table arte_ai_turn
(
    id_key           char(64) not null comment '交流轮次 ID 哈希'
        primary key,
    conversation_key char(64) not null comment '所属会话 ID 哈希',
    sequence_no      bigint not null comment '会话内连续递增的轮次序号，从 1 开始',
    snapshot         longtext not null comment '轮次快照，包含固定用户输入、历史路径及调用候选',
    constraint uk_arte_ai_turn_sequence
        unique (conversation_key, sequence_no)
) engine = InnoDB comment '新 AI 交流轮次表';

create table arte_ai_account
(
    id_key    char(64) not null comment '预算账户引用哈希'
        primary key,
    owner_key char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    snapshot  longtext not null comment '预算账户快照，包含币种、固定费率、限额、held、charged 及版本'
) engine = InnoDB comment '新 AI 预算账户表';

create table arte_ai_reservation
(
    id_key         char(64) not null comment '预算预留 ID 哈希'
        primary key,
    account_key    char(64) not null comment '所属预算账户引用哈希',
    invocation_key char(64) not null comment '所属逻辑调用 ID 哈希',
    attempt_key    char(64) not null comment '所属执行尝试 ID 哈希，每次尝试最多一个预留',
    snapshot       longtext not null comment '预算预留快照，包含金额、费率、状态、版本及有效期',
    constraint uk_arte_ai_reservation_attempt
        unique (attempt_key)
) engine = InnoDB comment '新 AI 预算预留表';

create table arte_ai_settlement
(
    reservation_key char(64) not null comment '所属预算预留 ID 哈希',
    settlement_key  char(64) not null comment '结算业务防重键哈希',
    digest          char(64) not null comment '结算内容及证据组成的摘要',
    snapshot        longtext not null comment '首次提交的结算事实快照',
    result_snapshot longtext not null comment '该次结算首次提交的预留快照，用于幂等重放',
    evidence_kind   varchar(32) not null comment '证据类型：UNKNOWN_COST；PROVIDER_BILL；PROVEN_NOT_DISPATCHED',
    evidence_ref    varchar(256) null comment '可信账单或未发送证据引用',
    primary key (reservation_key, settlement_key)
) engine = InnoDB comment '新 AI 预算结算事实表';
