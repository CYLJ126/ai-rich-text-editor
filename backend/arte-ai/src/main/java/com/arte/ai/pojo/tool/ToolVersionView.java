package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;

import java.time.LocalDateTime;

/**
 * 工具固定版本的只读视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolVersionView(
        ToolReference reference,
        String toolId,
        String title,
        String description,
        ToolLifecycleStateEnum lifecycleState,
        String checksum,
        long rowVersion,
        LocalDateTime publishedAt,
        String compatibilityBaseVersion,
        String releaseNotes
) {
}
