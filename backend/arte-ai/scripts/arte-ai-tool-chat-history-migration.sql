-- S4-03：已有 S4-02 库停写、备份后显式执行；新装完整基线已包含，不重复执行。
-- Turn 新写 schemaVersion=2；读取兼容 v1。所有新 AI 节点协调升级后再恢复写入，不混跑旧节点。
alter table arte_ai_conversation_new
    add column active_kind varchar(32) not null default 'INVOCATION' comment '活动根类型；active_invocation 为该类型根 ID 的摘要';

create table arte_ai_tool_chat_link
(
    run_key              char(64)     not null primary key,
    conversation_id      varchar(256) not null,
    turn_id              varchar(256) not null,
    conversation_version bigint       not null,
    request_digest       char(64)     not null comment '原 Turn 及提交版本摘要，防跨会话幂等串用',
    final_invocation_id  varchar(256) null comment '最终完整模型回答的真实子 Invocation；工具中间结果不能写入',
    index                ix_tool_chat_link_turn(conversation_id,turn_id),
    constraint fk_tool_chat_link_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment 'Run 聊天归属及最终回答来源；与会话占用、Turn 同事务';

create table arte_ai_tool_chat_run_event
(
    run_key     char(64) not null,
    sequence_no bigint   not null comment '独立 Run 游标，从 1 开始；不与子 Invocation 序号合并',
    snapshot    longtext not null comment '状态与检查点通知，不含正文、工具参数、密钥',
    primary key (run_key, sequence_no),
    constraint fk_tool_chat_event_run foreign key (run_key) references arte_ai_tool_chat_run (run_key)
) engine=InnoDB comment 'Run 事务事件；RUN_EVENT Outbox 至少一次提示，可按游标补读';
