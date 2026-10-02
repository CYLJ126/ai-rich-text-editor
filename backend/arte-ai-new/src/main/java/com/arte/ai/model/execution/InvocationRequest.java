package com.arte.ai.model.execution;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 类型化单次调用信封；I 保留能力输入类型，不携带明文凭据或任意远端地址。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record InvocationRequest<I>(
        DefinitionRef capabilityRef,
        DefinitionRef bindingRef,
        I input,
        ExecutionOptions options,
        ExecutionContext context
) {
}
