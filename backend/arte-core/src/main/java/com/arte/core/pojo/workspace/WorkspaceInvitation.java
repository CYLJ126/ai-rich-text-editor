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
 * @param id 空间邀请记录 ID
 * @param scope 目标空间范围，包含租户 ID 和空间 ID
 * @param targetSubjectId 受邀账号的主体 ID
 * @param role 接受邀请后授予的空间角色，限 {@code MEMBER} 或 {@code GUEST}
 * @param state 邀请当前状态
 * @param expiresAt 邀请到期时间
 * @param issuedBy 发出邀请的主体 ID
 * @param acceptedMemberId 接受邀请后的成员关系记录 ID，尚未接受时为 {@code null}
 * @param version 邀请记录版本
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
