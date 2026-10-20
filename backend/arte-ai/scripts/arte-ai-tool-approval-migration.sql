-- AI TOOL S3-01：先安装通用执行及 S2 受理表，停写、备份后显式执行一次。
-- 先运行 arte-ai-tool-approval-schema-check-mysql.sql；旧版同名表 request_id/call_id/status 不能复用。
-- 旧表保留归档并释放表名后再执行；不要用 IF NOT EXISTS 掩盖结构冲突，处理见 TOOL_APPROVAL.md。
-- 本脚本不开放审批 HTTP／写工具、不创建审批人；运行时不建表。
-- 所有共享库消费者升级到支持 WAITING_APPROVAL／tool-execution-snapshot v2 后才开放审批受理。
create table arte_ai_tool_approval
(
    invocation_key char(64)    not null comment '原 Invocation ID 哈希，一次调用一份审批' primary key,
    owner_key      char(64)    not null comment '原租户、空间、主体哈希，不因审批者改变',
    approval_key   char(64)    not null comment '稳定审批 ID 哈希',
    state          varchar(16) not null comment 'PENDING／APPROVED／REJECTED／EXPIRED／CANCELLED',
    version_no     bigint      not null comment '审批决定版本：初始 0，唯一决定后 1',
    expires_at     bigint      not null comment '固定审批截止 UTC epoch millis，不超过原 deadline 减执行余量',
    payload_digest char(64)    not null comment '实际审批内部快照字节 SHA-256',
    snapshot       longtext    not null comment 'tool-approval v1：固定摘要、模式、审批人及决定，无凭据值',
    constraint uk_tool_approval_id unique (approval_key),
    constraint fk_tool_approval_invocation foreign key (invocation_key) references arte_ai_invocation (id_key),
    index          ix_tool_approval_expiry (state, expires_at, invocation_key)
) engine=InnoDB comment 'AI TOOL 审批事实，与等待／排队／完成共用物理事务';
