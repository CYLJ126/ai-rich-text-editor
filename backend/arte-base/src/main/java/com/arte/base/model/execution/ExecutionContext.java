package com.arte.base.model.execution;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;

import java.time.Instant;
import java.util.Set;

/**
 * 服务端构建的执行上下文；预算及发布使用公共引用，不导入 AI 专有类型。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ExecutionContext(
        ExecutionScope scope,
        String traceId,
        String parentExecutionId,
        Instant deadline,
        CancellationRef cancellation,
        Set<String> authorizationScopes,
        ResourceRef budgetRef,
        ResourceRef releaseRef,
        IdempotencyKey idempotencyKey
) {
}
