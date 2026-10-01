package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具固定版本的完整管理视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolVersionDetailView(
        ToolReference reference,
        String toolId,
        String providerId,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> capabilities,
        Map<String, Object> riskProfile,
        Map<String, Object> defaultConfiguration,
        Map<String, Object> defaultPolicy,
        Set<String> tags,
        String checksum,
        ToolLifecycleStateEnum lifecycleState,
        long rowVersion,
        LocalDateTime publishedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        String compatibilityBaseVersion,
        String releaseNotes
) {
    public ToolVersionDetailView {
        inputSchema = immutable(inputSchema);
        outputSchema = immutable(outputSchema);
        capabilities = immutable(capabilities);
        riskProfile = immutable(riskProfile);
        defaultConfiguration = immutable(defaultConfiguration);
        defaultPolicy = immutable(defaultPolicy);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
