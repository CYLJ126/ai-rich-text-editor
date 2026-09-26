package com.arte.ai.pojo.tool;

import java.util.Set;

/**
 * 工具的静态风险画像。服务器或远程工具传回的风险声明不应被默认信任。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolRiskProfile(
        RiskLevel level,
        boolean readOnly,
        boolean destructive,
        boolean reversible,
        boolean idempotent,
        boolean openWorld,
        Set<String> requiredScopes,
        Set<String> allowedNetworkTargets
) {

    enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public ToolRiskProfile {
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        allowedNetworkTargets = allowedNetworkTargets == null
                ? Set.of()
                : Set.copyOf(allowedNetworkTargets);
        if (readOnly && destructive) {
            throw new IllegalArgumentException("a read-only tool cannot be destructive");
        }
    }
}
