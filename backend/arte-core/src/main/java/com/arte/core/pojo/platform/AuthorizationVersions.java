package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 当前范围授权依据快照；ACL／资源版本不适用时为空，不是永久执行凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AuthorizationVersions(
        long tenantEpoch,
        long workspaceEpoch,
        long tenantMembershipGeneration,
        long tenantMembershipVersion,
        long workspaceMembershipGeneration,
        long workspaceMembershipVersion,
        Long aclVersion,
        Long resourceVersion) {
    public AuthorizationVersions {
        PlatformChecks.version(tenantEpoch, "tenantEpoch");
        PlatformChecks.version(workspaceEpoch, "workspaceEpoch");
        PlatformChecks.version(tenantMembershipGeneration, "tenantMembershipGeneration");
        PlatformChecks.version(tenantMembershipVersion, "tenantMembershipVersion");
        PlatformChecks.version(workspaceMembershipGeneration, "workspaceMembershipGeneration");
        PlatformChecks.version(workspaceMembershipVersion, "workspaceMembershipVersion");
        if (aclVersion != null) PlatformChecks.version(aclVersion, "aclVersion");
        if (resourceVersion != null) PlatformChecks.version(resourceVersion, "resourceVersion");
    }
}
