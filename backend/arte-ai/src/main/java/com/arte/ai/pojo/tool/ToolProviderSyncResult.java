package com.arte.ai.pojo.tool;

import java.time.Instant;

/**
 * 工具提供者同步结果
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public record ToolProviderSyncResult(
        String providerId,
        boolean succeeded,
        int discoveredCount,
        int createdCount,
        int updatedCount,
        int activatedCount,
        int disabledCount,
        Instant completedAt,
        String errorMessage
) {
}
