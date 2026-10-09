package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 租户治理写命令，组织创建受独立 provisioning policy 控制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class TenantCommands {
    private TenantCommands() {
    }

    /**
     * 创建组织，当前主体为初始租户负责人，不隐式读取空间正文。
     */
    public record CreateOrganization(
            CommandMeta meta,
            String name) {
        public CreateOrganization {
            Objects.requireNonNull(meta, "meta");
            name = PlatformChecks.name(name);
        }
    }

    /**
     * 普通改角色不能授予 OWNER。
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
     * 撤销租户资格同时使其空间资格失效，不能自动退化为访客。
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
}
