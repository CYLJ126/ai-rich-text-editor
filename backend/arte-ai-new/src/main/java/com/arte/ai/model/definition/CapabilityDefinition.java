package com.arte.ai.model.definition;

import com.arte.ai.model.capability.CapabilityDescriptor;

/**
 * 能力定义的版本快照，区别于连接及主体使用绑定。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record CapabilityDefinition(
        CapabilityDescriptor descriptor,
        DefinitionStatus status
) {
}
