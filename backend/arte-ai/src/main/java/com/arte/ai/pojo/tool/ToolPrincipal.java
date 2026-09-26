package com.arte.ai.pojo.tool;

import java.util.Set;

/**
 * 调用工具的主体。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolPrincipal(
        String ownerId,
        String subjectId,
        Set<String> roles,
        Set<String> scopes
) {

    public ToolPrincipal {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId must not be blank");
        }
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
