package com.arte.ai.model.definition;

import com.arte.base.model.identity.ExecutionScope;

import java.util.Set;

/**
 * 限定租户、空间、主体及允许操作的能力使用配置；不替代资源授权。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record BindingDefinition(
        DefinitionRef ref,
        ExecutionScope scope,
        DefinitionRef capabilityRef,
        DefinitionRef connectionRef,
        Set<String> allowedOperations,
        DefinitionStatus status
) {
}
