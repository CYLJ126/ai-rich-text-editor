package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可安全返回给审批页面的工具审批视图，只包含脱敏参数和参数摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record ToolApprovalView(
        long id,
        String requestId,
        String callId,
        String taskId,
        String workflowRunId,
        String toolId,
        String toolVersion,
        String argumentsDigest,
        Map<String, Object> displayArguments,
        String summary,
        String status,
        String approverId,
        String decisionReason,
        Instant expiresAt,
        Instant decidedAt,
        Instant createTime,
        Instant updateTime
) {
    public ToolApprovalView {
        displayArguments = displayArguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(displayArguments));
    }
}
