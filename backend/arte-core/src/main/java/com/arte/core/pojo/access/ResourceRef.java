package com.arte.core.pojo.access;

import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 范围明确的资源定位键；持有 ID 不代表可访问，真实归属由领域提供者读取。
 *
 * @param tenantId    租户 ID
 * @param type        资源类型
 * @param resourceId  资源 ID
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceRef(String tenantId, ResourceType type, String resourceId) {

    public ResourceRef {
        PlatformChecks.id(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        PlatformChecks.id(resourceId, "resourceId");
    }
}
