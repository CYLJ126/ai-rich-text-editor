package com.arte.ainew.application.control;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.ExecutionPolicyChecks;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.AiExecutionPolicy;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

/**
 * 模型能力／绑定业务入口
 * <p>
 * 组合发布、资格、预算及政策端口；实际写入由独立事务门控复核。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 16:50 ✾
 */
public final class ModelControlService implements CapabilityCatalog, BindingManager {

    private final PublishedAiCatalog published;
    private final AiResourceEntitlementService entitlements;
    private final BudgetDefinitionProvider budgets;
    private final AiExecutionPolicyProvider policies;
    private final AiExecutionPolicy ceiling;
    private final Clock clock;

    public ModelControlService(PublishedAiCatalog published, AiResourceEntitlementService entitlements,
                               BudgetDefinitionProvider budgets, AiExecutionPolicyProvider policies,
                               AiExecutionPolicy ceiling, Clock clock) {
        this.published = Objects.requireNonNull(published);
        this.entitlements = Objects.requireNonNull(entitlements);
        this.budgets = Objects.requireNonNull(budgets);
        this.policies = Objects.requireNonNull(policies);
        this.ceiling = Objects.requireNonNull(ceiling);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Mono<ResolvedBinding> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context) {
        return entitlements.resolve(binding, capability, context).map(AiEntitlement::binding);
    }

    @Override
    public Mono<CapabilityDescriptor> resolve(DefinitionRef capability, ExecutionContext context) {
        return entitlements.resolveCapability(capability, context);
    }

    @Override
    public Flux<CapabilityDescriptor> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        return Flux.defer(() -> {
            ContractChecks.range(limit, "limit", 1, 256);
            return entitlements.discover(kind, 256, context).map(value -> value.binding().capability()).distinct().take(limit);
        });
    }

    @Override
    public Mono<Void> validate(InvocationRequest<?> request) {
        return Mono.deferContextual(values -> published.inSnapshot(request.context(), configuration ->
                resolve(request.binding(), request.capability(), request.context())
                .flatMap(binding -> {
                    if (!(request.input() instanceof GenerationRequest generation)
                            || request.kind() != CapabilityDescriptor.Kind.GENERATION
                            || !(generation.outputFormat() instanceof GenerationRequest.TextOutput)
                            || !generation.tools().isEmpty() && !binding.capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)) {
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY));
                    }
                    ExecutionContext current = request.context();
                    if (!configuration.releaseRef().equals(current.releaseRef()) || current.budgetRef() == null) {
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
                    }
                    return budgets.resolve(current.budgetRef(), current, BudgetDefinitionProvider.Operation.EXECUTE)
                            .onErrorMap(AdmissionException.class, error -> error.getResultCode() == ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE
                                    ? new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE) : error)
                            .flatMap(budget -> {
                                if (!budget.rate().equals(binding.rate())) {
                                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH));
                                }
                                return policies.current(current).doOnNext(policy -> ExecutionPolicyChecks.require(
                                        new AiAdmissionAuthorizationGuard.Check(request, binding,
                                                values.getOrDefault(com.arte.ainew.pojo.context.ContextSnapshot.class, null), null,
                                                AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND), policy.intersect(ceiling), clock)).then();
                            });
                })));
    }
}
