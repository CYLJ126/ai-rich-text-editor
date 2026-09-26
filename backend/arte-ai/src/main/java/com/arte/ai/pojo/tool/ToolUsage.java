package com.arte.ai.pojo.tool;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 一次工具调用的用量与成本摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolUsage(
        Instant startedAt,
        Instant completedAt,
        Duration duration,
        Long inputTokens,
        Long outputTokens,
        BigDecimal cost,
        String currency
) {

    public ToolUsage {
        if (startedAt == null || completedAt == null || duration == null) {
            throw new IllegalArgumentException("time information must not be null");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        if (inputTokens != null && inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative");
        }
        if (outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative");
        }
    }
}
