package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 以租户 ID 和工作空间 ID 定位客户端选择的空间范围。
 * 公共 API 仍须验证成员资格及空间状态。
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
