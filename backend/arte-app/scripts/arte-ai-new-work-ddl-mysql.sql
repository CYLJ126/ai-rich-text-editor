-- 第二批：停掉旧版本后执行本脚本，再启动新版本。仅新增表，不修改已有聊天与账本数据。
-- 显式声明时间默认值，兼容 explicit_defaults_for_timestamp=OFF 且禁止零日期的 MySQL。
-- 不声明 on update；应用显式写入截止时间，租约续期不能改动排队时间或截止时间。
create table if not exists arte_ai_new_worker_lease
(
    worker_key   varchar(64)  not null               comment 'Worker 工作组标识，当前为 ai.model，同组共享单活租约与启动速率窗口',
    owner_id     char(36)     null                   comment '持有实例的 UUID，尚无持有者时为空，是否有效由租约到期时间判断',
    lease_until  timestamp(6) null default null      comment '实例租约到期时间，使用数据库时钟，尚未获取租约时为空',
    draining     boolean      not null default false comment '是否进入停机收尾，默认否，为真时停止受理和认领新任务',
    rate_until   timestamp(6) null default null      comment '当前一分钟任务启动速率窗口的到期时间，尚未开始计数时为空',
    starts_count int          not null default 0     comment '当前速率窗口已成功认领的任务数，窗口切换时重新计数',
    primary key (worker_key)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 模型 Worker 租约表：单活实例、停机收尾及耐久启动速率窗口';

create table if not exists arte_ai_new_work
(
    execution_id     char(36)     not null                              comment '模型执行 ID，使用 UUID，与同一作用域的执行记录一对一关联',
    scope_key        char(64)     not null                              comment '租户、工作空间及主体的作用域规范摘要',
    worker_key       varchar(64)  not null                              comment '负责处理任务的 Worker 工作组标识，对应工作组租约记录',
    work_json        mediumtext   not null                              comment '版本化任务正文 JSON，含模型输入、执行上下文及同意引用，不含 API Key 或会话 Token',
    queued_at        timestamp(6) not null default current_timestamp(6) comment '可靠受理并进入队列的时间，由数据库时钟写入，用于排队排序及等待耗时统计',
    deadline_at      timestamp(6) not null default current_timestamp(6) comment '排队及模型执行的截止时间，正常受理由应用显式写入，省略时默认当前时间使任务立即到期',
    owner_id         char(36)     null                                  comment '认领任务的 Worker 实例 UUID，未认领或终态清理后为空',
    lease_token      char(36)     null                                  comment '本次认领的唯一写入围栏令牌，使用 UUID，未认领或终态清理后为空',
    lease_until      timestamp(6) null default null                     comment '任务租约到期时间，未认领或终态清理后为空，到期后旧 Worker 不得写入',
    cancel_requested boolean      not null default false                comment '是否已请求取消，默认否，为真时停止待执行或运行中的任务，不代表已完成取消',
    finished         boolean      not null default false                comment '队列工作是否已结束并完成清理，默认否，不代表模型调用成功',
    primary key (execution_id),
    index ix_ai_new_work_queue (worker_key, finished, queued_at, execution_id),
    index ix_ai_new_work_lease (worker_key, lease_until),
    constraint fk_ai_new_work_execution
        foreign key (execution_id, scope_key)
        references arte_ai_new_execution (execution_id, scope_key)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 模型工作队列表：与执行及预算原子受理，支持租约围栏、取消及重启恢复';
