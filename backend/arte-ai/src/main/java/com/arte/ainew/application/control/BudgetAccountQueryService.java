package com.arte.ainew.application.control;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

/**
 * 当前授权下读取固定预算账户，不初始化账户或修改账本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 17:38 ✾
 */
public final class BudgetAccountQueryService {
    private final BudgetDefinitionProvider definitions;
    private final AdmissionAuthorization authorization;
    private final BudgetService budgets;

    /**
     * 旧手工装配兼容入口；生产装配使用显式业务端口。
     */
    @Deprecated(since = "control-ports-batch-4")
    public BudgetAccountQueryService(FixedControlCatalog configuration, AdmissionAuthorization authorization, BudgetService budgets) {
        this(configuration.readAdapter(), authorization, budgets);
    }

    public BudgetAccountQueryService(BudgetDefinitionProvider definitions, AdmissionAuthorization authorization, BudgetService budgets) {
        this.definitions = definitions;
        this.authorization = authorization;
        this.budgets = budgets;
    }

    public Mono<BudgetCommands.Account> account(String budgetRef, ExecutionContext context) {
        ContractChecks.id(budgetRef, "budgetRef");
        return authorization.require(context, AdmissionAuthorization.READ).flatMap(current -> {
            var owner = ExecutionOwners.from(current);
            return definitions.resolve(budgetRef, current, BudgetDefinitionProvider.Operation.READ).flatMap(definition -> {
                if (!budgetRef.equals(definition.budgetRef()) || !owner.equals(definition.owner()))
                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE));
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
