package com.arte.ai.pojo.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建或更新当前用户工具绑定的命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolBindingCommand(
        String bindingId,
        String workspaceId,
        ToolReference tool,
        String credentialReference,
        Map<String, Object> configuration,
        ToolPolicyOverride policyOverride,
        Boolean enabled,
        Long expectedRowVersion,
        String versionPolicy
) {

    public ToolBindingCommand {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        configuration = configuration == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
        if (expectedRowVersion != null && expectedRowVersion < 0) {
            throw new IllegalArgumentException("expectedRowVersion must not be negative");
        }
        if (versionPolicy != null && !java.util.Set.of("follow-compatible", "pinned").contains(versionPolicy)) {
            throw new IllegalArgumentException("versionPolicy must be follow-compatible or pinned");
        }
    }
}
