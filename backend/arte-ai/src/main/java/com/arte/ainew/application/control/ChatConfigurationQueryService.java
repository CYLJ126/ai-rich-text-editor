package com.arte.ainew.application.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.ConfigurationExecutionPolicyProvider;
import com.arte.ainew.infrastructure.control.ControlConfigurationReader;
import com.arte.ainew.infrastructure.control.ExecutionPolicies;
import com.arte.ainew.pojo.budget.BudgetDefinition;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ChatConfigurationOptions;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.control.BudgetDefinitionProvider;
import com.arte.ainew.spi.control.PublishedAiCatalog;
import com.arte.ainew.spi.gateway.ModelGateway;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Supplier;

/**
 * 只读配置发现；按绑定保留不同模型及固定版本，过滤当前授权、网关支持和预算费率兼容性。
 * 不访问账本、不初始化账户、不解析凭据、不探测供应商或建立 Invocation。
 */
public final class ChatConfigurationQueryService {
    private final NewAiProperties properties;
    private final AdmissionAuthorization authorization;
    private final AiResourceEntitlementService entitlements;
    private final Supplier<ModelGateway> gateway;
    private final PublishedAiCatalog published;
    private final BudgetDefinitionProvider definitions;
    private AiExecutionPolicyProvider policies;

    public ChatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                         FixedControlCatalog catalog, Supplier<ModelGateway> gateway) {
        this(properties, authorization, catalog, catalog.readAdapter(), catalog.readAdapter(), gateway);
    }

    public ChatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                         FixedControlCatalog catalog, PublishedAiCatalog published,
                                         BudgetDefinitionProvider definitions, Supplier<ModelGateway> gateway) {
        this(properties, authorization, catalog.entitlementAdapter(), published, definitions, gateway);
        this.policies = catalog.policyProvider();
    }

    public ChatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                         AiResourceEntitlementService entitlements, PublishedAiCatalog published,
                                         BudgetDefinitionProvider definitions, Supplier<ModelGateway> gateway) {
        this.published = published;
        this.definitions = definitions;
        this.properties = properties;
        this.authorization = authorization;
        this.entitlements = entitlements;
        this.gateway = gateway;
        this.policies = new ConfigurationExecutionPolicyProvider(new ControlConfigurationReader(properties), properties.limits());
    }

    public ChatConfigurationQueryService policyProvider(AiExecutionPolicyProvider policies) {
        this.policies = policies;
        return this;
    }

    public Mono<ChatConfigurationOptions> discover(ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current -> {
            var modelGateway = gateway.get();
            if (modelGateway == null) {
                return Mono.just(new ChatConfigurationOptions(List.of()));
            }
            return published.inSnapshot(current, configuration -> policies.current(current).flatMap(policyValue -> {
                var policy = policyValue.intersect(ExecutionPolicies.from(properties.limits()));
                return definitions.available(current, BudgetDefinitionProvider.Operation.EXECUTE)
                    .flatMap(available -> entitlements.discover(CapabilityDescriptor.Kind.GENERATION, 256, current)
                            .filter(value -> value.binding().contextWindowTokens() >= 2
                                    && value.binding().capability().features().contains(CapabilityDescriptor.Feature.TEXT_INPUT)
                                    && value.binding().capability().features().contains(CapabilityDescriptor.Feature.STREAMING))
                            .filter(value -> modelGateway.supportsTextChat(value.binding(), value.connection()))
                            .map(value -> {
                                var binding = value.binding();
                        var budgets = available.stream()
                                .filter(budget -> budget.rate().equals(binding.rate()))
                                .map(BudgetDefinition::budgetRef).sorted().toList();
                        long window = binding.contextWindowTokens();
                                int outputLimit = (int) Math.min(policy.maxOutputTokens(), Math.min(1_000_000L, window - 1));
                                var limits = new ChatConfigurationOptions.Limits(window - 1, outputLimit, policy.maxInputBytes(),
                                        policy.maxOutputBytes(), policy.maximumTimeout().toSeconds());
                        int outputDefault = Math.min(512, outputLimit);
                        var defaults = new ChatConfigurationOptions.Defaults(Math.min(32_768L, window - outputDefault),
                                outputDefault, Math.min(60L, limits.maxTimeoutSeconds()));
                        return new ChatConfigurationOptions.ModelOption(binding.remoteOperation(), binding.definition(),
                                binding.capability().definition(), window, limits, defaults, budgets,
                                binding.capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)
                                        && modelGateway.supportsToolChat(binding, value.connection()));
                    })
                    .filter(option -> !option.budgetRefs().isEmpty())
                            .collectList().map(ChatConfigurationOptions::new));
            }));
        });
    }
}
