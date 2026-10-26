package com.arte.core.pojo.access;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 授权定位范围
 * <p>
 * 租户必填，空间可选；仅租户范围不要求空间成员资格。
 * 该对象不是已验证身份或权限，空间业务仍须提供完整 Scope。
 *
 * @param tenantId    租户 ID，必填
 * @param workspaceId 工作空间 ID，可选
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 23:53 ✾
 */
public record AccessScope(String tenantId, String workspaceId) {

    public AccessScope {
        PlatformChecks.id(tenantId, "tenantId");
        if (workspaceId != null) PlatformChecks.id(workspaceId, "workspaceId");
    }

    public static AccessScope tenant(String tenantId) {
        return new AccessScope(tenantId, null);
    }

    public static AccessScope workspace(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return new AccessScope(scope.tenantId(), scope.workspaceId());
    }

    /**
     * 转为完整空间范围；仅租户范围不能用于空间操作。
     */
    public Scope requireWorkspace() {
        return new Scope(tenantId, workspaceId);
    }
}
