-- 会话目录 V1：MySQL 8 / InnoDB；显式部署，不在启动时执行。
create table arte_ai_conversation_metadata
(
    id_key                char(64)                                               not null comment '会话 ID 哈希，与会话权威表的 id_key 一致' primary key,
    owner_key             char(64)                                               not null comment '租户、工作空间、主体组成的归属哈希',
    conversation_id       varchar(256) character set utf8mb4 collate utf8mb4_bin not null comment '会话原始业务 ID，使用二进制排序规则作为游标排序的次级键',
    title                 mediumtext                                             not null comment '当前会话标题，支持自动命名及人工修改',
    active                boolean                                                not null comment '会话是否活跃：0-否；1-是，仅活跃会话进入列表',
    metadata_version      bigint                                                 not null comment '标题或置顶状态变更时递增的元数据版本，初始为 0，与会话执行版本独立',
    pinned                boolean                                                not null comment '是否置顶：0-否；1-是，同一归属最多置顶 7 个会话',
    title_manually_edited boolean                                                not null comment '是否保护标题免于自动改名：0-否；1-是，人工改名及旧会话初始化时置为 1',
    auto_title_resolved   boolean                                                not null comment '是否已结束首次用户消息自动命名：0-待处理；1-已结束，旧会话初始化时置为 1',
    preview_text          mediumtext null comment '最近用户轮次的输入摘要，按 30 个扩展字素簇截断，超长追加省略号，无摘要时为 NULL',
    preview_sequence      bigint                                                 not null default 0 comment '当前预览对应的轮次序号，初始为 0，防止历史回填覆盖较新预览',
    user_message_count    bigint null comment '按消息身份去重后的用户消息数，空会话为 0，历史统计尚未完成时为 NULL',
    last_message_micros   bigint null comment '最近消息生成时间，UTC Unix 微秒，无消息或历史生成时间无法完整恢复时为 NULL',
    sort_micros           bigint                                                 not null comment '列表活动排序时间，UTC Unix 微秒，按降序读取，无可用消息时间时使用会话创建时间',
    created_micros        bigint                                                 not null comment '会话创建时间，UTC Unix 微秒',
    statistics_ready      boolean                                                not null comment '消息统计是否完整可用：0-待回填或历史数据不完整；1-完整可用',
    index                 idx_ai_conversation_list (owner_key,pinned,active,sort_micros desc,conversation_id desc)
) engine=InnoDB comment '新 AI 会话列表元数据及消息统计表';

create table arte_ai_conversation_metadata_command
(
    owner_key          char(64) not null comment '租户、工作空间、主体组成的归属哈希',
    command_key        char(64) not null comment '元数据修改命令幂等键哈希，改名与置顶操作共用同一归属下的键空间',
    request_digest     char(64) not null comment '会话 ID、预期元数据版本、操作类型及目标值组成的规范化请求摘要',
    workspace_snapshot longtext not null comment '首次提交的命令结果快照，包含结果码及可选成功回执，用于幂等重放',
    primary key (owner_key, command_key)
) engine=InnoDB comment '新 AI 会话元数据修改命令幂等回执表';

-- 只保存消息身份与时间，不存消息正文；用户按 messageId、助手按 invocation/attempt 去重。
create table arte_ai_conversation_message_fact
(
    conversation_key char(64)    not null comment '所属会话 ID 哈希',
    fact_key         char(64)    not null comment '消息角色及身份组成的去重哈希，用户按消息 ID、助手按调用 ID 与尝试 ID 去重',
    message_role     varchar(16) not null comment '消息角色：USER-用户；ASSISTANT-助手',
    generated_micros bigint      not null comment '消息生成时间，UTC Unix 微秒，用户取轮次创建时间，助手取首次可见文本事件时间',
    primary key (conversation_key, fact_key)
) engine=InnoDB comment '新 AI 会话消息身份及生成时间去重事实表';

-- 可恢复的有界离线回填任务；不在用户列表请求中遍历历史。
create table arte_ai_conversation_projection_job
(
    id_key           char(64) not null comment '待回填会话 ID 哈希，每个会话最多一个任务' primary key,
    turn_sequence    bigint   not null comment '已完成回填的轮次序号，初始为 0，后续从更大序号的轮次继续',
    invocation_index int      not null comment '当前待回填轮次内的调用列表索引，从 0 开始，轮次完成后重置为 0',
    event_sequence   bigint   not null comment '当前调用已扫描的事件序号，初始为 0，切换调用或轮次后重置为 0',
    incomplete       boolean  not null comment '历史统计是否存在缺失：0-未发现；1-调用缺失或事件已裁剪，生成时间无法完整恢复'
) engine=InnoDB comment '新 AI 会话历史消息统计可恢复回填任务表';
