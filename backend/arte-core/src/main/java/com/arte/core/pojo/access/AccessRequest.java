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
 * @param scope    本次授权检查的定位范围，不能为空；租户必填、空间可选，检查工作空间资源时必须提供空间。
 * @param resource 待检查的资源定位键，不能为空，所属租户须与 scope 的租户一致；真实归属与授权事实由可信提供者读取。
 * @param action   本次检查的单个动作，不能为空，例如 workspace:update；授权服务须校验动作已登记且适用于目标资源类型。
 * @param purpose  本次检查的用途标识，须符合平台 token 格式，例如 workspace-rename，用于表达发起检查的业务场景。
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
