package com.arte.core.pojo.access;

import com.arte.core.enums.access.ResourceVisibilityEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 公共 ACL 根。归属须与领域事实一致，不能用 ACL 镜像覆盖领域归属。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceAccess(
        ResourceRef resource,
        ResourceOwnership ownership,
        ResourceVisibilityEnum visibility,
        long aclVersion) {
    public ResourceAccess {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(ownership, "ownership");
        Objects.requireNonNull(visibility, "visibility");
        PlatformChecks.version(aclVersion, "aclVersion");
        PlatformChecks.require(resource.tenantId().equals(ownership.tenantId()), "resource and ownership tenant mismatch");
    }
}
