package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;

import java.time.Instant;
import java.util.Objects;

/**
 * 公共服务验证的成员及范围快照；写入时还需实际事务内重新检查，不得作为 HTTP 可信输入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VerifiedScope(
        Principal principal,
        Scope scope,
        TenantRoleEnum tenantRole,
        WorkspaceRoleEnum workspaceRole,
        AuthorizationVersions versions,
        Instant checkedAt) {
    public VerifiedScope {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(tenantRole, "tenantRole");
        Objects.requireNonNull(workspaceRole, "workspaceRole");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(checkedAt, "checkedAt");
    }
}
