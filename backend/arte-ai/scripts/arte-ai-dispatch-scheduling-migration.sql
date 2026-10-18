-- S2-06 显式部署：先停受理、停止旧消费者并排空 DISPATCH，再执行。客户端必须遇错停止，禁止 --force。
-- 不能从旧快照猜测工具分类；存在未确认旧工作项时拒绝迁移，先完成/核对/清理后再执行。
create
temporary table arte_ai_scheduling_migration_guard(pending_count bigint not null check (pending_count=0));
insert into arte_ai_scheduling_migration_guard
select count(*)
from arte_ai_outbox
where kind = 'DISPATCH'
  and delivered = 0;
drop table arte_ai_scheduling_migration_guard;
alter table arte_ai_outbox
    add column work_class varchar(32) not null default 'GENERATION' comment '受信调度类别；EVENT 忽略此字段';
alter table arte_ai_outbox
    add column provider_key char(64) not null default '' comment '固定提供者身份哈希';
alter table arte_ai_outbox
    add column space_key char(64) not null default '' comment '租户和空间组合哈希';
alter table arte_ai_outbox
    add column subject_key char(64) not null default '' comment '租户和主体组合哈希';
alter table arte_ai_outbox
    add column queued_at bigint not null default 0 comment '原受理排队时刻 UTC Unix 毫秒';
alter table arte_ai_outbox
    add column queue_deadline bigint not null default 0 comment '原排队绝对期限 UTC Unix 毫秒';
alter table arte_ai_outbox
    add column queue_pending int not null default 0 comment '是否仍在排队';
alter table arte_ai_outbox
    add column queue_reserved int not null default 0 comment '是否占用队列准入预留；生成保留至终态以覆盖重试';
alter table arte_ai_outbox
    add column finished int not null default 0 comment '调用是否已终态；仅用于调度收尾，不替代 Invocation 权威';

create index idx_arte_ai_outbox_class on arte_ai_outbox (kind, work_class, delivered, lease_until, queued_at);
create index idx_arte_ai_outbox_queue on arte_ai_outbox (kind, queue_pending, queue_deadline);
create index idx_arte_ai_outbox_reserved on arte_ai_outbox (kind, queue_reserved, message_key);

create table arte_ai_scheduling_permit
(
    message_key  char(64)    not null primary key comment '原 DISPATCH 消息身份',
    token        bigint      not null comment '原工作项领取 fencing token',
    work_class   varchar(32) not null comment '容量所属类别',
    space_key    char(64)    not null comment '受信空间身份哈希',
    provider_key char(64)    not null comment '固定提供者身份哈希',
    lease_until  bigint      not null comment '数据库时钟下租约截止毫秒'
) engine=InnoDB comment '新 AI 分类调度容量许可；不代替 Attempt 或模型限流';
create index idx_arte_ai_scheduling_class on arte_ai_scheduling_permit (work_class, lease_until);
create index idx_arte_ai_scheduling_space on arte_ai_scheduling_permit (space_key, lease_until);
create index idx_arte_ai_scheduling_provider on arte_ai_scheduling_permit (provider_key, lease_until);
create table arte_ai_dispatch_cursor
(
    work_class varchar(32) not null primary key comment '公平调度类别',
    last_space char(64)    not null comment '最近服务的空间哈希；跨实例轮转'
) engine=InnoDB comment '新 AI 分类调度的耐久空间游标';
create table arte_ai_dispatch_pool
(
    pool_key        varchar(32) not null primary key comment '同构执行池身份',
    policy_digest   char(64)    not null comment '调度配置固定摘要',
    manifest_digest varchar(64) not null comment '固定本地工具清单摘要；无工具池时为空'
) engine=InnoDB comment '新 AI 同构执行池契约；变更前须停写并排空许可';

create table arte_ai_queue_capacity
(
    counter_key  char(64) not null primary key comment '集群、类别、空间或主体队列预留维度哈希',
    reservations int      not null comment '当前逻辑队列预留数；与受理及释放同事务',
    constraint ck_arte_ai_queue_nonnegative check (reservations >= 0)
) engine=InnoDB comment '新 AI 耐久队列准入容量计数；不能用近似查询放行';
