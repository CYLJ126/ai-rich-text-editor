package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.InvitationStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 仅加入组织租户的正式成员邀请，不包含任何空间。
 *
 * @param id 租户邀请记录 ID
 * @param tenantId 目标租户 ID
 * @param targetSubjectId 受邀账号的主体 ID
 * @param state 邀请当前状态
 * @param expiresAt 邀请到期时间
 * @param issuedBy 发出邀请的主体 ID
 * @param acceptedMemberId 接受邀请后的成员关系记录 ID，尚未接受时为 {@code null}
 * @param version 邀请记录版本
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
