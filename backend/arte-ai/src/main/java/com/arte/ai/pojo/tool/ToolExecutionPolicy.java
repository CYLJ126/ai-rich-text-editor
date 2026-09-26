package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;

import java.time.Duration;

/**
 * 工具的默认执行策略。绑定、工作流程节点或单次调用可在安全允许的范围内覆盖它。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolExecutionPolicy(
        ToolExecutionModeEnum executionMode,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        int maxOutputTokens,
        boolean requiresApproval,
        boolean allowsResultCache
) {

    public ToolExecutionPolicy {
        if (executionMode == null) {
            throw new IllegalArgumentException("executionMode must not be null");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
    }
}
