-- S4-04：已有 S4-03 库停写、备份后显式执行；新装完整基线已包含，不重复执行。
-- 不为旧 Run 猜测额度／账户；旧无预算快照可读取，但不得派发新的 Run 子调用。
create table arte_ai_tool_chat_budget
(
    run_key  char(64) not null comment '根 Run ID 的长度前缀 SHA-256 摘要，关联所属 Run',
    currency char(3)  not null comment 'ISO 4217 三字母币种代码；各币种独立限额，按币种顺序锁定',
    version  bigint   not null comment '本币种预算版本，从 0 开始用于条件更新；独立于 Run 检查点版本',
    digest   char(64) not null comment 'snapshot 编码内容的 SHA-256 摘要，用于读取时校验完整性',
    snapshot longtext not null comment '预算快照：限额 L、已确认费用 C、未收敛预留 H 和超额阻断标志；金额按 Money 精确保存',
    primary key (run_key, currency),
    constraint fk_tool_chat_budget_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment 'Run 附加费用上限，与现有账户账本同事务占用和结算';

create table arte_ai_tool_chat_budget_allocation
(
    run_key         char(64)    not null comment '所属根 Run ID 的长度前缀 SHA-256 摘要',
    invocation_key  char(64)    not null comment '收费子 Invocation ID 的长度前缀 SHA-256 摘要',
    attempt_key     char(64)    not null comment '收费 Attempt ID 的长度前缀 SHA-256 摘要，全局唯一',
    attempt_number  int         not null comment '子 Invocation 内的尝试序号，从 1 开始；重试独立占用额度',
    charge_kind     varchar(16) not null comment '费用类别；当前仅支持 MODEL（模型调用），付费工具尚未开放',
    reservation_key char(64)    not null comment '账户 Reservation ID 的长度前缀 SHA-256 摘要，与本分配记录一一对应',
    digest          char(64)    not null comment 'snapshot 编码内容的 SHA-256 摘要，用于读取时校验完整性',
    snapshot        longtext    not null comment '分配快照：固定报价、费率、账户引用、预留身份、费用状态、实际费用及版本；未知费用保留报价预留',
    primary key (run_key, invocation_key, attempt_number, charge_kind),
    unique key uk_tool_chat_allocation_attempt(attempt_key),
    unique key uk_tool_chat_allocation_reservation(reservation_key),
    constraint fk_tool_chat_allocation_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment '收费 Attempt 唯一 Run 预留事实；未知费用不因到期或取消释放';
