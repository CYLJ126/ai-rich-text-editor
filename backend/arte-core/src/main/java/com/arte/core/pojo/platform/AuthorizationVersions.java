package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 当前范围授权依据快照
 * <p>
 * 仅租户授权的空间 epoch／成员代次／成员版本均为空。
 * ACL／资源版本不适用时为空，不是永久执行凭据。
 * <p>
 * long 表示租户授权必有的版本：租户纪元、租户成员代次和成员版本。Long 允许用 null 表示本次授权不涉及该项：仅租户授权时，三个空间版本同时为空；
 * 未涉及资源 ACL 或资源版本时，对应字段也可为空。0 是有效的初始版本，不能代替“无此版本”。
 *
 * @param tenantEpoch                   租户授权纪元；租户政策或授权依据变更时推进，始终存在。
 * @param workspaceEpoch                空间授权纪元；仅涉及空间授权时存在，须与两个空间成员版本同时存在或同时为空。
 * @param tenantMembershipGeneration    租户成员关系的代次；用于区分退出后重新加入等不同成员关系，始终存在。
 * @param tenantMembershipVersion       当前租户成员关系的版本；成员状态、角色等变更时更新，始终存在。
 * @param workspaceMembershipGeneration 空间成员关系的代次；仅涉及空间授权时存在。
 * @param workspaceMembershipVersion    当前空间成员关系的版本；仅涉及空间授权时存在。
 * @param aclVersion                    资源 ACL 的版本；本次授权涉及资源 ACL 时记录，否则为空。
 * @param resourceVersion               被授权资源的版本；本次授权涉及资源版本时记录，否则为空，租户或空间授权使用对应实体的版本。
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
