package com.arte.ai.pojo.tool;

import java.util.Map;

/**
 * 基于调用明细实时计算的一期统计摘要。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolCallStatistics(long total, long succeeded, long failed, long denied,
                                 double successRate, double averageLatencyMs,
                                 long inputTokens, long outputTokens, long retries,
                                 Map<String, Long> failureReasons) {
}
