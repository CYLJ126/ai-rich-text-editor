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
     * 为指定资源创建授权。授予动作仍受当前分享或 ACL 管理资格及政策上限约束。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param resource 待授权的资源引用
     * @param expectedAclVersion 执行前须匹配的资源访问控制列表（ACL）版本
     * @param recipient 请求授权的主体、空间角色或空间
     * @param actions 本次明确授予的动作集合
     * @param expiresAt 授权到期时间，为 {@code null} 时无时间期限
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
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param resource 授权所属的资源引用
     * @param grantId 待撤销的单条授权记录 ID
     * @param expectedAclVersion 执行前须匹配的资源访问控制列表（ACL）版本
     * @param expectedGrantVersion 执行前须匹配的授权记录版本
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
     * 修改资源可见性。开启空间共享时须提交明确的授权动作，关闭共享时保留直接主体授权。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param resource 待修改可见性的资源引用
     * @param expectedAclVersion 执行前须匹配的资源访问控制列表（ACL）版本
     * @param visibility 目标可见性
     * @param sharedGrants 空间共享时的显式授权列表，其他可见性须传空列表
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
