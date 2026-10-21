package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.spi.adapter.PaidToolAdapter;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

/**
 * 收费工具复用当前平台身份／空间／RBAC 和配置 grant；固定合同、资源及账户在提交事务再次核验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public final class PlatformPaidToolAccess {

    private final PlatformToolManagementAccess platform;
    private final LocalToolRegistry registry;
    private final NewAiProperties fixed;
    private final ObjectProvider<AiControlConfigurationSource> sources;

    public PlatformPaidToolAccess(PlatformToolManagementAccess platform, LocalToolRegistry registry, NewAiProperties fixed,
                                  ObjectProvider<AiControlConfigurationSource> sources) {
        this.platform = platform;
        this.registry = registry;
        this.fixed = fixed;
        this.sources = sources;
    }

    public Mono<ToolAdmissionAccess.Permit> authorize(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.defer(() -> {
            require(input, context, binding);
            return platform.authorizeUse(context).map(current -> {
                var scopes = new java.util.HashSet<>(context.authorization().scopes());
                scopes.retainAll(current.executionScopes());
                if (!scopes.containsAll(binding.tool().tool().risk().requiredScopes())) throw denied();
                return new ToolAdmissionAccess.Permit() {
                    public ExecutionAuthorization authorization() {
                        return context.authorization().restrictTo(scopes);
                    }

                    public void requireInCurrentTransaction(ToolExecutionSnapshot actual) {
                        current.requireInCurrentTransaction(actual.binding().tool().tool().risk().requiredScopes());
                        require(actual.input(), context, actual.binding());
                        if (!java.util.Objects.equals(actual.input().budgetRef(), actual.request().context().budgetRef()))
                            throw denied();
                    }
                };
            });
        });
    }

    private void require(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        var price = binding.tool().tool().price();
        var adapter = registry.executor(binding.tool()).orElseThrow(PlatformPaidToolAccess::denied);
        if (price == null || input.budgetRef() == null || !binding.owner().equals(ExecutionOwner.from(context))
                || !(adapter instanceof PaidToolAdapter paid) || !paid.revision().equals(binding.tool()))
            throw denied();
        paid.requireCharge(input, context, binding);
        var source = sources.getIfAvailable();
        var configuration = source == null ? fixed : source.configuration(context);
        var grant = configuration.grants().stream().filter(g -> g.enabled() && g.subjectId().equals(context.authorization().principal().subjectId())
                && g.subjectName().equals(context.authorization().principal().subjectName()) && g.principalKind() == context.authorization().principal().kind()
                && g.tenantId().equals(binding.owner().tenantId()) && g.workspaceId().equals(binding.owner().workspaceId())
                && g.scopes().containsAll(binding.tool().tool().risk().requiredScopes())).findFirst().orElseThrow(PlatformPaidToolAccess::denied);
        var budget = configuration.budgets().stream().filter(v -> v.budgetRef().equals(input.budgetRef())).findFirst().orElseThrow(PlatformPaidToolAccess::denied);
        var rate = configuration.rates().stream().filter(v -> v.definition().equals(price.rate())).findFirst().orElseThrow(PlatformPaidToolAccess::denied);
        if (!grant.budgetRefs().contains(input.budgetRef()) || !budget.owner().equals(binding.owner()) || !budget.rate().equals(price.rate())
                || !price.perCall().equals(rate.toolPerCall()) || !budget.limit().currency().equals(price.perCall().currency()))
            throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current paid tool access denied");
    }
}
