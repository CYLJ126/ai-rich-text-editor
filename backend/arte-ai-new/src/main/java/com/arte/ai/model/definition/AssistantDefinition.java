package com.arte.ai.model.definition;

import java.util.List;

/**
 * 助手复用定义的版本快照；实际执行记录解析后的发布版本。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record AssistantDefinition(
        DefinitionRef ref,
        ChatProfile profile,
        List<DefinitionRef> skills,
        DefinitionStatus status
) {
}
