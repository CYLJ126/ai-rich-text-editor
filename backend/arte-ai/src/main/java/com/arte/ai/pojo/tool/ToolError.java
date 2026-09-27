package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.CategoryEnum;

import java.util.Map;

/**
 * 标准化工具错误，用于区分可重试的技术错误、业务失败和安全拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolError(
        String code,
        CategoryEnum category,
        String message,
        boolean retryable,
        Map<String, Object> details
) {

    public ToolError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
