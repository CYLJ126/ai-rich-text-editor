package com.arte.ai.pojo.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工作流程层错误，可保留导致节点失败的底层工具错误。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowError(
        String code,
        Category category,
        String nodeId,
        String message,
        ToolError toolError,
        Map<String, Object> details
) {

    public enum Category {
        VALIDATION,
        COMPILATION,
        NODE,
        TOOL,
        MODEL,
        POLICY,
        APPROVAL,
        TIMEOUT,
        CANCELLED,
        INTERNAL
    }

    public WorkflowError {
        requireText(code, "code");
        Objects.requireNonNull(category, "category must not be null");
        requireText(message, "message");
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
