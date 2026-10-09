package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 领域同事务提供的原始可信事实；不含正文，不调用受保护的对外读取 API。domainState 保留领域语义，不替代 AI 发布状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceDescriptor(
        ResourceRef resource,
        ResourceOwnership ownership,
        String domainState,
        long version,
        String createdBy) {
    public ResourceDescriptor {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(ownership, "ownership");
        PlatformChecks.id(domainState, "domainState");
        PlatformChecks.version(version, "version");
        PlatformChecks.id(createdBy, "createdBy");
        PlatformChecks.require(resource.tenantId().equals(ownership.tenantId()), "resource and ownership tenant mismatch");
    }
}
