package com.arte.core.pojo.access;

import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 通用归属仅为 TENANT／WORKSPACE。创建者、凭据责任与执行身份不写入此对象。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceOwnership(
        ResourceOwnershipTypeEnum type,
        String tenantId,
        String workspaceId) {
    public ResourceOwnership {
        Objects.requireNonNull(type, "type");
        PlatformChecks.id(tenantId, "tenantId");
        if (workspaceId != null) PlatformChecks.id(workspaceId, "workspaceId");
        PlatformChecks.require((type == ResourceOwnershipTypeEnum.WORKSPACE) == (workspaceId != null), "workspace ownership requires workspace only");
    }
}
