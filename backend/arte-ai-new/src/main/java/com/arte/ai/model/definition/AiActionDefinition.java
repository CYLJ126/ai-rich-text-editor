package com.arte.ai.model.definition;

import com.arte.base.model.schema.SchemaRef;

/**
 * 通用动作定义；场景及输出契约可扩展，文章操作由组合模块注册。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record AiActionDefinition(
        DefinitionRef ref,
        String scenarioId,
        String instruction,
        SchemaRef inputSchema,
        SchemaRef outputSchema,
        DefinitionStatus status
) {
}
