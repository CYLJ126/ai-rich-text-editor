package com.arte.ainew.application.tool.billing;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.tool.ToolCharge;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolPrice;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.ainew.spi.persistence.ToolBillingStore;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 工具按次价格和当前账户资格；复用原账本，未知费用保留预留，业务结果不替代账单证据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public final class ToolBillingService {

    private boolean enabled = true;

    public ToolBillingService enabled(boolean value) {
        enabled = value;
        return this;
    }

    private final AdmissionAuthorization authorization;
    private final FixedControlCatalog catalog;
    private final BudgetService budgets;
    private final ExecutionStore executions;
    private final ToolBillingStore bills;

    public ToolBillingService(AdmissionAuthorization authorization, FixedControlCatalog catalog, BudgetService budgets,
                              ExecutionStore executions, ToolBillingStore bills) {
        this.authorization = authorization;
        this.catalog = catalog;
        this.budgets = budgets;
        this.executions = executions;
        this.bills = bills;
    }

    public Mono<BudgetCommands.Account> requireAccount(String ref, ToolPrice price, ExecutionContext context) {
        if (!enabled || ref == null) return denied();
        return bills.toolPriceBlocked(price.rate()).flatMap(blocked -> blocked ? ToolBillingService.<ExecutionContext>denied()
                : authorization.require(context, AdmissionAuthorization.INVOKE)).flatMap(current -> catalog.configuration(current).flatMap(config -> {
            var definition = config.budgets().stream().filter(b -> b.budgetRef().equals(ref)).findFirst().orElse(null);
            var rate = config.rates().stream().filter(r -> r.definition().equals(price.rate())).findFirst().orElse(null);
            if (!authorization.grant(config, current).budgetRefs().contains(ref) || definition == null || rate == null
                    || !price.perCall().equals(rate.toolPerCall()) || !definition.rate().equals(price.rate())
                    || !definition.owner().equals(ExecutionOwners.from(current)) || !definition.limit().currency().equals(price.perCall().currency()))
                return denied();
            return budgets.account(definition.owner(), ref).switchIfEmpty(denied()).flatMap(account ->
                    account.owner().equals(definition.owner()) && account.rateVersion().equals(price.rate()) && account.limit().equals(definition.limit())
                            ? Mono.just(account) : denied());
        }));
    }

    public Mono<java.util.List<BudgetCommands.Account>> available(ToolPrice price, ExecutionContext context) {
        if (!enabled) return denied();
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current -> catalog.configuration(current).flatMap(config -> {
            var grant = authorization.grant(config, current);
            var rate = config.rates().stream().filter(r -> r.definition().equals(price.rate()) && price.perCall().equals(r.toolPerCall())).findFirst();
            if (rate.isEmpty()) return Mono.just(java.util.List.of());
            return reactor.core.publisher.Flux.fromIterable(config.budgets()).filter(b -> grant.budgetRefs().contains(b.budgetRef())
                            && b.owner().equals(ExecutionOwners.from(current)) && b.rate().equals(price.rate()) && b.limit().currency().equals(price.perCall().currency()))
                    .concatMap(b -> budgets.account(b.owner(), b.budgetRef()).filter(a -> a.owner().equals(b.owner())
                            && a.rateVersion().equals(b.rate()) && a.limit().equals(b.limit()))).collectList();
        }));
    }

    private static <T> Mono<T> denied() {
        return Mono.error(new AccessDeniedException("Current tool budget unavailable"));
    }

    public DispatchPreparationStore.BillingPlan plan(ToolExecutionSnapshot fixed) {
        var price = fixed.binding().tool().tool().price();
        return price == null ? new DispatchPreparationStore.Free() : new DispatchPreparationStore.Charge(price.perCall(), price.rate(), Duration.ofHours(1));
    }

    public Mono<Void> record(ExecutionOwner owner, String invocation, String attempt, ToolCharge charge) {
        return charge == null ? Mono.empty() : bills.recordToolCharge(owner, invocation, attempt, charge);
    }

    public Mono<Void> settle(Invocation invocation) {
        if (invocation.request().context().budgetRef() == null || invocation.activeAttemptId() == null)
            return Mono.empty();
        var owner = ExecutionOwners.from(invocation.request().context());
        return executions.findAttempt(owner, invocation.request().context().executionId(), invocation.activeAttemptId()).flatMap(attempt -> {
            if (attempt.budgetReservationId() == null) return Mono.empty();
            return budgets.reservation(owner, attempt.budgetReservationId()).flatMap(reservation -> {
                if (reservation.state() != BudgetReservation.State.RESERVED) return Mono.empty();
                return bills.toolCharge(owner, attempt.invocationId(), attempt.attemptId()).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()).flatMap(report -> {
                    boolean notSent = attempt.dispatch() == Attempt.Dispatch.NOT_STARTED;
                    var bill = report.orElse(null);
                    boolean noInvocation = bill != null && bill.kind() == ToolCharge.Kind.NO_INVOCATION;
                    var state = notSent || noInvocation ? BudgetSettlement.State.RELEASED : bill == null ? BudgetSettlement.State.PENDING_RECONCILIATION : BudgetSettlement.State.SETTLED;
                    var amount = notSent ? new Money(BigDecimal.ZERO, reservation.reserved().currency()) : bill == null ? null : bill.amount();
                    var settlement = new BudgetSettlement(CanonicalJson.key(attempt.attemptId(), "tool-bill-v1"), reservation.reservationId(), state,
                            Usage.unknown(), amount, attempt.updatedAt());
                    return budgets.settle(new BudgetCommands.Settle(owner, reservation.version(), settlement,
                            notSent ? BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED : noInvocation ? BudgetCommands.Evidence.PROVEN_NO_EXECUTION : bill == null ? BudgetCommands.Evidence.UNKNOWN_COST : BudgetCommands.Evidence.PROVIDER_BILL,
                            notSent ? "attempt:" + attempt.attemptId() : bill == null ? null : bill.evidenceRef())).flatMap(result ->
                            result.successful() ? Mono.empty() : Mono.error(new IllegalStateException("Tool bill settlement rejected: " + result.code())));
                });
            });
        });
    }
}
