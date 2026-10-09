package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 当前范围授权依据快照
 * <p>
 * 仅租户授权的空间 epoch／成员代次／成员版本均为空。
 * ACL／资源版本不适用时为空，不是永久执行凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AuthorizationVersions(
        long tenantEpoch,
        Long workspaceEpoch,
        long tenantMembershipGeneration,
        long tenantMembershipVersion,
        Long workspaceMembershipGeneration,
        Long workspaceMembershipVersion,
        Long aclVersion,
        Long resourceVersion) {

    public AuthorizationVersions {
        PlatformChecks.version(tenantEpoch, "tenantEpoch");
        PlatformChecks.require((workspaceEpoch == null) == (workspaceMembershipGeneration == null)
                && (workspaceEpoch == null) == (workspaceMembershipVersion == null), "workspace versions must be present together");
        if (workspaceEpoch != null) PlatformChecks.version(workspaceEpoch, "workspaceEpoch");
        PlatformChecks.version(tenantMembershipGeneration, "tenantMembershipGeneration");
        PlatformChecks.version(tenantMembershipVersion, "tenantMembershipVersion");
        if (workspaceMembershipGeneration != null)
            PlatformChecks.version(workspaceMembershipGeneration, "workspaceMembershipGeneration");
        if (workspaceMembershipVersion != null)
            PlatformChecks.version(workspaceMembershipVersion, "workspaceMembershipVersion");
        if (aclVersion != null) PlatformChecks.version(aclVersion, "aclVersion");
        if (resourceVersion != null) PlatformChecks.version(resourceVersion, "resourceVersion");
    }
}
