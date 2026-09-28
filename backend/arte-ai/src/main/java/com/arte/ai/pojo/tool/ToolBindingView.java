package com.arte.ai.pojo.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户/工作空间工具绑定的管理视图，包含禁用或暂不可用的绑定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ToolBindingView(
        String bindingId,
        String workspaceId,
        ToolReference tool,
        String credentialReference,
        Map<String, Object> configuration,
        ToolPolicyOverride policyOverride,
        boolean enabled,
        boolean available,
        long rowVersion
) {

    public ToolBindingView {
        configuration = configuration == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
    }
}
