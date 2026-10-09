package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 命令身份范围
 * <p>
 * 双空为个人初始化；仅租户为空间外租户命令。
 * 存储须编码明确范围，不能以 SQL NULL 绕过唯一约束。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandScope(
        String tenantId,
        String workspaceId) {

    public CommandScope {
        if (tenantId != null) PlatformChecks.id(tenantId, "tenantId");
        if (workspaceId != null) PlatformChecks.id(workspaceId, "workspaceId");
        PlatformChecks.require(workspaceId == null || tenantId != null, "workspace requires tenant");
    }
}
