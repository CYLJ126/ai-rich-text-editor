package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 租户成员关系。撤销后保留，重新加入增加代次，不自动退化为访客。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record TenantMembership(
        String id,
        String tenantId,
        String subjectId,
        TenantRoleEnum role,
        MembershipStateEnum state,
        long generation,
        long version) {
    public TenantMembership {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(tenantId, "tenantId");
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(generation, "generation");
        PlatformChecks.version(version, "version");
    }
}
