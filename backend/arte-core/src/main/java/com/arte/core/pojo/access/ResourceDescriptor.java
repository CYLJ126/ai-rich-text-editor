package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 领域同事务提供的原始可信事实；不含正文，不调用受保护的对外读取 API。domainState 保留领域语义，不替代 AI 发布状态。
 *
 * @param resource 资源引用
 * @param ownership 资源的租户或空间归属
 * @param domainState 资源在所属业务领域中的状态标识
 * @param version 领域资源记录版本
 * @param createdBy 创建资源的主体 ID
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
