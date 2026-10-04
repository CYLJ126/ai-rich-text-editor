-- 新安装：在聊天初始化脚本之后执行。本表不授予资料权限，过期不自动删除正文。
create table if not exists arte_ai_new_retrieval_preview
(
    preview_id           varchar(64) not null comment '服务端检索预览标识，与固定资料快照标识相同',
    scope_key            varchar(71) not null comment '租户、工作区及当前主体的 SHA-256 摘要，禁止跨主体读取预览',
    conversation_id      varchar(64) not null comment '预览所属新聊天会话标识，提交必须与此会话一致',
    conversation_version bigint not null comment '预览时的会话版本，提交须与本版本一致；单位为版本次数',
    request_digest       varchar(71) not null comment '问题、会话版本及模型参数的 SHA-256 摘要，用于阻止预览后替换问题',
    context_json         json not null comment 'arte.resource.context.v1 固定资料快照，含实际正文或 ES 片段、来源版本、摘要、消息和预算',
    created_at           timestamp(6) not null default current_timestamp(6) comment '预览创建时间，UTC，精度为微秒',
    expires_at           timestamp(6) not null default current_timestamp(6) comment '新提交允许使用预览的截止时间，UTC；到期仍保留已受理请求的幂等恢复数据',
    primary key (preview_id),
    key ix_ai_new_preview_scope (scope_key, conversation_id),
    constraint fk_ai_new_preview_conversation foreign key (conversation_id, scope_key)
        references arte_ai_new_conversation (conversation_id, scope_key),
    constraint ck_ai_new_preview_expiry check (expires_at > created_at),
    constraint ck_ai_new_preview_version check (conversation_version > 0)
) engine = InnoDB default charset = utf8mb4 collate = utf8mb4_bin
    comment '新聊天文章检索预览；保存发送前确认的固定输入，正文读取和外发均须实时授权';
