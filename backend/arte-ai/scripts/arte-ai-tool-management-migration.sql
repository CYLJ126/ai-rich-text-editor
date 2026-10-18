-- AI TOOL S1-08：完整本地目录刷新命令与不可变审计；在 S1-04/06/07 后显式部署。
-- 停写、备份、确认目标表不存在；运行时不执行 DDL，不回填发布、不创建绑定。
create table arte_ai_tool_discovery_command
(
    command_key    char(64) not null comment '实际主体、类别与幂等键的长度前缀 SHA-256' primary key,
    request_digest char(64) not null comment '完整本地刷新意图、可信 Scope 与原因的语义摘要',
    payload_digest char(64) not null comment '原始白名单审计回执的字节摘要',
    snapshot       longtext not null comment 'tool-discovery-receipt v1；首次观察、原始决定、实际操作者和原因，不包含目录内容或秘密'
) engine=InnoDB comment 'AI TOOL 完整本地目录刷新管理防重与不可变审计';
