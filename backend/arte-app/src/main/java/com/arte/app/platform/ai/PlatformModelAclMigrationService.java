package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_POLICY_WRITE;

/**
 * 旧模型 ACL 清单的授权查询
 * <p>
 * 固定目录迁移写入口已关闭，不能通过删除或覆盖清单回退旧 Grant。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
public final class PlatformModelAclMigrationService implements ModelAclMigrationService {
    private final PublicTransactionRunner tx;
    private final ModelAclScopeStore scopes;
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;
    private final AuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;

    public PlatformModelAclMigrationService(ModelAclScopeStore scopes, RbacIdentityProvider identities,
                                            RbacToolPermissionReader permissions, PublicTransactionRunner tx,
                                            AuthorizationService authorization, TenantStore tenants, WorkspaceStore workspaces,
                                            TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                            PreferenceStore preferences) {
        this.tx = tx;
        this.scopes = scopes;
        this.identities = identities;
        this.permissions = permissions;
        this.authorization = authorization;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
    }

    @Override
    public Optional<ModelAclScope> get(TrustedActor actor, Scope scope) {
        return tx.required(() -> {
            locks.scopes(actor.principal().subjectId(), List.of(scope));
            requireManager(actor, scope);
            return scopes.findCurrent(scope);
        });
    }

    @Override
    public CommandReceipt<ModelAclScope> migrate(TrustedActor actor, Migrate command) {
        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
    }

    private void requireManager(TrustedActor actor, Scope scope) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope,
                WORKSPACE_POLICY_WRITE, "model-acl-migration"));
        String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(PlatformModelAclAccess::unavailable);
        Set<String> current = permissions.current(name);
        if (!current.contains("admin") && !current.contains("aiConfiguration:manage")) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "model-acl-migration");
    }
}
