package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 需要人工确认的工具调用快照。
 * <p>
 * argumentsDigest 必须与继续执行时的参数匹配，防止审批后参数被替换。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolApprovalRequest(
        String requestId,
        String callId,
        ToolReference tool,
        String argumentsDigest,
        String summary,
        Instant expiresAt,
        Map<String, Object> displayArguments
) {

    public ToolApprovalRequest {
        requireText(requestId, "requestId");
        requireText(callId, "callId");
        Objects.requireNonNull(tool, "tool must not be null");
        requireText(argumentsDigest, "argumentsDigest");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        displayArguments = displayArguments == null ? Map.of() : Map.copyOf(displayArguments);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
