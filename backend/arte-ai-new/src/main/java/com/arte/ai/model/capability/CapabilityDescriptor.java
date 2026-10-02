package com.arte.ai.model.capability;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.schema.SchemaRef;

import java.util.Set;

/**
 * 操作的类型化契约及能力特性；发现不等于获准使用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record CapabilityDescriptor(
        DefinitionRef ref,
        CapabilityKind kind,
        SchemaRef inputSchema,
        SchemaRef outputSchema,
        Set<String> features,
        SideEffectKind sideEffectKind
) {
}
