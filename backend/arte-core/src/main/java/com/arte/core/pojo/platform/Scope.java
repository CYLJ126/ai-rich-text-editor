package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 客户端选择的范围，仅是定位信息，必须经公共 API 验证成员及状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Scope(String tenantId, String workspaceId) {

    public Scope {
        PlatformChecks.id(tenantId, "tenantId");
        PlatformChecks.id(workspaceId, "workspaceId");
    }
}
