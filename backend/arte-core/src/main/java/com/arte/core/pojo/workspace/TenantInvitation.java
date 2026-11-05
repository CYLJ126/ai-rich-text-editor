package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.InvitationStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 仅加入组织租户的正式成员邀请，不包含任何空间。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 21:08 ✾
 */
public record TenantInvitation(String id, String tenantId, String targetSubjectId, InvitationStateEnum state,
                               Instant expiresAt, String issuedBy, String acceptedMemberId, long version) {

    public TenantInvitation {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(tenantId, "tenantId");
        PlatformChecks.id(targetSubjectId, "targetSubjectId");
        PlatformChecks.id(issuedBy, "issuedBy");
        Objects.requireNonNull(state);
        Objects.requireNonNull(expiresAt);
        PlatformChecks.version(version, "version");
        PlatformChecks.require((state == InvitationStateEnum.ACCEPTED) == (acceptedMemberId != null), "invalid invitation member");
    }
}
