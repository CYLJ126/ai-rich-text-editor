package com.arte.core.pojo.access;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 权限检查请求
 * <p>
 * 单动作判断输入，可信 actor 为 API 独立参数；resource 仅定位，真实事实由提供者确认。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AccessRequest(
        Scope scope,
        ResourceRef resource,
        Action action,
        String purpose) {

    public AccessRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(action, "action");
        PlatformChecks.token(purpose, "purpose");
        PlatformChecks.require(scope.tenantId().equals(resource.tenantId()), "cross-tenant request unsupported");
    }
}
