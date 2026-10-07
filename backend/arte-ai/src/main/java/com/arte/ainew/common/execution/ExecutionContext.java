package com.arte.ainew.common.execution;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变、可持久化的执行上下文
 * <p>
 * 仅由可信入口或经过重新授权的 Worker 构造。
 * 不含 Authentication、凭据、线程、连接、取消信号或任何 AI SDK 类型。
 * 引用字段不自动授予预算、发布配置或资源使用权，执行边界仍需校验。
 *
 * @param executionId       本次逻辑执行的唯一标识，不能为空；用于关联 Invocation 等执行记录，重新授权或恢复执行时保留。
 * @param traceId           追踪标识，不能为空；用于串联日志和执行链路，子执行共享此标识，与执行 ID、操作幂等键相互独立。
 * @param authorization     当前执行的授权快照，不能为空；包含主体、租户、工作空间、权限范围及授权引用，执行边界仍需重新校验。
 * @param deadline          本次执行的总截止时间，不能为空；包含授权、排队和实际执行所消耗的时间，恢复执行或创建子执行不能延长期限。
 * @param parentExecutionId 父执行的 ID，用于关联派生执行；根执行通常为 null，非 null 时不能等于 executionId。
 * @param budgetRef         本次执行使用的费用预算账户引用，可为 null；实际调用是否必填、账户归属及可用额度由业务边界校验，子执行继承此引用。
 * @param releaseRef        本次执行关联的发布配置引用，可为 null；用于固定发布选择并参与请求语义校验，不自动授予配置使用权，子执行继承此引用。
 * @param idempotencyKey    本次操作的幂等键，可为 null；需要可靠受理的入口要求提供，并结合主体、能力及请求语义判定重放或冲突，子执行使用单独分配的键。
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

    /**
     * 新执行身份与操作幂等键由调用方分配；子执行共享追踪和预算且不能延长期限。
     */
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
