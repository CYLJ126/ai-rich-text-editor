package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用过程中的追加式事件。
 * <p>
 * 参数和输出可能包含敏感信息，默认只应记录标识、状态与低基数属性。
 */
public record ToolExecutionEvent(
        String eventId,
        Type type,
        Instant occurredAt,
        String traceId,
        String spanId,
        String callId,
        ToolReference tool,
        Map<String, Object> attributes
) {

    public enum Type {
        REQUESTED,
        RESOLVED,
        VALIDATED,
        AUTHORIZED,
        GUARDRAIL_EVALUATED,
        APPROVAL_REQUESTED,
        APPROVAL_APPROVED,
        APPROVAL_REJECTED,
        APPROVAL_EXPIRED,
        TASK_QUEUED,
        STARTED,
        TASK_PROGRESS_CHANGED,
        RETRIED,
        SUCCEEDED,
        FAILED,
        DENIED,
        PAUSED,
        RESUMED,
        CANCELLED,
        TIMED_OUT
    }

    public ToolExecutionEvent {
        requireText(eventId, "eventId");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        requireText(traceId, "traceId");
        requireText(callId, "callId");
        Objects.requireNonNull(tool, "tool must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
