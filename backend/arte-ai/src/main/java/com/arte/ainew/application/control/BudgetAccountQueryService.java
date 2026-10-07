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
            // 先校验使用资格，使未知预算和其他主体预算返回相同的不可见响应。
            if (!authorization.grant(current).budgetRefs().contains(budgetRef)) {
                throw new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE);
            }
            var definition = configuration.budget(budgetRef);
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
    }
}
