-- S3-04：全新库已包含以下三表；已有 S3-03 库停写、备份后显式执行一次。
-- 独立新表名，不复用参考项目旧 reconciliation 表；不在应用运行时建表，不授予核对或执行权限。
create table arte_ai_tool_outcome_check
(
    invocation_key char(64)     not null primary key comment '原逻辑调用哈希，一次 UNKNOWN 只登记一个核对工作项',
    invocation_id  varchar(256) not null comment '原 Invocation ID，不是新执行',
    owner_key      char(64)     not null comment '原租户、空间与主体哈希',
    attempt_id     varchar(256) not null comment '唯一原 Attempt 身份，不创建第二次执行',
    operation_key  varchar(256) not null comment '受信适配器派生的原领域或远端操作键',
    space_key      char(64)     not null comment '容量仲裁的原空间哈希',
    provider_key   char(64)     not null comment '容量仲裁的固定提供方哈希',
    state          varchar(32)  not null comment 'CHECKING／NEEDS_REVIEW／UNRESOLVABLE／RESOLVED',
    check_count    int          not null comment '已分配的逻辑查询次数，最多 6；重领不重置',
    version_no     bigint       not null comment '工作项版本，不同于 Invocation 或 Attempt 版本',
    worker_id      varchar(256) not null comment '当前系统核对 Worker；空串表示未领取',
    fence          bigint       not null comment '每次领取增加的核对 fencing',
    lease_until    bigint       not null comment '数据库 UTC epoch millis 租约截止，零为未领取',
    query_deadline bigint       not null comment '当前查询 UTC epoch millis 截止，重领不延长',
    next_check     bigint       not null comment '下一允许查询 UTC epoch millis；不受浏览器刷新重置',
    created_at     bigint       not null comment '首次 UNKNOWN 时间 UTC epoch millis',
    valid_until    bigint       not null comment '首次 UNKNOWN 加 30 分钟硬上限',
    last_code      varchar(96)  not null comment '安全结果码，不保存原始异常或业务参数',
    evidence_key   varchar(64)  not null comment '最终可信证据键，空串表示未确认',
    index          ix_tool_outcome_due(state,next_check,invocation_key),
    constraint fk_tool_outcome_inv foreign key (invocation_key) references arte_ai_invocation (id_key),
    constraint ck_tool_outcome_count check (check_count >= 0 and check_count <= 6)
) engine=InnoDB comment '独立可靠业务核对工作项；无 execute 权限';

create table arte_ai_tool_outcome_evidence
(
    invocation_key  char(64)     not null,
    evidence_key    char(64)     not null,
    check_no        int          not null,
    decision        varchar(32)  not null,
    evidence_ref    varchar(256) not null comment '受信领域回执或查询证据引用，不是用户自报成功',
    evidence_digest char(64)     not null,
    result_digest   char(64)     not null,
    recorded_at     bigint       not null,
    primary key (invocation_key, evidence_key),
    constraint fk_tool_outcome_evidence foreign key (invocation_key) references arte_ai_tool_outcome_check (invocation_key)
) engine=InnoDB comment '核对证据审计，与原调用终态及事件同事务';

create table arte_ai_tool_outcome_scan
(
    scan_key  varchar(16) not null primary key,
    after_key varchar(64) not null comment '一次历史回填的主键游标，不重扫全库',
    completed boolean     not null comment '扫描完毕后仅靠 UNKNOWN 原子登记接收新任务'
) engine=InnoDB comment '启用时每轮最多扫描 32 条历史调用，游标与任务同事务';
