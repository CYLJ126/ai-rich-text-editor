package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;

import java.time.Duration;

/**
 * 工具执行策略的部分覆盖；空字段表示继承上一层策略。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolPolicyOverride(
        ToolExecutionModeEnum executionMode,
        Duration timeout,
        Integer maxRetries,
        Duration retryBackoff,
        Integer maxOutputTokens,
        Boolean requiresApproval,
        Boolean allowsResultCache
) {

    public ToolPolicyOverride {
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxRetries != null && maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        if (retryBackoff != null && retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }
    }
}
