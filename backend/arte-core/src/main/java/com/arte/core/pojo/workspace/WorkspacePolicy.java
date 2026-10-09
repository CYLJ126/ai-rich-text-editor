package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 空间政策；必须在租户上限内，更新与 workspaceEpoch 同事务提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspacePolicy(
        Scope scope,
        PolicyRules rules,
        int policySchemaVersion,
        int rolePresetVersion,
        long version) {
    public WorkspacePolicy {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(rules, "rules");
        PlatformChecks.require(policySchemaVersion >= 1, "policySchemaVersion must be positive");
        PlatformChecks.require(rolePresetVersion >= 1, "rolePresetVersion must be positive");
        PlatformChecks.version(version, "version");
    }
}
