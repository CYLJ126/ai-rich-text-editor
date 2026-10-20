package com.arte.ainew.application.tool.outcome;

import com.arte.ainew.pojo.tool.ToolReconciliation;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.spi.persistence.ToolReconciliationStore;
import com.arte.core.pojo.platform.Scope;
import reactor.core.publisher.Mono;

/**
 * 当前空间的核对用例；会话捕获与每次事务授权均沿用既有宿主。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public final class ToolReconciliationService {

    private final ToolReconciliationStore store;

    public ToolReconciliationService(ToolReconciliationStore store) {
        this.store = store;
    }

    public Mono<ToolReconciliation.Detail> get(ToolManagementAccess.Session session, Scope scope, String invocationId, boolean managed) {
        return session.authorize(scope, managed ? ToolManagementAccess.Operation.RECONCILE : ToolManagementAccess.Operation.LIST)
                .flatMap(p -> store.reconciliation(p, invocationId, managed));
    }

    public Mono<ToolReconciliation.Page> list(ToolManagementAccess.Session session, Scope scope, String cursor, int limit) {
        return session.authorize(scope, ToolManagementAccess.Operation.RECONCILE).flatMap(p -> store.reconciliations(p, cursor, limit));
    }

    public Mono<com.arte.ainew.pojo.tool.ToolOutcomeEvidence> evidence(ToolManagementAccess.Session session, Scope scope, String id) {
        return session.authorize(scope, ToolManagementAccess.Operation.RECONCILE).flatMap(p -> store.reviewEvidence(p, id));
    }

    public Mono<ToolReconciliation.Receipt> command(ToolManagementAccess.Session session, Scope scope, String key, ToolReconciliation.Command command) {
        return session.authorize(scope, ToolManagementAccess.Operation.RECONCILE).flatMap(p -> store.reviewCommand(p, key, command));
    }
}
