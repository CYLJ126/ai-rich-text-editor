package com.arte.core.pojo.access;

import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 授权管理返回对象。角色／代次等内部匹配细节不作为永久执行许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class ResourceAccessViews {
    private ResourceAccessViews() {
    }

    /**
     * 管理者可见的授权项。
     */
    public record GrantView(
            String id,
            ResourceRef resource,
            GrantRecipient recipient,
            Action action,
            GrantStateEnum state,
            Instant expiresAt,
            GrantSourceEnum source,
            long version) {
        public GrantView {
            PlatformChecks.id(id, "id");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(source, "source");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 一次多动作授予的逐项记录及 ACL 新版本。
     */
    public record GrantResult(
            List<GrantView> grants,
            long aclVersion) {
        public GrantResult {
            grants = List.copyOf(grants);
            PlatformChecks.version(aclVersion, "aclVersion");
        }
    }

    /**
     * 资源权限元数据，不含正文。
     */
    public record AccessView(
            ResourceRef resource,
            ResourceOwnership ownership,
            ResourceVisibilityEnum visibility,
            long aclVersion) {
        public AccessView {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(ownership, "ownership");
            Objects.requireNonNull(visibility, "visibility");
            PlatformChecks.version(aclVersion, "aclVersion");
        }
    }
}
