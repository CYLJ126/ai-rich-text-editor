package com.arte.base.model.job;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;

import java.time.Instant;

/**
 * 可恢复工作项的状态快照；实际领域处理器按 handlerKey 接入，不承载 AI 专有状态。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Job(
        String jobId,
        String handlerKey,
        ExecutionContext context,
        ResourceRef inputRef,
        JobStatus status,
        Instant scheduledAt
) {
}
