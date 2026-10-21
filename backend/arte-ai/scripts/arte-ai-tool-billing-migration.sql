-- S5-02：已有环境在升级消费者后、启用收费工具前执行一次；不修改既有账户或历史费用。
create table arte_ai_tool_bill
(
    attempt_key     char(64) not null primary key comment '收费 Attempt 摘要，一次尝试仅一份不可变费用事实',
    invocation_key  char(64) not null comment '所属 Invocation 摘要',
    owner_key       char(64) not null comment '主体和空间归属摘要',
    payload_digest  char(64) not null comment '费用快照 SHA-256 摘要',
    charge_snapshot longtext not null comment '可信账单或未进入适配器的零费用证据；不含密钥及原始响应',
    constraint fk_tool_bill_attempt foreign key (attempt_key) references arte_ai_attempt (id_key),
    constraint fk_tool_bill_invocation foreign key (invocation_key) references arte_ai_invocation (id_key)
) engine=InnoDB comment '工具独立费用证据，业务结果保存失败时仍可恢复结算';

create table arte_ai_tool_price_guard
(
    rate_key     char(64) not null primary key comment '固定工具费率引用摘要，跨账户及 Run 共享',
    blocked      boolean  not null default false comment '真实费用超过报价后锁存，禁止该费率新派发',
    evidence_ref varchar(256) null comment '触发阻断的可信费用证据引用'
) engine=InnoDB comment '工具报价上界失效阻断，不自动复位';
