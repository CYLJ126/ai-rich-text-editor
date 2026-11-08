package com.arte.ainew.application.budget;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.spi.auth.BudgetReconciliationAccess;
import com.arte.ainew.spi.persistence.ReconciliationStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.ResourceRef;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

/**
 * 当前本人或获明确账户授权的责任人凭实际账单核对；目标 owner 与真实 actor 分离。
 */
public final class ManualReconciliationService {
    private final AdmissionAuthorization authorization;
    private final BudgetAccountQueryService accounts;
    private final ReconciliationStore store;
    private BudgetReconciliationAccess access;

    public ManualReconciliationService(AdmissionAuthorization authorization, BudgetAccountQueryService accounts, ReconciliationStore store) {
        this.authorization = authorization;
        this.accounts = accounts;
        this.store = store;
    }

    /**
     * 由组合模块安装公共账户授权；未安装时管理路径默认拒绝。
     */
    public void accountAccess(BudgetReconciliationAccess access) {
        this.access = access;
    }

    /**
     * 核对分页及当前提交提示。
     *
     * @param page         当前有权读取的财务记录
     * @param canReconcile 当前提示，提交仍须在事务中重新授权
     */
    public record PendingPage(ConversationPage<Reconciliation.Pending> page, boolean canReconcile) { }

    public Mono<PendingPage> pending(String budgetRef, long current, long size, ExecutionContext context) {
        return accounts.account(budgetRef, context).flatMap(account ->
                store.pendingReconciliations(account.owner(), budgetRef, current, size).flatMap(page ->
                        accounts.handling(account, context).map(handling -> new PendingPage(page, handling.status().equals("SELF_SERVICE")))));
    }

    public Mono<PendingPage> managedPending(ResourceRef accountRef, long current, long size, ExecutionContext context) {
        return managed(accountRef, context, false).flatMap(permit -> store.pendingReconciliations(permit, current, size)
                .flatMap(page -> authorization.isOperationConfigured(context, AdmissionAuthorization.BUDGET_ADMIN)
                        .map(allowed -> new PendingPage(new ConversationPage<>(page.current(), page.size(), page.total(),
                                page.records().stream().map(p -> new Reconciliation.Pending(p.invocationId(), null, p.state(), p.invocationVersion(),
                                        p.reservationId(), p.reservationVersion(), p.budgetRef(), p.reserved(), p.remoteRequestId(), p.acceptedAt(), p.pendingSince())).toList()), allowed))));
    }

    public Mono<Reconciliation.Receipt> confirm(String budgetRef, String invocationId, long invocationVersion,
                                                String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                                boolean executionEnded, ExecutionContext context) {
        return authorization.requireWrite(context, AdmissionAuthorization.BUDGET_ADMIN).flatMap(current ->
                accounts.account(budgetRef, current).flatMap(account -> {
                    if (!account.limit().currency().equals(charge.currency()))
                        throw new AdmissionException(ResultCodeEnum.AI_CURRENCY_MISMATCH);
                    var command = command(account.owner(), budgetRef, invocationId, invocationVersion, reservationId, reservationVersion,
                            charge, evidenceRef, note, executionEnded, current);
                    if (access == null) return result(store.confirmReconciliation(command), budgetRef);
                    return access.authorize(BudgetAccountQueryService.accountRef(account), current, true)
                            .flatMap(permit -> result(store.confirmReconciliation(command, permit), budgetRef));
                }));
    }

    public Mono<Reconciliation.Receipt> confirmManaged(ResourceRef accountRef, String budgetRef, String invocationId, long invocationVersion,
                                                       String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                                       boolean executionEnded, ExecutionContext context) {
        return managed(accountRef, context, true).flatMap(permit -> {
            var account = permit.account();
            if (!account.budgetRef().equals(budgetRef)) throw new AccessDeniedException("Account target mismatch");
            if (!account.limit().currency().equals(charge.currency()))
                throw new AdmissionException(ResultCodeEnum.AI_CURRENCY_MISMATCH);
            return result(store.confirmReconciliation(command(account.owner(), budgetRef, invocationId, invocationVersion, reservationId,
                    reservationVersion, charge, evidenceRef, note, executionEnded, context), permit), budgetRef);
        });
    }

    private Reconciliation.Confirm command(com.arte.core.pojo.platform.ExecutionOwner owner, String budgetRef, String invocationId, long invocationVersion,
                                           String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                           boolean executionEnded, ExecutionContext actor) {
        return new Reconciliation.Confirm(owner, actor.authorization().principal(), actor.traceId(), actor.idempotencyKey(), budgetRef,
                invocationId, invocationVersion, reservationId, reservationVersion, charge, evidenceRef, note, executionEnded);
    }

    private Mono<Reconciliation.Receipt> result(Mono<com.arte.ainew.pojo.execution.StoreOutcome<Reconciliation.Receipt>> work, String budgetRef) {
        return work.map(result -> {
            if (!result.successful()) throw AdmissionException.fromStoreRejection(result.code());
            if (!result.value().budgetRef().equals(budgetRef))
                throw new IllegalStateException("Reconciliation account mismatch");
            return result.value();
        });
    }

    private Mono<BudgetReconciliationAccess.Permit> managed(ResourceRef accountRef, ExecutionContext actor, boolean write) {
        if (access == null) return Mono.error(new AccessDeniedException("Account authorization unavailable"));
        return (write ? authorization.requireWrite(actor, AdmissionAuthorization.BUDGET_ADMIN) : authorization.require(actor, AdmissionAuthorization.READ))
                .flatMap(current -> access.authorize(accountRef, current, write));
    }

    public Mono<Reconciliation.Receipt> receipt(String budgetRef, String invocationId, String key, ExecutionContext context) {
        return accounts.account(budgetRef, context).then(store.reconciliationReceipt(ExecutionOwners.from(context), invocationId, key))
                .filter(receipt -> receipt.budgetRef().equals(budgetRef))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    public Mono<Reconciliation.Receipt> managedReceipt(ResourceRef accountRef, String invocationId, String key, ExecutionContext context) {
        return managed(accountRef, context, false).flatMap(permit -> store.reconciliationReceipt(permit, invocationId, key))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }
}
