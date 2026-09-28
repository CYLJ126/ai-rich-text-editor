package com.arte.ai.pojo.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已完成版本锁定、配置合并、凭据解析和安全策略收紧的工具绑定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ResolvedToolBinding(
        String bindingId,
        String ownerId,
        String workspaceId,
        String providerId,
        String toolId,
        ToolReference tool,
        Map<String, Object> effectiveConfiguration,
        String credentialReference,
        ToolExecutionPolicy effectivePolicy,
        boolean enabled,
        long rowVersion
) {

    public ResolvedToolBinding {
        effectiveConfiguration = effectiveConfiguration == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(effectiveConfiguration));
    }
}
