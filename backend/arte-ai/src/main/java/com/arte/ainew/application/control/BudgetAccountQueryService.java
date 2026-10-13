package com.arte.ainew.application.control;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

/**
 * 当前授权下读取固定预算账户，不初始化账户或修改账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public final class BudgetAccountQueryService {
    private final FixedControlCatalog configuration;
    private final AdmissionAuthorization authorization;
    private final BudgetService budgets;

    public BudgetAccountQueryService(FixedControlCatalog configuration, AdmissionAuthorization authorization, BudgetService budgets) {
        this.configuration = configuration;
        this.authorization = authorization;
        this.budgets = budgets;
    }

    public Mono<BudgetCommands.Account> account(String budgetRef, ExecutionContext context) {
        ContractChecks.id(budgetRef, "budgetRef");
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            var owner = ExecutionOwner.from(current);
            return configuration.configuration(current).flatMap(snapshot -> {
                // 未知预算与其他主体预算均不可见；资格、定义来自同一次可信读取。
                if (!authorization.grant(snapshot, current).budgetRefs().contains(budgetRef))
                    throw new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE);
                var definition = snapshot.budgets().stream().filter(b -> b.budgetRef().equals(budgetRef)).findFirst()
                        .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE));
                if (!definition.owner().equals(owner)) {
                    throw new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE);
                }
                return budgets.account(owner, budgetRef)
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED)))
                        .map(account -> {
                            if (!account.budgetRef().equals(budgetRef) || !account.owner().equals(owner)
                                    || !account.limit().equals(definition.limit()) || !account.rateVersion().equals(definition.rate())) {
                                throw new AdmissionException(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT);
                            }
                            return account;
                    });
            });
        });
    }
}
