package com.arte.base.model.execution;

import com.arte.base.model.resource.ResourceRef;

import java.time.Instant;

/**
 * 带单调序号的通用执行事件；类型化负载由所属模块扩展，重放不重新执行。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ExecutionEvent<T>(
        String executionId,
        String attemptId,
        long sequence,
        String eventType,
        Instant occurredAt,
        T payload,
        ResourceRef payloadRef
) {
}
