package com.arte.ainew.application.budget;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.spi.persistence.ReconciliationStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

/**
 * 普通聊天请求不能自报费用；ai:budget:admin 才能凭账单和执行结束证明进行人工核对。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
public final class ManualReconciliationService {
    private final AdmissionAuthorization authorization;
    private final BudgetAccountQueryService budgetAccountQueryService;
    private final ReconciliationStore reconciliationStore;

    public ManualReconciliationService(AdmissionAuthorization authorization, BudgetAccountQueryService budgetAccountQueryService, ReconciliationStore reconciliationStore) {
        this.authorization = authorization;
        this.budgetAccountQueryService = budgetAccountQueryService;
        this.reconciliationStore = reconciliationStore;
    }

    public record PendingPage(ConversationPage<Reconciliation.Pending> page, boolean canReconcile) {
    }

    public Mono<PendingPage> pending(String budgetRef, long current, long size, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(authorized ->
                budgetAccountQueryService.account(budgetRef, authorized).flatMap(account ->
                        reconciliationStore.pendingReconciliations(ExecutionOwner.from(authorized), budgetRef, current, size)
                                .map(page -> new PendingPage(page, authorization.grant(authorized).scopes().contains(AdmissionAuthorization.BUDGET_ADMIN)))));
    }

    public Mono<Reconciliation.Receipt> confirm(String budgetRef, String invocationId, long invocationVersion,
                                                String reservationId, long reservationVersion, Money charge, String evidenceRef, String note,
                                                boolean executionEnded, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.BUDGET_ADMIN).flatMap(authorized ->
                budgetAccountQueryService.account(budgetRef, authorized).flatMap(account -> {
                    if (!account.limit().currency().equals(charge.currency()))
                        throw new AdmissionException(ResultCodeEnum.AI_CURRENCY_MISMATCH);
                    return reconciliationStore.confirmReconciliation(new Reconciliation.Confirm(ExecutionOwner.from(authorized),
                            authorized.authorization().principal(), authorized.traceId(), authorized.idempotencyKey(), budgetRef, invocationId,
                            invocationVersion, reservationId, reservationVersion, charge, evidenceRef, note, executionEnded));
                }).map(result -> {
                    if (!result.successful()) throw AdmissionException.fromStoreRejection(result.code());
                    if (!result.value().budgetRef().equals(budgetRef))
                        throw new IllegalStateException("Reconciliation account mismatch");
                    return result.value();
                }));
    }

    public Mono<Reconciliation.Receipt> receipt(String budgetRef, String invocationId, String key, ExecutionContext context) {
        return budgetAccountQueryService.account(budgetRef, context).then(reconciliationStore.reconciliationReceipt(ExecutionOwner.from(context), invocationId, key))
                .filter(receipt -> receipt.budgetRef().equals(budgetRef))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }
}
