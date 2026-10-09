package com.arte.core.application.workspace;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 实际事务内的治理锁顺序
 * <p>
 * 持锁后的允许判断仍交由 AuthorizationService。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class WorkspaceGovernanceLocks {

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

    public void tenant(String subjectId, String tenantId) {
        transactions.requireActive();
        preferences.lockSubject(subjectId);
        if (tenants.lock(tenantId).isEmpty())
            throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        tenantMembers.lock(tenantId, subjectId);
    }

    public void scopes(String subjectId, Collection<Scope> scopes) {
        ranges(subjectId, scopes.stream().map(Scope::tenantId).toList(), scopes);
    }

    /**
     * 混合租户／空间请求一次排序取锁；仅租户目标不取得空间及空间成员锁。
     */
    public void ranges(String subjectId, Collection<String> tenantTargets, Collection<Scope> scopes) {
        transactions.requireActive();
        preferences.lockSubject(subjectId);
        List<Scope> ordered = scopes.stream().distinct().sorted(Comparator.comparing(Scope::tenantId)
                .thenComparing(Scope::workspaceId)).toList();
        List<String> tenantIds = Stream.concat(tenantTargets.stream(), ordered.stream().map(Scope::tenantId))
                .distinct().sorted().toList();
        for (String tenantId : tenantIds) {
            if (tenants.lock(tenantId).isEmpty())
                throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        }
        for (Scope scope : ordered) {
            if (workspaces.lock(scope).isEmpty())
                throw new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "governance-lock");
        }
        for (String tenantId : tenantIds) tenantMembers.lock(tenantId, subjectId);
        for (Scope scope : ordered) workspaceMembers.lock(scope, subjectId);
    }
}
