package com.arte.ainew.common.execution;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变、可持久化的执行上下文；仅由可信入口或经过重新授权的 Worker 构造。
 * 不含 Authentication、凭据、线程、连接、取消信号或任何 AI SDK 类型。
 * 引用字段不自动授予预算、发布配置或资源使用权，执行边界仍需校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionContext(
        String executionId, String traceId, ExecutionAuthorization authorization,
        Instant deadline, String parentExecutionId, String budgetRef,
        String releaseRef, String idempotencyKey) implements Serializable {

    public ExecutionContext {
        ExecutionPrincipal.requireText(executionId, "executionId");
        ExecutionPrincipal.requireText(traceId, "traceId");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(deadline, "deadline");
        optionalText(parentExecutionId, "parentExecutionId");
        optionalText(budgetRef, "budgetRef");
        optionalText(releaseRef, "releaseRef");
        optionalText(idempotencyKey, "idempotencyKey");
        if (executionId.equals(parentExecutionId)) {
            throw new IllegalArgumentException("Execution cannot be its own parent");
        }
    }

    /** 新执行身份与操作幂等键由调用方分配；子执行共享追踪和预算且不能延长期限。 */
    public ExecutionContext child(String childId, Instant childDeadline,
                                  Set<String> scopes, String childIdempotencyKey) {
        if (Objects.requireNonNull(childDeadline, "childDeadline").isAfter(deadline)) {
            throw new IllegalArgumentException("Child deadline exceeds parent deadline");
        }
        return new ExecutionContext(childId, traceId, authorization.restrictTo(scopes),
                childDeadline, executionId, budgetRef, releaseRef, childIdempotencyKey);
    }

    private static void optionalText(String value, String field) {
        if (value != null) {
            ExecutionPrincipal.requireText(value, field);
        }
    }
}
