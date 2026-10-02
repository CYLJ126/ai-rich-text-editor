package com.arte.ai.model.definition;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.schema.SchemaRef;

/**
 * 预定义流程的版本快照；流程结构单独引用，步骤及循环规则后续细化。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record WorkflowDefinition(
        DefinitionRef ref,
        SchemaRef inputSchema,
        SchemaRef outputSchema,
        ResourceRef graphRef,
        DefinitionStatus status
) {
}
