package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 用于失败恢复、审批暂停和长时间任务续跑的工作流程检查点。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowCheckpoint(
        String checkpointId,
        String runId,
        long sequence,
        Map<String, Object> state,
        Instant createdAt
) {

    public WorkflowCheckpoint {
        requireText(checkpointId, "checkpointId");
        requireText(runId, "runId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        state = Map.copyOf(Objects.requireNonNull(state, "state must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
