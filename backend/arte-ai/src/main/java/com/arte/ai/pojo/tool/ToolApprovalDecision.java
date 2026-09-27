package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Objects;

/**
 * 人工审批结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolApprovalDecision(
        String requestId,
        boolean approved,
        String approverId,
        String reason,
        Instant decidedAt
) {

    public ToolApprovalDecision {
        requireText(requestId, "requestId");
        requireText(approverId, "approverId");
        Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        if (!approved && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("reason must not be blank when approval is rejected");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
