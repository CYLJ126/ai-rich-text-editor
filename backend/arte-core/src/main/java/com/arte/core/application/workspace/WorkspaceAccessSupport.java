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
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

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
    private final PublicTransactionRunner transactions;

    public WorkspaceAccessSupport(CurrentPrincipalResolver principals, TenantStore tenants, WorkspaceStore workspaces,
                                  TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PolicyStore policies,
                                  PreferenceStore preferences, Clock clock, PublicTransactionRunner transactions) {
        this.principals = Objects.requireNonNull(principals);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
        this.policies = Objects.requireNonNull(policies);
        this.preferences = Objects.requireNonNull(preferences);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
    }

    public void requireTransaction() {
        transactions.requireActive();
    }

    public TenantSnapshot tenant(TrustedActor actor, String tenantId) {
        Principal principal = principals.requireActive(actor);
        return tenantSnapshot(principal, tenantId,
                tenants.find(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)),
                tenantMembers.find(tenantId, principal.subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)),
                () -> policies.findTenantPolicy(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
    }

    /**
     * 在原事务门控下，以锁定读取返回值构造租户事实，不再通过普通 find 重读。
     */
    public TenantSnapshot tenantCurrent(TrustedActor actor, String tenantId) {
        transactions.requireActive();
        String subjectId = actor.principal().subjectId();
        preferences.lockSubject(subjectId);
        Tenant tenant = tenants.lock(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        TenantMembership member = tenantMembers.lock(tenantId, subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        return tenantSnapshot(principals.requireActiveCurrent(actor), tenantId, tenant, member,
                () -> policies.findCurrentTenantPolicy(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
    }

    private TenantSnapshot tenantSnapshot(Principal principal, String tenantId, Tenant tenant,
                                          TenantMembership member, Supplier<TenantPolicy> policyRead) {
        if (!tenant.id().equals(tenantId) || !member.tenantId().equals(tenantId)
                || !member.subjectId().equals(principal.subjectId()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        if (member.state() != MembershipStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (tenant.state() != TenantStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        TenantPolicy policy = policyRead.get();
        if (!policy.tenantId().equals(tenantId)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        requirePolicyVersion(policy.policySchemaVersion(), policy.rolePresetVersion());
        return new TenantSnapshot(principal, tenant, member, policy);
    }

    public WorkspaceSnapshot snapshot(TrustedActor actor, Scope scope) {
        Objects.requireNonNull(scope);
        TenantSnapshot tenant = tenant(actor, scope.tenantId());
        return workspaceSnapshot(tenant, scope,
                workspaces.find(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)),
                workspaceMembers.find(scope, tenant.principal().subjectId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)),
                () -> policies.findWorkspacePolicy(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
    }

    /**
     * 最终授权的当前事实：先锁主体、租户、空间和成员，再使用锁定读取的返回值验证。
     * 多范围命令必须预先通过 WorkspaceGovernanceLocks 按完整范围排序取得全部门控；
     * 此处重入已持有锁，不新增事务，不依赖外层事务的普通读取快照。
     */
    public WorkspaceSnapshot snapshotCurrent(TrustedActor actor, Scope scope) {
        transactions.requireActive();
        Objects.requireNonNull(scope);
        String subjectId = actor.principal().subjectId();
        preferences.lockSubject(subjectId);
        Tenant tenant = tenants.lock(scope.tenantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        Workspace workspace = workspaces.lock(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        TenantMembership tenantMember = tenantMembers.lock(scope.tenantId(), subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        WorkspaceMembership member = workspaceMembers.lock(scope, subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        Principal principal = principals.requireActiveCurrent(actor);
        TenantSnapshot tenantSnapshot = tenantSnapshot(principal, scope.tenantId(), tenant, tenantMember,
                () -> policies.findCurrentTenantPolicy(scope.tenantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
        return workspaceSnapshot(tenantSnapshot, scope, workspace, member,
                () -> policies.findCurrentWorkspacePolicy(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
    }

    private WorkspaceSnapshot workspaceSnapshot(TenantSnapshot tenant, Scope scope, Workspace workspace,
                                                WorkspaceMembership member, Supplier<WorkspacePolicy> policyRead) {
        if (!workspace.scope().equals(scope) || !member.scope().equals(scope)
                || !member.subjectId().equals(tenant.principal().subjectId()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        if (member.state() != MembershipStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (workspace.state() == WorkspaceStateEnum.DELETED) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        if (workspace.state() == WorkspaceStateEnum.CLOSING) throw error(PlatformErrorCodeEnum.SPACE_CLOSING);
        WorkspacePolicy policy = policyRead.get();
        if (!policy.scope().equals(scope)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        requirePolicyVersion(policy.policySchemaVersion(), policy.rolePresetVersion());
        var versions = new AuthorizationVersions(tenant.tenant().authEpoch(), workspace.authEpoch(),
                tenant.member().generation(), tenant.member().version(), member.generation(), member.version(), null, workspace.version());
        var verified = new VerifiedScope(tenant.principal(), scope, tenant.member().role(), member.role(), versions, clock.instant());
        return new WorkspaceSnapshot(tenant, workspace, member, policy, verified);
    }

    public boolean isDefaultSpace(WorkspaceSnapshot snapshot) {
        return isDefaultSpace(snapshot, false);
    }

    public boolean isDefaultSpaceCurrent(WorkspaceSnapshot snapshot) {
        transactions.requireActive();
        return isDefaultSpace(snapshot, true);
    }

    private boolean isDefaultSpace(WorkspaceSnapshot snapshot, boolean currentRead) {
        Tenant tenant = snapshot.tenant().tenant();
        if (tenant.type() != TenantTypeEnum.PERSONAL) return false;
        WorkspacePreference preference = (currentRead ? preferences.findCurrent(tenant.personalSubjectId())
                : preferences.find(tenant.personalSubjectId())).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        if (!preference.subjectId().equals(tenant.personalSubjectId())
                || !preference.defaultScope().tenantId().equals(tenant.id()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        return preference.defaultScope().equals(snapshot.workspace().scope());
    }

    public static void requirePolicyVersion(int schema, int preset) {
        if (schema != 1 || preset != 1) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "scope-check");
    }
}
