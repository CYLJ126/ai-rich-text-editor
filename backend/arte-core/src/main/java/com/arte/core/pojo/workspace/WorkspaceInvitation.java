package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.InvitationStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 针对已有有效账户的空间邀请；角色仅 MEMBER／GUEST，期限及邀请者资格在接受时重检。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspaceInvitation(
        String id,
        Scope scope,
        String targetSubjectId,
        WorkspaceRoleEnum role,
        InvitationStateEnum state,
        Instant expiresAt,
        String issuedBy,
        String acceptedMemberId,
        long version) {
    public WorkspaceInvitation {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(targetSubjectId, "targetSubjectId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(expiresAt, "expiresAt");
        PlatformChecks.id(issuedBy, "issuedBy");
        if (acceptedMemberId != null) PlatformChecks.id(acceptedMemberId, "acceptedMemberId");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(role == WorkspaceRoleEnum.MEMBER || role == WorkspaceRoleEnum.GUEST, "invitation cannot grant governance role");
        PlatformChecks.require((state == InvitationStateEnum.ACCEPTED) == (acceptedMemberId != null), "accepted member must match invitation state");
    }
}
