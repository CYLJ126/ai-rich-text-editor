-- 已有实例：先停止模型 Worker，执行这两个字段变更，再执行 arte-ai-new-rag-ddl-mysql.sql。
-- 新安装的聊天初始化脚本已经包含这两个字段，不要再执行本升级脚本。
alter table arte_ai_new_turn
    add column resource_context_json json null comment '本轮已确认的检索预览快照 JSON，arte.resource.context.v1；普通文本或重新生成时为空';

alter table arte_ai_new_context_snapshot
    add column resource_context_json json null comment '固定资料快照 JSON，arte.resource.context.v1；普通文本无资料时为空，摘要覆盖实际输入和来源';
