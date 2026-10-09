package com.arte.core.pojo.access;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

import static com.arte.core.constant.platform.PlatformResourceTypes.WORKSPACE;

/**
 * 权限检查请求
 * <p>
 * 单动作判断输入，可信 actor 为 API 独立参数；resource 仅定位，真实事实由提供者确认。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AccessRequest(
        AccessScope scope,
        ResourceRef resource,
        Action action,
        String purpose) {

    public AccessRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        PlatformChecks.token(purpose, "purpose");
        PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "cross-tenant request unsupported");
        PlatformChecks.require(!resource.type().equals(WORKSPACE) || scope.workspaceId() != null,
                "workspace resource requires workspace scope");
    }

    /**
     * 保留完整空间请求的构造方式；租户治理可直接传入 AccessScope.tenant。
     */
    public AccessRequest(Scope scope, ResourceRef resource, Action action, String purpose) {
        this(AccessScope.workspace(scope), resource, action, purpose);
    }
}
