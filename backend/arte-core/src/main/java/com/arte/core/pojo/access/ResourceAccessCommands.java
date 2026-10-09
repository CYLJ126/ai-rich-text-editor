package com.arte.core.pojo.access;

import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 授权写命令
 * <p>
 * 来源、成员代次及 issuedBy 由服务器生成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class ResourceAccessCommands {

    private ResourceAccessCommands() {
    }

    /**
     * 授予动作仍需当前分享／ACL 管理资格及政策上限。
     */
    public record GrantResource(
            CommandMeta meta,
            Scope scope,
            ResourceRef resource,
            long expectedAclVersion,
            GrantRecipient recipient,
            Set<Action> actions,
            Instant expiresAt) {
        public GrantResource {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(resource, "resource");
            PlatformChecks.version(expectedAclVersion, "expectedAclVersion");
            Objects.requireNonNull(recipient, "recipient");
            actions = Set.copyOf(actions);
            PlatformChecks.require(!actions.isEmpty() && actions.size() <= 100, "invalid action count");
            PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "cross-tenant grant unsupported");
        }
    }

    /**
     * 按指定来源记录撤销，不删除其他独立允许来源。
     */
    public record RevokeGrant(
            CommandMeta meta,
            Scope scope,
            ResourceRef resource,
            String grantId,
            long expectedAclVersion,
            long expectedGrantVersion) {
        public RevokeGrant {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(resource, "resource");
            PlatformChecks.id(grantId, "grantId");
            PlatformChecks.version(expectedAclVersion, "expectedAclVersion");
            PlatformChecks.version(expectedGrantVersion, "expectedGrantVersion");
            PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "cross-tenant grant unsupported");
        }
    }

    /**
     * 切换共享必须同时提交明确允许动作；关闭共享不重置直接主体授权。
     */
    public record ChangeVisibility(
            CommandMeta meta,
            Scope scope,
            ResourceRef resource,
            long expectedAclVersion,
            ResourceVisibilityEnum visibility,
            List<VisibilityGrant> sharedGrants) {
        public ChangeVisibility {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(resource, "resource");
            PlatformChecks.version(expectedAclVersion, "expectedAclVersion");
            Objects.requireNonNull(visibility, "visibility");
            sharedGrants = List.copyOf(sharedGrants);
            PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "cross-tenant visibility unsupported");
            PlatformChecks.require((visibility == ResourceVisibilityEnum.SPACE_SHARED) != sharedGrants.isEmpty(), "visibility requires matching explicit grants");
            PlatformChecks.require(sharedGrants.size() <= 100, "too many shared grants");
        }
    }
}
