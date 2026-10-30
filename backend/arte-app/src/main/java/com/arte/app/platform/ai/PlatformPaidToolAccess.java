package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.ainew.pojo.control.PublishedAiDefinitions;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.adapter.PaidToolAdapter;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

/**
 * 收费工具组合公共身份／空间门控及发布／预算端口，提交事务按同一物理事务再次核验。
 */
public final class PlatformPaidToolAccess {
    private final PlatformToolManagementAccess platform;
    private final StaticToolRegistry registry;
    private final PublishedAiCatalog published;
    private final BudgetDefinitionProvider budgets;

    public PlatformPaidToolAccess(PlatformToolManagementAccess platform, StaticToolRegistry registry,
                                  PublishedAiCatalog published, BudgetDefinitionProvider budgets) {
        this.platform = platform;
        this.registry = registry;
        this.published = published;
        this.budgets = budgets;
    }

    public Mono<ToolAdmissionAccess.Permit> authorize(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.defer(() -> {
            requireContract(input, context, binding);
            return platform.authorizeUse(context).flatMap(current -> {
                var scopes = new java.util.HashSet<>(context.authorization().scopes());
                scopes.retainAll(current.executionScopes());
                if (!scopes.containsAll(binding.tool().tool().risk().requiredScopes())) throw denied();
                return published.inSnapshot(context, definitions -> budgets.resolve(input.budgetRef(), context, BudgetDefinitionProvider.Operation.EXECUTE)
                        .map(budget -> {
                            requireDefinitions(input, binding, definitions, budget);
                            return (ToolAdmissionAccess.Permit) new ToolAdmissionAccess.Permit() {
                                public ExecutionAuthorization authorization() {
                                    return context.authorization().restrictTo(scopes);
                                }

                                public void requireInCurrentTransaction(ToolExecutionSnapshot actual) {
                                    current.requireInCurrentTransaction(actual.binding().tool().tool().risk().requiredScopes());
                                    requireContract(actual.input(), context, actual.binding());
                                    if (!java.util.Objects.equals(actual.input().budgetRef(), actual.request().context().budgetRef()))
                                        throw denied();
                                    // 公共门控先取锁，精确目录与预算资格在同一事务读取范围内复核。
                                    published.inCurrentTransaction(context, latest -> {
                                        var selected = budgets.resolveInCurrentTransaction(actual.input().budgetRef(), context, BudgetDefinitionProvider.Operation.EXECUTE);
                                        requireDefinitions(actual.input(), actual.binding(), latest, selected);
                                        return null;
                                    });
                                }
                            };
                        }));
            });
        });
    }

    private void requireContract(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        var adapter = registry.executor(binding.tool()).orElseThrow(PlatformPaidToolAccess::denied);
        if (binding.tool().tool().price() == null || input.budgetRef() == null || !binding.owner().equals(ExecutionOwners.from(context))
                || !(adapter instanceof PaidToolAdapter paid) || !paid.revision().equals(binding.tool())) throw denied();
        paid.requireCharge(input, context, binding);
    }

    private void requireDefinitions(ToolAdmissionInput input, ToolBindingResolution binding, PublishedAiDefinitions definitions, BudgetDefinition budget) {
        var price = binding.tool().tool().price();
        var rate = definitions.rate(price.rate()).orElseThrow(PlatformPaidToolAccess::denied);
        if (!budget.budgetRef().equals(input.budgetRef()) || !budget.owner().equals(binding.owner()) || !budget.rate().equals(price.rate())
                || !price.perCall().equals(rate.toolPerCall()) || !budget.limit().currency().equals(price.perCall().currency()))
            throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current paid tool access denied"); }
}
