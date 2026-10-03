-- 第二批：停掉旧版本后执行本脚本，再启动新版本。仅新增表，不修改已有聊天与账本数据。
create table if not exists arte_ai_new_worker_lease
(
    worker_key
    varchar
(
    64
) not null,
    owner_id char
(
    36
) null,
    lease_until timestamp
(
    6
) null,
    draining boolean not null default false,
    rate_until timestamp
(
    6
) null,
    starts_count int not null default 0,
    primary key
(
    worker_key
)
    ) engine=InnoDB default charset=utf8mb4 collate =utf8mb4_bin comment '模型 Worker 单活租约与耐久启动速率窗口';

create table if not exists arte_ai_new_work
(
    execution_id
    char
(
    36
) not null,
    scope_key char
(
    64
) not null,
    worker_key varchar
(
    64
) not null,
    work_json mediumtext not null,
    queued_at timestamp
(
    6
) not null,
    deadline_at timestamp
(
    6
) not null,
    owner_id char
(
    36
) null,
    lease_token char
(
    36
) null,
    lease_until timestamp
(
    6
) null,
    cancel_requested boolean not null default false,
    finished boolean not null default false,
    primary key
(
    execution_id
),
    index ix_ai_new_work_queue
(
    worker_key,
    finished,
    queued_at,
    execution_id
),
    index ix_ai_new_work_lease
(
    worker_key,
    lease_until
),
    constraint fk_ai_new_work_execution foreign key
(
    execution_id,
    scope_key
)
    references arte_ai_new_execution
(
    execution_id,
    scope_key
)
    ) engine=InnoDB default charset=utf8mb4 collate =utf8mb4_bin comment '与执行及预算原子受理的模型工作队列，租约防止旧 Worker 写入';
