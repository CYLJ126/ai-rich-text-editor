-- 已有实例先停止旧 Worker，再执行本脚本并更新后端；新安装的模型初始化脚本已包含此字段。
alter table arte_ai_new_execution
    add column resource_context_json mediumtext null comment '固定资料上下文 JSON，含实际来源、范围、预算与摘要；纯文本旧调用为空，读取及外发须重新授权';

alter table arte_ai_new_action
    modify column payload_json mediumtext not null comment '不可变动作输入 JSON，v1 为纯文本，v2 含固定资料快照；含指令、要求、定义版本及可空再生成来源，不存储凭据';
