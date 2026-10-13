package com.arte.core.application.workspace;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 实际事务内的治理锁顺序
 * <p>
 * 同一外层事务共享主体／租户取锁记录。所有子记录锁须在对应排他父租户锁下取得，
 * 因此同租户组合调用可重入或扩展空间；跨租户新增父锁必须递增，租户锁后不再新增主体锁。
 * 持锁后的允许判断仍交由 AuthorizationService；只在一次写回调前的授权批次内复用锁定事实，回调开始前清除。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class WorkspaceGovernanceLocks {

    private static final Object TRANSACTION_LOCK_STATE = new Object();

    private static final class LockState {
        final Set<String> subjects = new HashSet<>();
        final Set<String> tenants = new HashSet<>();
        final Set<String> lockedParents = new HashSet<>();
        String lastSubject;
        String lastTenant;
        boolean failed;
        int authorizationDepth;
        Map<Object, Object> authorizationFacts;
    }

    private final PublicTransactionRunner transactions;
    private final PreferenceStore preferences;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final TenantMembershipStore tenantMembers;
    private final WorkspaceMembershipStore workspaceMembers;

    public WorkspaceGovernanceLocks(PublicTransactionRunner transactions, TenantStore tenants, WorkspaceStore workspaces,
                                    TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers,
                                    PreferenceStore preferences) {
        this.transactions = Objects.requireNonNull(transactions);
        this.preferences = Objects.requireNonNull(preferences);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
    }

    /**
     * 仅在写回调前的一批授权内复用已锁事实；退出后立即清除，后续写入／重入必须重读。
     */
    public <T> T duringAuthorization(Supplier<T> work) {
        LockState state = state();
        if (state.authorizationDepth++ == 0) state.authorizationFacts = new HashMap<>();
        try {
            return work.get();
        } finally {
            if (--state.authorizationDepth == 0) state.authorizationFacts = null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T authorizationFact(Object key, Supplier<T> read) {
        LockState state = state();
        if (state.authorizationFacts == null) return read.get();
        if (state.authorizationFacts.containsKey(key)) return (T) state.authorizationFacts.get(key);
        T value = read.get();
        state.authorizationFacts.put(key, value);
        return value;
    }

    private record FactKey(Class<?> store, Object range, String subject) {
    }

    public void tenant(String subjectId, String tenantId) {
        preflight(subjectId, List.of(tenantId));
        subject(subjectId);
        if (lockTenant(tenantId).isEmpty())
            throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        lockTenantMember(tenantId, subjectId);
    }

    /**
     * 初始化／偏好路径也须在事务共享记录中取得主体门控。
     */
    public void subject(String subjectId) {
        PlatformChecks.id(subjectId, "subjectId");
        LockState state = state();
        checkSubject(state, subjectId);
        if (state.subjects.add(subjectId)) state.lastSubject = subjectId;
        authorizationFact(new FactKey(PreferenceStore.class, subjectId, subjectId), () -> acquire(() -> {
            preferences.lockSubject(subjectId);
            return Boolean.TRUE;
        }));
    }

    public Optional<Tenant> lockTenant(String tenantId) {
        PlatformChecks.id(tenantId, "tenantId");
        LockState state = state();
        checkTenant(state, tenantId);
        if (state.tenants.add(tenantId)) state.lastTenant = tenantId;
        Optional<Tenant> result = authorizationFact(new FactKey(TenantStore.class, tenantId, null),
                () -> acquire(() -> tenants.lock(tenantId)));
        if (result.isPresent()) state.lockedParents.add(tenantId);
        return result;
    }

    public Optional<Workspace> lockWorkspace(Scope scope) {
        requireParent(scope.tenantId());
        return authorizationFact(new FactKey(WorkspaceStore.class, scope, null), () -> acquire(() -> workspaces.lock(scope)));
    }

    public Optional<TenantMembership> lockTenantMember(String tenantId, String subjectId) {
        requireParentAndSubject(tenantId, subjectId);
        return authorizationFact(new FactKey(TenantMembershipStore.class, tenantId, subjectId),
                () -> acquire(() -> tenantMembers.lock(tenantId, subjectId)));
    }

    public Optional<WorkspaceMembership> lockWorkspaceMember(Scope scope, String subjectId) {
        requireParentAndSubject(scope.tenantId(), subjectId);
        return authorizationFact(new FactKey(WorkspaceMembershipStore.class, scope, subjectId),
                () -> acquire(() -> workspaceMembers.lock(scope, subjectId)));
    }

    public void scopes(String subjectId, Collection<Scope> scopes) {
        ranges(subjectId, scopes.stream().map(Scope::tenantId).toList(), scopes);
    }

    /**
     * 当前偏好过滤等多目标读取预先锁定全部候选父范围；不存在的父范围由后续授权返回不可见。
     * 不取得成员资格或缓存事实，也不放行缺失父记录的子锁。
     */
    public void parents(String subjectId, Collection<String> tenantTargets) {
        List<String> ordered = tenantTargets.stream().distinct().sorted().toList();
        preflight(subjectId, ordered);
        subject(subjectId);
        for (String tenantId : ordered) lockTenant(tenantId);
    }

    /**
     * 混合租户／空间请求一次排序取锁；仅租户目标不取得空间及空间成员锁。
     */
    public void ranges(String subjectId, Collection<String> tenantTargets, Collection<Scope> scopes) {
        List<Scope> ordered = scopes.stream().distinct().sorted(Comparator.comparing(Scope::tenantId)
                .thenComparing(Scope::workspaceId)).toList();
        List<String> tenantIds = Stream.concat(tenantTargets.stream(), ordered.stream().map(Scope::tenantId))
                .distinct().sorted().toList();
        // 在任何存储取锁前检查完整新增父范围，不能先取一部分锁才发现组合调用逆序。
        preflight(subjectId, tenantIds);
        subject(subjectId);
        for (String tenantId : tenantIds) {
            if (lockTenant(tenantId).isEmpty())
                throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        }
        for (Scope scope : ordered) {
            if (lockWorkspace(scope).isEmpty())
                throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        }
        for (String tenantId : tenantIds) lockTenantMember(tenantId, subjectId);
        for (Scope scope : ordered) lockWorkspaceMember(scope, subjectId);
    }

    /**
     * 当前授权读取也先检查完整父范围，随后才允许重入存储锁。
     */
    public void preflight(String subjectId, Collection<String> tenantIds) {
        PlatformChecks.id(subjectId, "subjectId");
        LockState state = state();
        checkSubject(state, subjectId);
        for (String tenantId : tenantIds) {
            PlatformChecks.id(tenantId, "tenantId");
            if (!state.tenants.contains(tenantId) && state.lastTenant != null && tenantId.compareTo(state.lastTenant) < 0)
                reject(state);
        }
    }

    private LockState state() {
        transactions.requireWritable();
        LockState state = transactions.resource(TRANSACTION_LOCK_STATE, LockState::new);
        if (state.failed) reject(state);
        return state;
    }

    private void checkSubject(LockState state, String subjectId) {
        if (!state.subjects.contains(subjectId) && (state.lastTenant != null
                || state.lastSubject != null && subjectId.compareTo(state.lastSubject) < 0)) reject(state);
    }

    private void checkTenant(LockState state, String tenantId) {
        if (state.subjects.isEmpty() || !state.tenants.contains(tenantId)
                && state.lastTenant != null && tenantId.compareTo(state.lastTenant) < 0) reject(state);
    }

    private void requireParent(String tenantId) {
        LockState state = state();
        if (!state.lockedParents.contains(tenantId)) reject(state);
    }

    private void requireParentAndSubject(String tenantId, String subjectId) {
        requireParent(tenantId);
        LockState state = state();
        if (!state.subjects.contains(subjectId)) reject(state);
    }

    private <T> T acquire(Supplier<T> work) {
        LockState state = state();
        try {
            return work.get();
        } catch (RuntimeException | Error e) {
            state.failed = true;
            transactions.markRollbackOnly();
            throw e;
        }
    }

    private void reject(LockState state) {
        state.failed = true;
        transactions.markRollbackOnly();
        throw new GovernanceLockOrderException();
    }
}
