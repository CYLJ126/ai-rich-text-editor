package com.arte.core.application.access;

import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformResourceTypes.TENANT;
import static com.arte.core.constant.platform.PlatformResourceTypes.WORKSPACE;

/**
 * 租户／空间及已登记资源的最终门控；不在事务外开新事务，未接入 ACL 的业务资源拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspaceWriteGuard implements WorkspaceWriteGuard {

    private final PublicTransactionRunner transactions;
    private final CurrentPrincipalResolver principals;
    private final WorkspaceGovernanceLocks locks;
    private final AuthorizationService authorization;
    private final boolean resourceAclEnabled;

    public DefaultWorkspaceWriteGuard(PublicTransactionRunner transactions, CurrentPrincipalResolver principals,
                                      WorkspaceGovernanceLocks locks, AuthorizationService authorization) {
        this(transactions, principals, locks, authorization, false);
    }

    public DefaultWorkspaceWriteGuard(PublicTransactionRunner transactions, CurrentPrincipalResolver principals,
                                      WorkspaceGovernanceLocks locks, AuthorizationService authorization, boolean resourceAclEnabled) {
        this.transactions = Objects.requireNonNull(transactions);
        this.principals = Objects.requireNonNull(principals);
        this.locks = Objects.requireNonNull(locks);
        this.authorization = Objects.requireNonNull(authorization);
        this.resourceAclEnabled = resourceAclEnabled;
    }

    @Override
    public <T> T inCurrentTransaction(TrustedActor actor, List<AccessRequest> requests, Supplier<T> work) {
        transactions.requireWritable();
        principals.requireActive(actor);
        List<AccessRequest> inputs = List.copyOf(requests);
        if (inputs.isEmpty() || inputs.size() > 100)
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, "write-guard");
        if (!resourceAclEnabled) {
            for (AccessRequest request : inputs) {
                boolean root = request.resource().type().equals(WORKSPACE) && request.resource().resourceId().equals(request.scope().workspaceId())
                        || request.resource().type().equals(TENANT) && request.resource().resourceId().equals(request.scope().tenantId());
                if (!root) throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "write-guard");
            }
        }
        // 租户治理无需空间资格；混合请求仍统一先锁全部租户，再锁目标空间及成员。
        locks.duringAuthorization(() -> {
            locks.ranges(actor.principal().subjectId(), inputs.stream().map(request -> request.scope().tenantId()).toList(),
                    inputs.stream().filter(request -> !request.resource().type().equals(TENANT))
                            .map(request -> request.scope().requireWorkspace()).toList());
            for (AccessRequest request : inputs) authorization.requireInCurrentTransaction(actor, request);
            return Boolean.TRUE;
        });
        return Objects.requireNonNull(work).get();
    }
}
