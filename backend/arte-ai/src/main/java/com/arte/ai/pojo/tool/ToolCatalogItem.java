package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;

import java.time.LocalDateTime;

/**
 * 管理端工具目录条目，包含草稿、废弃和禁用工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolCatalogItem(
        String toolId,
        String namespace,
        String name,
        String providerId,
        String title,
        String description,
        String latestVersion,
        ToolLifecycleStateEnum lifecycleState,
        long versionCount,
        LocalDateTime updateTime
) {
}
