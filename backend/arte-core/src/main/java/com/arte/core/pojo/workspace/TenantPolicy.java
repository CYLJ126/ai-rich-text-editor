package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 租户政策权威。更新与 tenantEpoch 同事务提交，不能随程序升级隐式扩权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TenantPolicy(
        String tenantId,
        PolicyRules rules,
        boolean membersMayCreateWorkspace,
        int policySchemaVersion,
        int rolePresetVersion,
        long version) {
    public TenantPolicy {
        PlatformChecks.id(tenantId, "tenantId");
        Objects.requireNonNull(rules, "rules");
        PlatformChecks.require(policySchemaVersion >= 1, "policySchemaVersion must be positive");
        PlatformChecks.require(rolePresetVersion >= 1, "rolePresetVersion must be positive");
        PlatformChecks.version(version, "version");
    }
}
