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

    public BudgetAccountQueryService(BudgetDefinitionProvider definitions, AdmissionAuthorization authorization, BudgetService budgets) {
        this.definitions = definitions;
        this.authorization = authorization;
        this.budgets = budgets;
    }

    private com.arte.ainew.spi.auth.BudgetReconciliationAccess access;

    /**
     * 组合模块安装账户责任及公共授权投影。
     */
    public void accountAccess(com.arte.ainew.spi.auth.BudgetReconciliationAccess access) {
        this.access = access;
    }

    public static com.arte.core.pojo.access.ResourceRef accountRef(BudgetCommands.Account account) {
        return new com.arte.core.pojo.access.ResourceRef(account.owner().tenantId(),
                com.arte.core.constant.platform.PlatformResourceTypes.BUDGET_ACCOUNT, account.budgetRef());
    }

    public Mono<com.arte.ainew.spi.auth.BudgetReconciliationAccess.Handling> handling(BudgetCommands.Account account, ExecutionContext actor) {
        return authorization.isOperationConfigured(actor, AdmissionAuthorization.BUDGET_ADMIN).flatMap(admin -> access == null
                ? Mono.just(new com.arte.ainew.spi.auth.BudgetReconciliationAccess.Handling(admin ? "SELF_SERVICE" : "UNASSIGNED", null))
                : access.handling(account, actor, admin));
    }

    /**
     * 本人预算与处理责任的只读组合。
     *
     * @param account  已按当前身份核验的账本
     * @param handling 当前可达处理责任，不授予操作权
     */
    public record View(BudgetCommands.Account account,
                       com.arte.ainew.spi.auth.BudgetReconciliationAccess.Handling handling) {
    }

    public Mono<View> view(String budgetRef, ExecutionContext actor) {
        return account(budgetRef, actor).flatMap(account -> handling(account, actor).map(handling ->
                new View(account, handling)));
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
