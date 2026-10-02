package com.arte.ai.model.execution;

import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.resource.ResourceRef;

/**
 * 逻辑能力调用的记录快照；与尝试、工作项及多步运行分开。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Invocation<I>(
        String invocationId,
        InvocationRequest<I> request,
        ExecutionStatus status,
        String jobId,
        String runId,
        ResourceRef resultRef,
        ExecutionError error
) {
}
