-- 升级顺序：停止旧后端，已有模型／聊天／工作表后执行本脚本，再启动新版本。新增表并更新快照格式约束，不改写已有数据。
-- 第三批流式输出：增量与状态事件同事务提交，部分正文缓存供刷新恢复。
create table if not exists arte_ai_new_stream_output
(
    execution_id  char(36)   not null comment '模型执行 ID，使用 UUID，与模型执行记录关联',
    partial_text  mediumtext not null comment '已持久化的累计回答正文，用于刷新恢复，不代表调用成功',
    utf8_bytes    int        not null comment '累计回答正文的 UTF-8 字节数，用于限制流式输出大小',
    last_sequence bigint     not null comment '已缓存的最后一条文本增量事件序号，用于续传去重',
    primary key (execution_id),
    constraint fk_ai_new_stream_execution
        foreign key (execution_id)
        references arte_ai_new_execution (execution_id)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 流式回答缓存表：累计正文、字节数及增量续传游标';

create table if not exists arte_ai_new_stream_delta
(
    execution_id char(36) not null comment '模型执行 ID，使用 UUID，与模型执行事件关联',
    sequence_no  bigint   not null comment '关联的模型执行事件序号，与状态事件共用执行内递增序列',
    text_delta   text     not null comment '本批新增的模型回答文本，按事件序号拼接，不是累计正文',
    primary key (execution_id, sequence_no),
    constraint fk_ai_new_stream_event
        foreign key (execution_id, sequence_no)
        references arte_ai_new_event (execution_id, sequence_no)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 流式文本增量表：与执行事件原子提交，支持按游标重放';

-- v2 上下文 Token 计量侧表；保留原有 v1 快照，不改写已提交上下文。
create table if not exists arte_ai_new_context_token_budget
(
    snapshot_id            char(36)     not null comment '关联的 v2 上下文快照 ID，使用 UUID，与快照一对一',
    context_window_tokens  int          not null comment '应用侧上下文 Token 总容量，包含输入、输出预留及安全余量',
    input_token_limit      int          not null comment '输入 Token 上限，等于总容量减去输出预留及安全余量',
    estimated_input_tokens int          not null comment '实际选入消息的输入 Token 保守估算值，不是供应商计费用量',
    safety_token_reserve   int          not null comment '为分词及协议开销额外保留的安全 Token 数',
    estimator_version      varchar(128) not null comment 'Token 估算策略版本，与计量事实共同参与快照摘要校验',
    primary key (snapshot_id),
    constraint fk_ai_new_token_snapshot
        foreign key (snapshot_id)
        references arte_ai_new_context_snapshot (snapshot_id)
)
    engine=InnoDB
    default charset=utf8mb4
    collate=utf8mb4_bin
    comment '新 AI 上下文 Token 预算表：v2 快照的容量、输入估算及安全预留事实';

-- MySQL 8 原子更新格式约束；已有 v1 快照继续有效。
alter table arte_ai_new_context_snapshot
    drop check ck_ai_new_snapshot_format,
    add constraint ck_ai_new_snapshot_format check (payload_format in ('arte.chat.context.v1','arte.chat.context.v2'));
