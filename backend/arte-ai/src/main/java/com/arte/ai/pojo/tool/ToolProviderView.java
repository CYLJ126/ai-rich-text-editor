package com.arte.ai.pojo.tool;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具提供者管理视图，不返回凭据引用或其他敏感字段。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolProviderView(
        String providerId,
        String name,
        String providerType,
        String endpoint,
        Map<String, Object> configuration,
        String status,
        boolean loaded,
        boolean supportsStartupRefresh,
        long toolCount,
        LocalDateTime lastSyncTime,
        String lastError
) {
    public ToolProviderView {
        configuration = configuration == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
