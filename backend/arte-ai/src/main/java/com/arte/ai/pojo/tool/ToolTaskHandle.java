package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 持久化异步工具任务的不可变状态快照。
 * <p>
 * 句柄是可序列化、可持久化的资源标识，不封装 Future、线程或内存回调。
 */
public record ToolTaskHandle(
        String taskId,
        String callId,
        ToolReference tool,
        Status status,
        Double progress,
        String progressMessage,
        String resumeToken,
        Instant createdAt,
        Instant updatedAt,
        long version,
        Map<String, Object> metadata
) {

    public enum Status {
        QUEUED,
        RUNNING,
        WAITING_APPROVAL,
        PAUSED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT
    }

    public ToolTaskHandle {
        requireText(taskId, "taskId");
        requireText(callId, "callId");
        Objects.requireNonNull(tool, "tool must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (progress != null && (!Double.isFinite(progress) || progress < 0.0 || progress > 1.0)) {
            throw new IllegalArgumentException("progress must be between 0.0 and 1.0");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }

    public boolean terminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED
                || status == Status.TIMED_OUT;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
