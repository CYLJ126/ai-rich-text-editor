-- 已部署新 AI 表的数据库只执行本增量脚本；首次部署使用完整 DDL。

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
