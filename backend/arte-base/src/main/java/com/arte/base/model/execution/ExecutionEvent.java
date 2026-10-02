package com.arte.base.model.execution;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 通用执行事件。执行、类型、时间必填，attemptId 可为空，序号非负。
 * 内联 payload 与 payloadRef 必须且只能提供一个，泛型负载的不可变性由所属模块保证。
 * 单调性、唯一序号、耐久性及按游标重放由事件存储保证，值对象不推进执行状态。
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

    public ExecutionEvent {
        executionId = ContractChecks.identifier(executionId, "executionId");
        attemptId = ContractChecks.optionalIdentifier(attemptId, "attemptId");
        eventType = ContractChecks.identifier(eventType, "eventType");
        occurredAt = ContractChecks.required(occurredAt, "occurredAt");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if ((payload == null) == (payloadRef == null)) {
            throw new IllegalArgumentException("exactly one of payload and payloadRef is required");
        }
    }
}
