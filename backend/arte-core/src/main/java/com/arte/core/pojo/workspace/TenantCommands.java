package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 租户治理写命令
 * <p>
 * 组织创建受独立 provisioning policy 控制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class TenantCommands {

    private TenantCommands() {
    }

    /**
     * 创建组织，当前主体为初始租户负责人，不隐式读取空间正文。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param name 新组织租户的展示名称
     */
    public record CreateOrganization(CommandMeta meta, String name) {

        public CreateOrganization {
            Objects.requireNonNull(meta, "meta");
            name = PlatformChecks.name(name);
        }
    }

    /**
     * 只修改租户展示名称，保留托管主体、类型和生命周期。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     * @param name 更新后的租户展示名称
     */
    public record UpdateTenant(CommandMeta meta, String tenantId, long expectedTenantVersion, String name) {
        public UpdateTenant {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
            name = PlatformChecks.name(name);
        }
    }

    /**
     * 变更租户成员的角色。负责人角色通过专用交接命令授予。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param memberId 目标成员关系记录的 ID
     * @param expectedMemberVersion 执行前须匹配的成员关系记录版本
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     * @param role 目标成员的新租户角色
     */
    public record ChangeTenantMemberRole(
            CommandMeta meta,
            String tenantId,
            String memberId,
            long expectedMemberVersion,
            long expectedTenantVersion,
            TenantRoleEnum role) {

        public ChangeTenantMemberRole {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.version(expectedMemberVersion, "expectedMemberVersion");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
            Objects.requireNonNull(role, "role");
            PlatformChecks.require(role != TenantRoleEnum.OWNER, "OWNER requires transfer command");
        }
    }

    /**
     * 移除租户成员并使其空间资格失效。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param memberId 目标成员关系记录的 ID
     * @param expectedMemberVersion 执行前须匹配的成员关系记录版本
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     */
    public record RemoveTenantMember(
            CommandMeta meta,
            String tenantId,
            String memberId,
            long expectedMemberVersion,
            long expectedTenantVersion) {

        public RemoveTenantMember {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.version(expectedMemberVersion, "expectedMemberVersion");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
        }
    }

    /**
     * 负责人原子交接，个人托管账户关系仍受独立限制。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param targetMemberId 接任负责人的成员关系记录 ID
     * @param expectedOwnerMemberVersion 执行前须匹配的现任负责人关系记录版本
     * @param expectedTargetMemberVersion 执行前须匹配的接任者关系记录版本
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     */
    public record TransferTenantOwner(
            CommandMeta meta,
            String tenantId,
            String targetMemberId,
            long expectedOwnerMemberVersion,
            long expectedTargetMemberVersion,
            long expectedTenantVersion) {

        public TransferTenantOwner {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.id(targetMemberId, "targetMemberId");
            PlatformChecks.version(expectedOwnerMemberVersion, "expectedOwnerMemberVersion");
            PlatformChecks.version(expectedTargetMemberVersion, "expectedTargetMemberVersion");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
        }
    }

    /**
     * 同事务更新租户政策及 epoch，不接受客户端提升角色预设版本。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     * @param expectedPolicyVersion 执行前须匹配的租户政策版本
     * @param rules 更新后的租户政策规则
     * @param membersMayCreateWorkspace 是否允许普通租户成员创建空间
     */
    public record UpdateTenantPolicy(
            CommandMeta meta,
            String tenantId,
            long expectedTenantVersion,
            long expectedPolicyVersion,
            PolicyRules rules,
            boolean membersMayCreateWorkspace) {

        public UpdateTenantPolicy {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
            PlatformChecks.version(expectedPolicyVersion, "expectedPolicyVersion");
            Objects.requireNonNull(rules, "rules");
        }
    }

    /**
     * 邀请现有账号加入组织租户，接受后取得成员资格。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     * @param targetSubjectId 受邀账号的主体 ID
     * @param expiresAt 请求的邀请到期时间，为 {@code null} 时使用 7 天默认期限，最长 30 天
     */
    public record InviteTenantMember(CommandMeta meta, String tenantId, long expectedTenantVersion,
                                     String targetSubjectId, Instant expiresAt) {
        public InviteTenantMember {
            Objects.requireNonNull(meta);
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.id(targetSubjectId, "targetSubjectId");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
        }
    }

    /**
     * 按邀请和父级版本撤销组织邀请。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param expectedTenantVersion 执行前须匹配的租户记录版本
     * @param invitationId 邀请记录 ID
     * @param expectedInvitationVersion 执行前须匹配的邀请记录版本
     */
    public record RevokeTenantInvitation(CommandMeta meta, String tenantId, long expectedTenantVersion,
                                         String invitationId, long expectedInvitationVersion) {
        public RevokeTenantInvitation {
            Objects.requireNonNull(meta);
            PlatformChecks.id(tenantId, "tenantId");
            PlatformChecks.id(invitationId, "invitationId");
            PlatformChecks.version(expectedTenantVersion, "expectedTenantVersion");
            PlatformChecks.version(expectedInvitationVersion, "expectedInvitationVersion");
        }
    }

    /**
     * 由受邀账号确认加入组织租户。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param invitationId 邀请记录 ID
     * @param expectedInvitationVersion 执行前须匹配的邀请记录版本
     */
    public record AcceptTenantInvitation(CommandMeta meta, String invitationId, long expectedInvitationVersion) {
        public AcceptTenantInvitation {
            Objects.requireNonNull(meta);
            PlatformChecks.id(invitationId, "invitationId");
            PlatformChecks.version(expectedInvitationVersion, "expectedInvitationVersion");
        }
    }
}
