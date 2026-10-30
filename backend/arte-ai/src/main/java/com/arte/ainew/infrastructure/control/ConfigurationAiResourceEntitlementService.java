package com.arte.ainew.infrastructure.control;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;

/**
 * 两种配置来源共用模型资格规则
 * <p>
 * 现阶段适配既有 Grant，不虚构尚未落地的公共模型 ACL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 13:00 ✾
 */
public abstract class ConfigurationAiResourceEntitlementService implements AiResourceEntitlementService {

    private final ControlConfigurationReader reader;
    private final AdmissionAuthorization authorization;
    private final AiEntitlement.Source source;

    protected ConfigurationAiResourceEntitlementService(ControlConfigurationReader reader, AdmissionAuthorization authorization,
                                                        AiEntitlement.Source source) {
        this.reader = Objects.requireNonNull(reader);
        this.authorization = Objects.requireNonNull(authorization);
        this.source = Objects.requireNonNull(source);
    }

    private <T> Mono<T> inSnapshot(ExecutionContext context, Function<NewAiProperties, Mono<T>> work) {
        // FIXED 继续通过现有解析器核验当前身份；DATABASE 来源读取本身已重检公共资格。
        Mono<ExecutionContext> checked = source == AiEntitlement.Source.FIXED
                ? authorization.require(context, AdmissionAuthorization.INVOKE) : Mono.just(context);
        return checked.flatMap(current -> reader.inSnapshot(current, configuration -> {
            authorization.requireConfigurationScope(configuration, current, AdmissionAuthorization.INVOKE);
            ControlDefinitionChecks.validate(configuration);
            return work.apply(configuration);
        }));
    }

    private AiEntitlement entitlement(NewAiProperties configuration, ResolvedBinding binding, ExecutionContext context) {
        var connection = configuration.connections().stream().filter(c -> c.definition().equals(binding.connection())).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        if (binding.capability().availability() != CapabilityDescriptor.Availability.EXECUTABLE
                || connection.state() != ConnectionDefinition.State.ENABLED)
            throw new AdmissionException(ResultCodeEnum.AI_CAPABILITY_DISABLED);
        return new AiEntitlement(ExecutionOwners.from(context), binding, connection, configuration.releaseRef(),
                authorization.grant(configuration, context).grantRef(), source, Set.of(MODEL_USE));
    }

    @Override
    public Mono<AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability, Action action, ExecutionContext context) {
        return Mono.defer(() -> {
            binding.requireType("binding");
            capability.requireType("capability");
            if (!MODEL_USE.equals(action))
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
            return inSnapshot(context, configuration -> {
                var grant = authorization.grant(configuration, context);
                var found = configuration.bindings().stream().filter(value -> value.definition().equals(binding)
                                && value.capability().definition().equals(capability) && grant.bindingIds().contains(binding.id())).findFirst()
                        .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
                return Mono.just(entitlement(configuration, found, context));
            });
        });
    }

    @Override
    public Flux<AiEntitlement> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        return Flux.defer(() -> {
            Objects.requireNonNull(kind, "kind");
            ContractChecks.range(limit, "limit", 1, 256);
            return inSnapshot(context, configuration -> {
                var ids = authorization.grant(configuration, context).bindingIds();
                return Flux.fromIterable(configuration.bindings()).filter(binding -> binding.capability().kind() == kind
                                && ids.contains(binding.definition().id())
                                && binding.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE
                                && configuration.connections().stream().anyMatch(c -> c.definition().equals(binding.connection())
                                && c.state() == ConnectionDefinition.State.ENABLED))
                        .sort(Comparator.comparing((ResolvedBinding binding) -> binding.definition().id()).thenComparing(binding -> binding.definition().version()))
                        .take(limit).map(binding -> entitlement(configuration, binding, context)).collectList();
            }).flatMapMany(Flux::fromIterable);
        });
    }
}
