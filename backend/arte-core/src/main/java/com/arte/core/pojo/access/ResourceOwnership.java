package com.arte.core.pojo.access;

import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 资源归属描述
 * <p>
 * 即资源归哪个空间或租户持有、保存和治理。
 * 通用归属仅为 TENANT／WORKSPACE，WORKSPACE 时必须有空间，TENANT 时空间必须为空。
 * 创建者、凭据责任与执行身份不写入此对象。
 *
 * @param type        归属类型
 * @param tenantId    租户 ID
 * @param workspaceId 工作空间 ID
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceOwnership(ResourceOwnershipTypeEnum type, String tenantId, String workspaceId) {

    public ResourceOwnership {
        Objects.requireNonNull(type, "type");
        PlatformChecks.id(tenantId, "tenantId");
        if (workspaceId != null) PlatformChecks.id(workspaceId, "workspaceId");
        PlatformChecks.require((type == ResourceOwnershipTypeEnum.WORKSPACE) == (workspaceId != null), "workspace ownership requires workspace only");
    }
}
