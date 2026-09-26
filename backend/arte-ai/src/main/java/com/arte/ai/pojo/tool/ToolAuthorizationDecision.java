package com.arte.ai.pojo.tool;

import java.util.Set;

/**
 * 工具授权决策。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolAuthorizationDecision(boolean allowed, String reason, Set<String> missingScopes) {

    public ToolAuthorizationDecision {
        missingScopes = missingScopes == null ? Set.of() : Set.copyOf(missingScopes);
        if (allowed && !missingScopes.isEmpty()) {
            throw new IllegalArgumentException("an allowed decision cannot contain missing scopes");
        }
    }
}
