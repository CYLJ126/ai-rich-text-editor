package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 作用域标识<租户，工作空间>
 * 客户端选择的范围，仅是定位信息，必须经公共 API 验证成员及状态。
 *
 * @param tenantId    租户 ID，必填
 * @param workspaceId 工作空间 ID，必填
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Scope(String tenantId, String workspaceId) {

    public Scope {
        PlatformChecks.id(tenantId, "tenantId");
        PlatformChecks.id(workspaceId, "workspaceId");
    }
}
