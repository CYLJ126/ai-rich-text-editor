package com.arte.ainew.application.tool.chat;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 从当前配置与授权固定账户映射；构造子上下文不授予工具资源、审批或实际外发资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public final class ToolChatBudgetSelector {

    private final ToolChatRunStore runs;
    private final AdmissionAuthorization authorization;
    private final FixedControlCatalog catalog;
    private final BudgetService budgets;
    private final ToolBindingService bindings;
    private final Clock clock;
    private com.arte.ainew.application.tool.billing.ToolBillingService billing;

    public ToolChatBudgetSelector paid(com.arte.ainew.application.tool.billing.ToolBillingService service) {
        this.billing = service;
        return this;
    }

    public ToolChatBudgetSelector(ToolChatRunStore runs, AdmissionAuthorization authorization, FixedControlCatalog catalog,
                                  BudgetService budgets, ToolBindingService bindings, Clock clock) {
        this.runs = runs;
        this.authorization = authorization;
        this.catalog = catalog;
        this.budgets = budgets;
        this.bindings = bindings;
        this.clock = clock;
    }

    private static <T> Mono<T> denied() {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
    }

    private Mono<ToolChatBudgetPlan.Selection> model(ToolChatRunSnapshot fixed, String budgetRef, ExecutionContext current) {
        return catalog.resolve(fixed.model().definition(), fixed.model().capability().definition(), current).flatMap(model -> {
            if (!model.equals(fixed.model())) return denied();
            return authorization.configurationGrant(current).flatMap(grant -> {
                if (!grant.budgetRefs().contains(budgetRef)) return denied();
                return catalog.configuration(current).flatMap(configuration -> {
                    var rate = configuration.rates().stream().filter(r -> r.definition().equals(model.rate())).findFirst().orElse(null);
                    var configured = configuration.budgets().stream().filter(b -> b.budgetRef().equals(budgetRef)).findFirst().orElse(null);
                    var owner = ExecutionOwner.from(current);
                    if (rate == null || rate.toolPerCall() != null || configured == null || !configured.owner().equals(owner) || !configured.rate().equals(model.rate())
                            || !configured.limit().currency().equals(rate.inputPerMillion().currency()))
                        return denied();
                    return budgets.account(owner, budgetRef).switchIfEmpty(denied()).flatMap(account -> {
                        if (!account.owner().equals(owner) || !account.budgetRef().equals(budgetRef) || !account.rateVersion().equals(rate.definition())
                                || !account.limit().currency().equals(configured.limit().currency())) return denied();
                        return Mono.just(new ToolChatBudgetPlan.Selection(model.definition(), model.capability().definition(), model.capability().kind(),
                                BillingMode.CHARGE, budgetRef, model.rate(), new ToolChatBudgetPlan.ModelPrice(rate.inputPerMillion(), rate.outputPerMillion())));
                    });
                });
            });
        });
    }

    private Mono<ToolChatBudgetPlan.Selection> free(ToolSetSnapshot.Entry entry, ExecutionContext current) {
        if (entry.binding().tool().tool().price() != null) return denied();
        if (bindings == null) return denied();
        return bindings.fixed(ExecutionOwner.from(current), entry.binding().definition()).flatMap(result -> {
            if (!result.successful() || !result.value().equals(entry.binding())
                    || !com.arte.ainew.pojo.tool.ToolExecutionRules.supportedTarget(result.value(), result.value().effectivePolicy())
                    || !current.authorization().scopes().containsAll(entry.binding().tool().tool().risk().requiredScopes()))
                return denied();
            return Mono.just(new ToolChatBudgetPlan.Selection(entry.binding().definition(), entry.binding().tool().tool().capability(),
                    CapabilityDescriptor.Kind.TOOL, BillingMode.FREE, null, null, null));
        }).switchIfEmpty(denied());
    }

    private Mono<ToolChatBudgetPlan.Selection> tool(ToolSetSnapshot.Entry entry, String budgetRef, ExecutionContext current) {
        var price = entry.binding().tool().tool().price();
        if (price == null) return budgetRef == null ? free(entry, current) : denied();
        if (billing == null || bindings == null) return denied();
        return bindings.fixed(ExecutionOwner.from(current), entry.binding().definition()).flatMap(result -> {
            if (!result.successful() || !result.value().equals(entry.binding()) || !current.authorization().scopes().containsAll(entry.binding().tool().tool().risk().requiredScopes()))
                return denied();
            return billing.requireAccount(budgetRef, price, current).map(account -> new ToolChatBudgetPlan.Selection(entry.binding().definition(),
                    entry.binding().tool().tool().capability(), CapabilityDescriptor.Kind.TOOL, BillingMode.CHARGE, budgetRef, price.rate(), null, price));
        }).switchIfEmpty(denied());
    }

    /**
     * 受理前准备：预算上限可收紧，账户必须来自当前 grant 与实际账本，工具按固定合同选择 FREE／CHARGE。
     */
    public Mono<ToolChatBudgetPlan> prepare(ToolChatRunSnapshot fixed, List<Money> limits, String modelBudgetRef, ExecutionContext context) {
        return prepare(fixed, limits, modelBudgetRef, java.util.Map.of(), context);
    }

    public Mono<ToolChatBudgetPlan> prepare(ToolChatRunSnapshot fixed, List<Money> limits, String modelBudgetRef, java.util.Map<String, String> toolBudgets, ExecutionContext context) {
        ContractChecks.id(modelBudgetRef, "model budgetRef");
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current -> {
            if (!ExecutionOwner.from(current).equals(ExecutionOwner.from(fixed.context()))) return denied();
            return model(fixed, modelBudgetRef, current).flatMap(model -> Flux.fromIterable(fixed.tools().entries())
                    .concatMap(entry -> tool(entry, toolBudgets.get(entry.binding().definition().id()), current)).collectList().map(tools -> {
                        var selections = new ArrayList<>(tools);
                        selections.add(model);
                        return new ToolChatBudgetPlan(limits, selections);
                    }));
        });
    }

    /**
     * 恢复原计划子身份后重新授权，只从固定映射取得账户；不继承根的 null 预算或接受调用方换账户。
     */
    public Mono<ExecutionContext> child(String runId, String childId, Set<String> scopes, Instant requestedDeadline, ExecutionContext context) {
        Objects.requireNonNull(scopes);
        Objects.requireNonNull(requestedDeadline);
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current -> {
            var owner = ExecutionOwner.from(current);
            return runs.find(owner, runId).switchIfEmpty(denied()).flatMap(run -> {
                if (run.state().terminal() || run.state() == ToolChatRun.State.PENDING_RECONCILIATION
                        || !run.checkpoint().children().contains(childId)) return denied();
                return runs.snapshot(owner, runId).switchIfEmpty(denied()).flatMap(fixed -> {
                    if (fixed.budgetPlan() == null || !fixed.context().authorization().principal().equals(current.authorization().principal())
                            || !fixed.context().authorization().scopes().containsAll(scopes) || !current.authorization().scopes().containsAll(scopes)
                            || !scopes.contains(AdmissionAuthorization.INVOKE)) return denied();
                    return runs.step(owner, runId, childId).switchIfEmpty(denied()).flatMap(step -> {
                        ToolChatBudgetPlan.Selection selected;
                        Mono<ToolChatBudgetPlan.Selection> refreshed;
                        if (step.input() instanceof ToolChatStep.Model) {
                            selected = fixed.budgetPlan().selection(fixed.model().definition());
                            refreshed = model(fixed, selected.budgetRef(), current);
                        } else {
                            var call = ((ToolChatStep.Tool) step.input()).call();
                            var entry = fixed.tools().tool(call.tool());
                            if (!scopes.containsAll(entry.binding().tool().tool().risk().requiredScopes()))
                                return denied();
                            selected = fixed.budgetPlan().selection(entry.binding().definition());
                            refreshed = tool(entry, selected.budgetRef(), current);
                        }
                        return refreshed.flatMap(choice -> {
                            if (!choice.equals(selected)) return denied();
                            Instant deadline = requestedDeadline.isBefore(fixed.context().deadline()) ? requestedDeadline : fixed.context().deadline();
                            if (current.deadline().isBefore(deadline)) deadline = current.deadline();
                            if (!deadline.isAfter(clock.instant()))
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
                            return Mono.just(new ExecutionContext(childId, fixed.context().traceId(), current.authorization().restrictTo(scopes), deadline,
                                    runId, selected.budgetRef(), fixed.context().releaseRef(), CanonicalJson.key("tool-chat-child-submit-v1", childId)));
                        });
                    });
                });
            });
        });
    }
}
