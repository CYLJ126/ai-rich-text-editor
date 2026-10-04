package com.arte.ainew.common.execution;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;

/**
 * 授权解析结果，固定租户、空间和任务权限上限。
 * grantRef 引用可重新校验的授权记录；持有此快照不替代资源级授权或授权撤销检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionAuthorization(
        ExecutionPrincipal principal, String tenantId, String workspaceId,
        Set<String> scopes, String grantRef) implements Serializable {

    public ExecutionAuthorization {
        Objects.requireNonNull(principal, "principal");
        ExecutionPrincipal.requireText(tenantId, "tenantId");
        ExecutionPrincipal.requireText(workspaceId, "workspaceId");
        ExecutionPrincipal.requireText(grantRef, "grantRef");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        scopes.forEach(scope -> ExecutionPrincipal.requireText(scope, "scope"));
    }

    /** 派生任务只能缩小权限，不能通过上下文复制提升权限。 */
    public ExecutionAuthorization restrictTo(Set<String> requestedScopes) {
        if (!scopes.containsAll(Objects.requireNonNull(requestedScopes, "requestedScopes"))) {
            throw new IllegalArgumentException("Child scopes exceed parent authorization");
        }
        return new ExecutionAuthorization(principal, tenantId, workspaceId, requestedScopes, grantRef);
    }
}
