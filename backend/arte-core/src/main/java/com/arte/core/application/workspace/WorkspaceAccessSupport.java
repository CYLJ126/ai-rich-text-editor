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
import java.util.*;
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
    private final WorkspaceGovernanceLocks locks;
    private final WorkspaceAccessFactsStore batchFacts;

    public WorkspaceAccessSupport(CurrentPrincipalResolver principals, TenantStore tenants, WorkspaceStore workspaces,
                                  TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PolicyStore policies,
                                  PreferenceStore preferences, Clock clock, PublicTransactionRunner transactions,
                                  WorkspaceAccessFactsStore batchFacts) {
        this.principals = Objects.requireNonNull(principals);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
        this.policies = Objects.requireNonNull(policies);
        this.preferences = Objects.requireNonNull(preferences);
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.batchFacts = Objects.requireNonNull(batchFacts);
        this.locks = new WorkspaceGovernanceLocks(transactions, tenants, workspaces, tenantMembers, workspaceMembers, preferences);
    }

    public void requireTransaction() {
        transactions.requireActive();
    }

    /**
     * 身份每批重检一次；原始事实及验证快照仅限本批复用，不跨请求或候选批次缓存。
     */
    public Batch readBatch(TrustedActor actor, Set<String> tenantIds, Set<Scope> scopes, boolean includeDefaultPreferences) {
        Set<String> tenantKeys = Set.copyOf(tenantIds);
        Set<Scope> scopeKeys = Set.copyOf(scopes);
        if (tenantKeys.size() > 100 || scopeKeys.size() > 100
                || scopeKeys.stream().anyMatch(scope -> !tenantKeys.contains(scope.tenantId())))
            throw error(PlatformErrorCodeEnum.INVALID_ARGUMENT);
        Principal principal = principals.requireActive(actor);
        WorkspaceAccessFacts facts = Objects.requireNonNull(batchFacts.read(principal.subjectId(), tenantKeys, scopeKeys, includeDefaultPreferences));
        // 不接受适配器混入其他范围的事实；值与键、主体的对应关系仍由共用校验逐项确认。
        if (!tenantKeys.containsAll(facts.tenants().keySet()) || !tenantKeys.containsAll(facts.tenantMembers().keySet())
                || !tenantKeys.containsAll(facts.tenantPolicies().keySet()) || !tenantKeys.containsAll(facts.defaultPreferences().keySet())
                || !scopeKeys.containsAll(facts.workspaces().keySet()) || !scopeKeys.containsAll(facts.workspaceMembers().keySet())
                || !scopeKeys.containsAll(facts.workspacePolicies().keySet())
                || (!includeDefaultPreferences && !facts.defaultPreferences().isEmpty()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        return new Batch(principal, facts, tenantKeys, scopeKeys);
    }

    /**
     * 本批可信读取上下文；只复用原始事实，不保存允许决定。
     */
    public final class Batch {

        private final Principal principal;
        private final WorkspaceAccessFacts facts;
        private final Set<String> tenantKeys;
        private final Set<Scope> scopeKeys;
        private final Map<String, TenantSnapshot> tenantSnapshots = new HashMap<>();
        private final Map<Scope, WorkspaceSnapshot> workspaceSnapshots = new HashMap<>();

        private Batch(Principal principal, WorkspaceAccessFacts facts, Set<String> tenantKeys, Set<Scope> scopeKeys) {
            this.principal = principal;
            this.facts = facts;
            this.tenantKeys = tenantKeys;
            this.scopeKeys = scopeKeys;
        }

        public TenantSnapshot tenant(String tenantId) {
            if (!tenantKeys.contains(tenantId)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            return tenantSnapshots.computeIfAbsent(tenantId, key -> tenantSnapshot(principal, key,
                    required(facts.tenants(), key, PlatformErrorCodeEnum.NOT_FOUND),
                    required(facts.tenantMembers(), key, PlatformErrorCodeEnum.NOT_FOUND),
                    () -> required(facts.tenantPolicies(), key, PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
        }

        public WorkspaceSnapshot snapshot(Scope scope) {
            if (!scopeKeys.contains(scope)) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            return workspaceSnapshots.computeIfAbsent(scope, key -> workspaceSnapshot(tenant(key.tenantId()), key,
                    required(facts.workspaces(), key, PlatformErrorCodeEnum.NOT_FOUND),
                    required(facts.workspaceMembers(), key, PlatformErrorCodeEnum.NOT_FOUND),
                    () -> required(facts.workspacePolicies(), key, PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
        }

        public boolean isDefaultSpace(WorkspaceSnapshot snapshot) {
            Tenant tenant = snapshot.tenant().tenant();
            if (tenant.type() != TenantTypeEnum.PERSONAL) return false;
            return matchesDefault(snapshot, required(facts.defaultPreferences(), tenant.id(), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        }
    }

    private static <K, V> V required(Map<K, V> facts, K key, PlatformErrorCodeEnum missing) {
        V value = facts.get(key);
        if (value == null) throw error(missing);
        return value;
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
        locks.preflight(subjectId, List.of(tenantId));
        locks.subject(subjectId);
        Tenant tenant = locks.lockTenant(tenantId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        TenantMembership member = locks.lockTenantMember(tenantId, subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        return tenantSnapshot(locks.authorizationFact(List.of(CurrentPrincipalResolver.class, actor.principal()), () -> principals.requireActiveCurrent(actor)), tenantId, tenant, member,
                () -> locks.authorizationFact(List.of(PolicyStore.class, tenantId), () -> policies.findCurrentTenantPolicy(tenantId)).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
    }

    private TenantSnapshot tenantSnapshot(Principal principal, String tenantId, Tenant tenant,
                                          TenantMembership member, Supplier<TenantPolicy> policyRead) {
        if (!tenant.id().equals(tenantId) || !member.tenantId().equals(tenantId)
                || !member.subjectId().equals(principal.subjectId()))
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        if (member.state() != MembershipStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.NOT_FOUND);
        // 租户生命周期独立于主体身份，不能将租户不可用报告为主体停用。
        if (tenant.state() != TenantStateEnum.ACTIVE) throw error(PlatformErrorCodeEnum.TENANT_INACTIVE);
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
        locks.preflight(subjectId, List.of(scope.tenantId()));
        locks.subject(subjectId);
        Tenant tenant = locks.lockTenant(scope.tenantId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        Workspace workspace = locks.lockWorkspace(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        TenantMembership tenantMember = locks.lockTenantMember(scope.tenantId(), subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        WorkspaceMembership member = locks.lockWorkspaceMember(scope, subjectId)
                .orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        Principal principal = locks.authorizationFact(List.of(CurrentPrincipalResolver.class, actor.principal()), () -> principals.requireActiveCurrent(actor));
        TenantSnapshot tenantSnapshot = tenantSnapshot(principal, scope.tenantId(), tenant, tenantMember,
                () -> locks.authorizationFact(List.of(PolicyStore.class, scope.tenantId()), () -> policies.findCurrentTenantPolicy(scope.tenantId())).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
        return workspaceSnapshot(tenantSnapshot, scope, workspace, member,
                () -> locks.authorizationFact(List.of(PolicyStore.class, scope), () -> policies.findCurrentWorkspacePolicy(scope)).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE)));
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
        return matchesDefault(snapshot, preference);
    }

    private boolean matchesDefault(WorkspaceSnapshot snapshot, WorkspacePreference preference) {
        Tenant tenant = snapshot.tenant().tenant();
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
