package com.arte.core.application.workspace;

import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.Objects;

/**
 * 默认服务共用的可信范围读取；不复制菜单 RBAC，也不从 createdBy 推算成员资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class WorkspaceAccessSupport {

    public record TenantSnapshot(Principal principal, Tenant tenant, TenantMembership member, TenantPolicy policy) {
    }

    public record WorkspaceSnapshot(TenantSnapshot tenant, Workspace workspace, WorkspaceMembership member,
                                    WorkspacePolicy policy, VerifiedScope verified) {
    }

    private final CurrentPrincipalResolver principals;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final TenantMembershipStore tenantMembers;
    private final WorkspaceMembershipStore workspaceMembers;
    private final PolicyStore policies;
    private final PreferenceStore preferences;
    private final Clock clock;

    public WorkspaceAccessSupport(CurrentPrincipalResolver principals, TenantStore tenants, WorkspaceStore workspaces,
                                  TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PolicyStore policies,
                                  PreferenceStore preferences, Clock clock) {
        this.principals = Objects.requireNonNull(principals);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
        this.policies = Objects.requireNonNull(policies);
        this.preferences = Objects.requireNonNull(preferences);
        this.clock = Objects.requireNonNull(clock);
    }

    public TenantSnapshot tenant(TrustedActor actor, String tenantId) {
        Principal principal = principals.requireActive(actor);
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        TenantMembership member = tenantMembers.find(tenantId, principal.subjectId())
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!tenant.id().equals(tenantId) || !member.tenantId().equals(tenantId)
                || !member.subjectId().equals(principal.subjectId()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        if (member.state() != MembershipStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (tenant.state() != TenantStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        TenantPolicy policy = policies.findTenantPolicy(tenantId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!policy.tenantId().equals(tenantId)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        requirePolicyVersion(policy.policySchemaVersion(), policy.rolePresetVersion());
        return new TenantSnapshot(principal, tenant, member, policy);
    }

    public WorkspaceSnapshot snapshot(TrustedActor actor, Scope scope) {
        Objects.requireNonNull(scope);
        TenantSnapshot tenant = tenant(actor, scope.tenantId());
        Workspace workspace = workspaces.find(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        WorkspaceMembership member = workspaceMembers.find(scope, tenant.principal().subjectId())
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        if (!workspace.scope().equals(scope) || !member.scope().equals(scope)
                || !member.subjectId().equals(tenant.principal().subjectId()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        if (member.state() != MembershipStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (workspace.state() == WorkspaceStateEnum.DELETED) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (workspace.state() == WorkspaceStateEnum.CLOSING) throw error(PlatformErrorCodeEnum.SPACE_CLOSING);
        WorkspacePolicy policy = policies.findWorkspacePolicy(scope)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!policy.scope().equals(scope)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        requirePolicyVersion(policy.policySchemaVersion(), policy.rolePresetVersion());
        var versions = new AuthorizationVersions(tenant.tenant().authEpoch(), workspace.authEpoch(),
                tenant.member().generation(), tenant.member().version(), member.generation(), member.version(), null, workspace.version());
        var verified = new VerifiedScope(tenant.principal(), scope, tenant.member().role(), member.role(), versions, clock.instant());
        return new WorkspaceSnapshot(tenant, workspace, member, policy, verified);
    }

    public boolean isDefaultSpace(WorkspaceSnapshot workspaceSnapshot) {
        Tenant tenant = workspaceSnapshot.tenant().tenant();
        if (tenant.type() != TenantTypeEnum.PERSONAL) return false;
        WorkspacePreference preference = preferences.find(tenant.personalSubjectId())
                .orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!preference.subjectId().equals(tenant.personalSubjectId())
                || !preference.defaultScope().tenantId().equals(tenant.id()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        return preference.defaultScope().equals(workspaceSnapshot.workspace().scope());
    }

    public static void requirePolicyVersion(int schema, int preset) {
        if (schema != 1 || preset != 1) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "scope-check");
    }
}
