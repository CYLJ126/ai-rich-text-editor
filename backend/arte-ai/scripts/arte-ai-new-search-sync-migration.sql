-- 会话搜索 S2：只新增后台进度表。部署时显式执行，不由应用执行。
-- 执行前备份目标数据库；原业务表、Outbox 和消息正文不变。
create table arte_ai_search_generation
(
    generation       varchar(128) character set ascii collate ascii_bin not null primary key comment '物理索引代际名，各代际独立维护进度，不使用读写别名',
    rules_version    varchar(256)                                       not null comment '检索规则版本，代际注册后固定，用于校验映射与同步规则兼容性',
    worker           varchar(128) null comment '租约关联的 Worker 标识，释放租约后为空',
    fence            bigint                                             not null comment '每次领取租约时递增的 fencing 序号，用于阻止旧持有者更新进度',
    lease_until      bigint                                             not null comment '租约到期时间，数据库 Unix 毫秒；0 表示未持有租约',
    last_success_at  bigint null comment '最近一次源任务成功完成的时间，数据库 Unix 毫秒；尚无成功记录时为空，不代表索引已刷新',
    validation_epoch bigint                                             not null default 0 comment '代际切换校准凭据：0-未准备；正数-待消费；负数-已消费；每次准备递增绝对值'
) engine=InnoDB comment '搜索物理代际与数据库租约';

create table arte_ai_search_checkpoint
(
    generation varchar(128) character set ascii collate ascii_bin not null comment '所属物理索引代际，与扫描任务和同步账本使用同一代际',
    lane       char(1)                                            not null comment '扫描通道：C-会话；T-轮次；I-调用；E-已知源任务校准',
    cursor_key varchar(65) character set ascii collate ascii_bin  not null comment '本轮已扫描的稳定键边界；C/T/I 为 64 位源键，E 为 65 位任务键；空串表示从头开始',
    rounds     bigint                                             not null comment '自初始化或重新准备切换校准后完成的循环数；0 表示尚未完成一轮',
    next_at    bigint                                             not null comment '该通道下一次允许扫描的时间，数据库 Unix 毫秒；0 表示可立即扫描',
    primary key (generation, lane)
) engine=InnoDB comment '搜索来源循环及校准检查点';

create table arte_ai_search_work
(
    generation      varchar(128) character set ascii collate ascii_bin not null comment '所属物理索引代际，重建任务不会跟随别名切换目标',
    task_key        char(65) character set ascii collate ascii_bin     not null comment '稳定任务键，由 kind 与 64 位 source_key 拼接组成',
    kind            char(1)                                            not null comment '源类型：C-会话；T-轮次；I-调用',
    source_key      char(64)                                           not null comment '源表 id_key，即业务 ID 编码后的 SHA-256，用于按稳定键重新读取权威快照',
    owner_key       char(64) null comment '租户、空间、主体组成的归属哈希；尚未解析归属时为空，不作为授权依据',
    tenant_id       varchar(256) null comment '源会话所属租户 ID，尚未解析归属时为空',
    workspace_id    varchar(256) null comment '源会话所属工作空间 ID，尚未解析归属时为空',
    subject_id      varchar(256) null comment '源会话所属主体 ID，尚未解析归属时为空',
    conversation_id varchar(256) null comment '源记录关联的会话 ID，尚未解析关联时为空',
    pending         boolean                                            not null comment '是否待处理：0-本轮已完成；1-等待处理、继续处理或失败重试',
    force_write     boolean                                            not null comment '是否强制重写：0-按指纹及确认版本决定写入；1-校准时强制重写当前投影',
    source_state    varchar(16) null comment '最近已观测的源状态：LIVE-有效；REMOVED-已移除；ARCHIVED-会话已归档；SKIP-不收录；尚未观测时为空',
    failures        int                                                not null comment '连续失败次数，成功完成或转入正常分批续作时清零',
    error_code      varchar(64) null comment '最近失败类型码，如 MISSING_RESULT、ES_ITEM_FAILED；不存异常原文，成功或正常续作时清空',
    next_at         bigint                                             not null comment '任务下一次允许执行的时间，数据库 Unix 毫秒；用于重试退避或分批续作，0 表示可立即执行',
    primary key (generation, task_key),
    index           idx_search_pending(generation,pending,next_at),
    index           idx_search_owner(generation,owner_key,pending)
) engine=InnoDB comment '搜索源身份与可恢复重试，不存正文';

create table arte_ai_search_entry
(
    generation      varchar(128) character set ascii collate ascii_bin not null comment '所属物理索引代际，旧代际的确认进度不能替代新代际进度',
    entry_key       char(64)                                           not null comment '检索记录稳定身份的 SHA-256，对应 ES 文档 _id；区分标题、用户消息和各助手候选消息',
    task_key        char(65) character set ascii collate ascii_bin     not null comment '产生该检索记录的源任务键，与搜索工作表的 task_key 对应',
    kind            char(1)                                            not null comment '源类型：C-会话；T-轮次；I-调用；不是 TITLE/MESSAGE 检索记录类型',
    source_key      char(64)                                           not null comment '源表 id_key，即业务 ID 编码后的 SHA-256，与 kind 共同确定权威来源',
    owner_key       char(64)                                           not null comment '租户、空间、主体组成的归属哈希，用于分范围统计与校准，不作为授权依据',
    tenant_id       varchar(256)                                       not null comment '检索记录所属租户 ID',
    workspace_id    varchar(256)                                       not null comment '检索记录所属工作空间 ID',
    subject_id      varchar(256)                                       not null comment '检索记录所属主体 ID',
    conversation_id varchar(256)                                       not null comment '检索记录关联的会话 ID，用于归档清理与恢复校准',
    identity_json   mediumtext                                         not null comment '重试与清理所需的检索身份及定位元数据 JSON；标题、正文和内容哈希清空，不保存授权信息',
    fingerprint     char(64)                                           not null comment '期望状态与源投影的 SHA-256 指纹，用于判断是否需要推进同步版本',
    revision        bigint                                             not null comment '该代际下该检索记录的单调同步版本，从 1 开始；不同于业务版本及 ES 内部版本',
    acknowledged    bigint                                             not null comment '最近已确认写入或清理成功的同步版本；0-尚未确认；等于 revision 表示当前版本已确认，不代表 ES 已刷新',
    desired         varchar(16)                                        not null comment '期望索引状态：LIVE-正常投影；TOMBSTONE-清除正文的墓碑；DELETE-物理删除归档记录',
    primary key (generation, entry_key),
    index           idx_search_source(generation,task_key),
    index           idx_search_entry_owner(generation,owner_key)
) engine=InnoDB comment '搜索同步版本、无正文身份与期望状态';
