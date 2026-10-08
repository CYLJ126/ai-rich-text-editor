package com.arte.ainew.application.control;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/**
 * 首批固定文本能力控制面
 * <p>
 * 从服务器固定配置（配置文件）中完成解析和校验。仅解析受信固定版本，不提供管理 CRUD 或远端探测。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class FixedControlCatalog implements CapabilityCatalog, BindingManager {
    public static final DefinitionRef INPUT_SCHEMA = new DefinitionRef("schema", "generation-request", "v1");
    public static final DefinitionRef OUTPUT_SCHEMA = new DefinitionRef("schema", "model-result", "v1");
    private final NewAiProperties properties;
    private final AdmissionAuthorization authorization;
    private final Clock clock;
    private final Map<DefinitionRef, CapabilityDescriptor> capabilities;
    private final Map<DefinitionRef, ResolvedBinding> bindings;
    private final Map<DefinitionRef, ConnectionDefinition> connections;
    private final Map<DefinitionRef, NewAiProperties.Rate> rates;
    private final Map<String, NewAiProperties.Budget> budgets;

    public FixedControlCatalog(NewAiProperties properties, AdmissionAuthorization authorization, Clock clock) {
        this.properties = properties;
        this.authorization = authorization;
        this.clock = clock;
        capabilities = index(properties.capabilities(), CapabilityDescriptor::definition);
        bindings = index(properties.bindings(), ResolvedBinding::definition);
        connections = index(properties.connections(), ConnectionDefinition::definition);
        rates = index(properties.rates(), NewAiProperties.Rate::definition);
        budgets = index(properties.budgets(), NewAiProperties.Budget::budgetRef);
        for (var capability : capabilities.values()) {
            ContractChecks.require(capability.kind() == CapabilityDescriptor.Kind.GENERATION
                            && INPUT_SCHEMA.equals(capability.inputSchema()) && OUTPUT_SCHEMA.equals(capability.outputSchema()),
                    "Only registered text generation schemas are supported");
            ContractChecks.require(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING,
                    CapabilityDescriptor.Feature.USAGE_REPORTING).containsAll(capability.features())
                    && capability.features().contains(CapabilityDescriptor.Feature.TEXT_INPUT), "Unsupported advertised capability features");
        }
        for (var binding : bindings.values()) {
            ContractChecks.require(binding.capability().equals(capabilities.get(binding.capability().definition()))
                    && connections.containsKey(binding.connection()) && binding.rate() != null && rates.containsKey(binding.rate()), "Unresolved binding dependency");
        }
        for (var budget : budgets.values()) {
            ContractChecks.require(rates.containsKey(budget.rate())
                    && rates.get(budget.rate()).inputPerMillion().currency().equals(budget.limit().currency()), "Unresolved budget rate/currency");
        }
        for (var grant : properties.grants()) {
            ContractChecks.require(new HashSet<>(bindings.keySet().stream().map(DefinitionRef::id).toList()).containsAll(grant.bindingIds())
                    && budgets.keySet().containsAll(grant.budgetRefs()), "Grant refers to unregistered configuration");
            for (var budgetRef : grant.budgetRefs()) {
                ContractChecks.require(budgets.get(budgetRef).owner().equals(new ExecutionOwner(grant.tenantId(), grant.workspaceId(), grant.subjectId())),
                        "Grant budget owner mismatch");
            }
        }
    }

    private static <K, V> Map<K, V> index(List<V> values, Function<V, K> key) {
        var result = new HashMap<K, V>();
        for (var value : values) {
            ContractChecks.require(result.put(key.apply(value), value) == null, "Duplicate configuration definition");
        }
        return Map.copyOf(result);
    }

    private ResolvedBinding binding(DefinitionRef reference, DefinitionRef capability, ExecutionContext context) {
        reference.requireType("binding");
        capability.requireType("capability");
        var value = bindings.get(reference);
        if (value == null || !value.capability().definition().equals(capability)
                || !authorization.grant(context).bindingIds().contains(reference.id())) {
            throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        }
        if (value.capability().availability() != CapabilityDescriptor.Availability.EXECUTABLE
                || connections.get(value.connection()).state() != ConnectionDefinition.State.ENABLED) {
            throw new AdmissionException(ResultCodeEnum.AI_CAPABILITY_DISABLED);
        }
        return value;
    }

    @Override
    public Mono<ResolvedBinding> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).map(current -> binding(binding, capability, current));
    }

    /**
     * ConnectionManager 的返回类型与 CapabilityCatalog.resolve 相同签名冲突，使用独立门面注册。
     */
    public Mono<ConnectionDefinition> resolveConnection(DefinitionRef reference, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).map(current -> {
            reference.requireType("connection");
            var allowed = bindings.values().stream().anyMatch(b -> b.connection().equals(reference)
                    && authorization.grant(current).bindingIds().contains(b.definition().id())
                    && b.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE);
            var value = connections.get(reference);
            if (!allowed || value == null || value.state() != ConnectionDefinition.State.ENABLED) {
                throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
            }
            return value;
        });
    }

    @Override
    public Mono<CapabilityDescriptor> resolve(DefinitionRef reference, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).map(current -> {
            reference.requireType("capability");
            return available(current).stream().filter(c -> c.definition().equals(reference)).findFirst()
                    .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
        });
    }

    private List<CapabilityDescriptor> available(ExecutionContext context) {
        return availableBindings(context).stream().map(ResolvedBinding::capability).distinct().toList();
    }

    private List<ResolvedBinding> availableBindings(ExecutionContext context) {
        var ids = authorization.grant(context).bindingIds();
        return properties.bindings().stream().filter(b -> ids.contains(b.definition().id())
                        && b.capability().availability() == CapabilityDescriptor.Availability.EXECUTABLE
                        && connections.get(b.connection()).state() == ConnectionDefinition.State.ENABLED)
                .toList();
    }

    /**
     * 有界固定目录中的当前可见绑定；同一能力的不同绑定和版本分别保留。
     */
    public Flux<ResolvedBinding> discoverBindings(CapabilityDescriptor.Kind kind, ExecutionContext context) {
        Objects.requireNonNull(kind, "kind");
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMapMany(current ->
                Flux.fromIterable(availableBindings(current).stream().filter(b -> b.capability().kind() == kind)
                        .sorted(Comparator.comparing((ResolvedBinding b) -> b.definition().id())
                                .thenComparing(b -> b.definition().version())).toList()));
    }

    @Override
    public Flux<CapabilityDescriptor> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMapMany(current -> {
            ContractChecks.range(limit, "limit", 1, 256);
            return Flux.fromIterable(available(current).stream().filter(c -> c.kind() == Objects.requireNonNull(kind)).limit(limit).toList());
        });
    }

    @Override
    public Mono<Void> validate(InvocationRequest<?> request) {
        return resolve(request.binding(), request.capability(), request.context()).flatMap(binding -> Mono.fromRunnable(() -> {
            if (!(request.input() instanceof GenerationRequest generation) || request.kind() != CapabilityDescriptor.Kind.GENERATION
                    || !(generation.outputFormat() instanceof GenerationRequest.TextOutput) || !generation.tools().isEmpty()) {
                throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
            }
            var current = request.context();
            if (!properties.releaseRef().equals(current.releaseRef()) || current.budgetRef() == null
                    || !authorization.grant(current).budgetRefs().contains(current.budgetRef())) {
                throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
            }
            var budget = budgets.get(current.budgetRef());
            if (!budget.rate().equals(binding.rate())) {
                throw new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH);
            }
            var options = request.options();
            if (options.maxToolSteps() != 0 || options.maxConcurrentTools() != 0
                    || options.maxOutputBytes() > properties.limits().maxOutputBytes()
                    || generation.options().maxOutputTokens() > properties.limits().maxOutputTokens()) {
                throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
            }
            var maximum = options.requestedTimeout() == null ? properties.limits().maximumTimeout() : options.requestedTimeout();
            if (maximum.compareTo(properties.limits().maximumTimeout()) > 0
                    || options.deadline().isAfter(clock.instant().plus(maximum))) {
                throw new AdmissionException(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED);
            }
        }));
    }

    public NewAiProperties.Budget budget(String ref) {
        var value = budgets.get(ref);
        if (value == null) {
            throw new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        }
        return value;
    }
}
