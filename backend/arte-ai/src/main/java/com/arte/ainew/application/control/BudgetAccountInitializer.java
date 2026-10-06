package com.arte.ainew.application.control;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

/**
 * 显式的预算准备操作，要求独立管理权限；不会启动时执行或覆盖已经发生的 held／charged。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class BudgetAccountInitializer {
    private final FixedControlCatalog configuration;
    private final AdmissionAuthorization authorization;
    private final BudgetService budgets;
    private final AdmissionCatalogStore store;

    public BudgetAccountInitializer(FixedControlCatalog configuration, AdmissionAuthorization authorization,
                                    BudgetService budgets, AdmissionCatalogStore store) {
        this.configuration = configuration;
        this.authorization = authorization;
        this.budgets = budgets;
        this.store = store;
    }

    public Mono<BudgetCommands.Account> initialize(String ref, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.BUDGET_ADMIN).flatMap(current -> {
            var definition = configuration.budget(ref);
            if (!definition.owner().equals(ExecutionOwner.from(current)) || !authorization.grant(current).budgetRefs().contains(ref)) {
                throw new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE);
            }
            var zero = new Money(BigDecimal.ZERO, definition.limit().currency());
            var candidate = new BudgetCommands.Account(ref, definition.owner(), definition.limit(), zero, zero, definition.rate(), 0);
            return budgets.account(definition.owner(), ref)
                    .switchIfEmpty(Mono.defer(() -> store.createAccount(candidate)
                            .onErrorResume(DuplicateKeyException.class, ignored -> Mono.empty())
                            .then(budgets.account(definition.owner(), ref))))
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED)))
                    .map(account -> {
                        if (!account.owner().equals(candidate.owner()) || !account.limit().equals(candidate.limit())
                                || !account.rateVersion().equals(candidate.rateVersion())) {
                            throw new AdmissionException(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT);
                        }
                        return account;
                    });
        });
    }
}
