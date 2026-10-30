package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.ainew.pojo.control.PublishedAiDefinitions;
import com.arte.ainew.pojo.control.RateDefinition;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * FIXED／数据库来源共用的转换与预算资格规则；不注册为第三种来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 12:18 ✾
 */
public abstract class ControlReadAdapter implements PublishedAiCatalog, BudgetDefinitionProvider {

    protected final ControlConfigurationReader reader;
    private final AdmissionAuthorization authorization;

    protected ControlReadAdapter(ControlConfigurationReader reader, AdmissionAuthorization authorization) {
        this.reader = Objects.requireNonNull(reader);
        this.authorization = Objects.requireNonNull(authorization);
    }

    static RateDefinition rate(NewAiProperties.Rate rate) {
        return new RateDefinition(rate.definition(), rate.inputPerMillion(), rate.outputPerMillion(), rate.toolPerCall());
    }

    @Override
    public <T> Mono<T> inSnapshot(ExecutionContext context, Function<PublishedAiDefinitions, Mono<T>> work) {
        return reader.inSnapshot(context, value -> work.apply(new PublishedAiDefinitions(value.releaseRef(),
                value.capabilities(), value.bindings(), value.connections(), value.rates().stream().map(ControlReadAdapter::rate).toList())));
    }

    @Override
    public Mono<List<BudgetDefinition>> available(ExecutionContext context, Operation operation) {
        return reader.read(context).map(value -> budgetDefinitions(value, context, operation));
    }

    @Override
    public BudgetDefinition resolveInCurrentTransaction(String budgetRef, ExecutionContext context, Operation operation) {
        return reader.inCurrentTransaction(context, value -> budgetDefinitions(value, context, operation).stream()
                .filter(b -> b.budgetRef().equals(budgetRef)).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE)));
    }

    private List<BudgetDefinition> budgetDefinitions(NewAiProperties value, ExecutionContext context, Operation operation) {
        String required = switch (operation) {
            case READ -> AdmissionAuthorization.READ;
            case INITIALIZE -> AdmissionAuthorization.BUDGET_ADMIN;
            case EXECUTE -> AdmissionAuthorization.INVOKE;
        };
        NewAiProperties.Grant grant = authorization.grant(value, context);
        if (!context.authorization().scopes().contains(required) || !grant.scopes().containsAll(context.authorization().scopes())
                || !grant.scopes().contains(required)) {
            throw new AccessDeniedException("Budget definition scope denied");
        }
        var owner = ExecutionOwners.from(context);
        ContractChecks.unique(value.budgets().stream().map(NewAiProperties.Budget::budgetRef).toList(), "budgets");
        return value.budgets().stream().filter(b -> grant.budgetRefs().contains(b.budgetRef()) && b.owner().equals(owner))
                .map(b -> {
                    NewAiProperties.Rate rate = value.rates().stream().filter(r -> r.definition().equals(b.rate())).findFirst()
                            .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH));
                    if (!rate.currency().equals(b.limit().currency())) {
                        throw new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH);
                    }
                    return new BudgetDefinition(b.budgetRef(), b.owner(), b.limit(), b.rate());
                }).toList();
    }
}
