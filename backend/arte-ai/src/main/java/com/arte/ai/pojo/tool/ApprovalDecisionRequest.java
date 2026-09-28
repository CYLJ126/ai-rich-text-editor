package com.arte.ai.pojo.tool;

/**
 * 人工审批操作请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ApprovalDecisionRequest(boolean approved, String reason) {

    private static final int MAX_REASON_LENGTH = 1000;

    public ApprovalDecisionRequest {
        reason = reason == null || reason.isBlank() ? null : reason.trim();
        if (!approved && reason == null) {
            throw new IllegalArgumentException("a rejection reason is required");
        }
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("approval reason must not exceed 1000 characters");
        }
    }
}
