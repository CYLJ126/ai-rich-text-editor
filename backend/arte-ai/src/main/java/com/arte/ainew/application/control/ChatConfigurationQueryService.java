package com.arte.ainew.application.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ChatConfigurationOptions;
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
    private final FixedControlCatalog catalog;
    private final Supplier<ModelGateway> gateway;

    public ChatConfigurationQueryService(NewAiProperties properties, AdmissionAuthorization authorization,
                                         FixedControlCatalog catalog, Supplier<ModelGateway> gateway) {
        this.properties = properties;
        this.authorization = authorization;
        this.catalog = catalog;
        this.gateway = gateway;
    }

    public Mono<ChatConfigurationOptions> discover(ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current -> {
            var modelGateway = gateway.get();
            if (modelGateway == null) {
                return Mono.just(new ChatConfigurationOptions(List.of()));
            }
            var grant = authorization.grant(current);
            var owner = ExecutionOwner.from(current);
            return catalog.discoverBindings(CapabilityDescriptor.Kind.GENERATION, current)
                    .filter(binding -> binding.contextWindowTokens() >= 2
                            && binding.capability().features().contains(CapabilityDescriptor.Feature.TEXT_INPUT)
                            && binding.capability().features().contains(CapabilityDescriptor.Feature.STREAMING))
                    .filter(binding -> properties.connections().stream().anyMatch(connection ->
                            connection.definition().equals(binding.connection()) && modelGateway.supportsTextChat(binding, connection)))
                    .map(binding -> {
                        var budgets = properties.budgets().stream()
                                .filter(budget -> grant.budgetRefs().contains(budget.budgetRef()) && budget.owner().equals(owner)
                                        && budget.rate().equals(binding.rate()))
                                .map(NewAiProperties.Budget::budgetRef).sorted().toList();
                        long window = binding.contextWindowTokens();
                        int outputLimit = (int) Math.min(properties.limits().maxOutputTokens(), Math.min(1_000_000L, window - 1));
                        var limits = new ChatConfigurationOptions.Limits(window - 1, outputLimit, properties.limits().maxInputBytes(),
                                properties.limits().maxOutputBytes(), properties.limits().maximumTimeout().toSeconds());
                        int outputDefault = Math.min(512, outputLimit);
                        var defaults = new ChatConfigurationOptions.Defaults(Math.min(32_768L, window - outputDefault),
                                outputDefault, Math.min(60L, limits.maxTimeoutSeconds()));
                        return new ChatConfigurationOptions.ModelOption(binding.remoteOperation(), binding.definition(),
                                binding.capability().definition(), window, limits, defaults, budgets);
                    })
                    .filter(option -> !option.budgetRefs().isEmpty())
                    .collectList().map(ChatConfigurationOptions::new);
        });
    }
}
