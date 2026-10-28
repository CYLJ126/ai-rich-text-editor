package com.arte.ainew.application.control;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.*;
import java.util.function.Function;

/**
 * 首批固定文本能力控制面
 * <p>
 * 从受信固定版本配置中完成解析和校验；数据库模式通过来源 SPI 读取当前范围配置，不缓存授权，不执行远端探测。
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
    private AiControlConfigurationSource source;
    private Scheduler scheduler;

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
                    CapabilityDescriptor.Feature.USAGE_REPORTING, CapabilityDescriptor.Feature.TOOL_CALLS).containsAll(capability.features())
                    && capability.features().contains(CapabilityDescriptor.Feature.TEXT_INPUT), "Unsupported advertised capability features");
        }
        for (var binding : bindings.values()) {
            ContractChecks.require(binding.target() instanceof com.arte.ainew.pojo.control.ExecutionTarget.Remote,
                    "Fixed generation catalog requires remote bindings");
            ContractChecks.require(binding.capability().equals(capabilities.get(binding.capability().definition()))
                    && connections.containsKey(binding.connection()) && binding.rate() != null && rates.containsKey(binding.rate()) && rates.get(binding.rate()).toolPerCall() == null, "Unresolved binding dependency");
        }
        for (var budget : budgets.values()) {
            ContractChecks.require(rates.containsKey(budget.rate())
                    && rates.get(budget.rate()).currency().equals(budget.limit().currency()), "Unresolved budget rate/currency");
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

    /**
     * 数据库模式使用同一校验算法；本次准备内复用一致快照，新的业务边界重新读取当前配置。
     */
    public FixedControlCatalog(NewAiProperties infrastructure, AdmissionAuthorization authorization, Clock clock,
                               AiControlConfigurationSource source, reactor.core.scheduler.Scheduler scheduler) {
        this(infrastructure, authorization, clock);
        this.source = Objects.requireNonNull(source);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public Mono<NewAiProperties> configuration(ExecutionContext context) {
        return Mono.deferContextual(values -> {
            var preparation = com.arte.ainew.context.ControlPreparation.find(values, context);
            if (preparation.isPresent()) return Mono.just(preparation.get());
            var snapshot = com.arte.ainew.context.ReadOperationSnapshot.find(values, context);
            if (snapshot.isPresent()) {
                var configuration = snapshot.get().configuration();
                if (configuration == null)
                    return Mono.error(new IllegalStateException("Read operation did not request control configuration"));
                return Mono.just(configuration);
            }
            return source == null ? Mono.just(properties)
                    : Mono.fromCallable(() -> source.configuration(context)).subscribeOn(scheduler);
        });
    }

    public java.util.List<NewAiProperties.Rate> rates() {
        return source == null ? properties.rates() : source.definitions().rates();
    }

    public FixedControlCatalog fromSnapshot(NewAiProperties value) {
        var snapshotAuthorization = new AdmissionAuthorization(
                new com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver(value), value, clock);
        return new FixedControlCatalog(value, snapshotAuthorization, clock);
    }

    private Mono<FixedControlCatalog> current(ExecutionContext context) {
        return configuration(context).map(this::fromSnapshot);
    }

    public Mono<NewAiProperties.Budget> budget(String ref, ExecutionContext context) {
        return current(context).map(catalog -> catalog.budget(ref));
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
        if (source != null) return current(context).flatMap(catalog -> catalog.resolve(binding, capability, context));
        return authorization.require(context, AdmissionAuthorization.INVOKE).map(current -> binding(binding, capability, current));
    }

    /**
     * ConnectionManager 的返回类型与 CapabilityCatalog.resolve 相同签名冲突，使用独立门面注册。
     */
    public Mono<ConnectionDefinition> resolveConnection(DefinitionRef reference, ExecutionContext context) {
        if (source != null) return current(context).flatMap(catalog -> catalog.resolveConnection(reference, context));
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

    /**
     * Sending an external request must not reuse the earlier preparation's allow decision.
     */
    public Mono<ConnectionDefinition> resolveCurrentConnection(DefinitionRef reference, ExecutionContext context) {
        if (source == null) return resolveConnection(reference, context);
        return authorization.revalidate(context, current -> resolveConnection(reference, current));
    }

    public Mono<ConnectionDefinition> resolveCurrentConnection(DefinitionRef reference,
                                                               com.arte.ainew.pojo.execution.GatewayCall<?> call) {
        return authorization.revalidate(call.runtime().execution(), current -> {
            var original = call.request();
            var request = new InvocationRequest<>(original.capability(), original.binding(), original.kind(),
                    original.input(), original.options(), current);
            return resolve(request.binding(), request.capability(), current).flatMap(binding -> {
                if (!binding.equals(call.binding()) || !binding.connection().equals(reference))
                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
                return validate(request).then(resolveConnection(reference, current));
            });
        });
    }

    @Override
    public Mono<CapabilityDescriptor> resolve(DefinitionRef reference, ExecutionContext context) {
        if (source != null) return current(context).flatMap(catalog -> catalog.resolve(reference, context));
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
        if (source != null) return current(context).flatMapMany(catalog -> catalog.discoverBindings(kind, context));
        Objects.requireNonNull(kind, "kind");
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMapMany(current ->
                Flux.fromIterable(availableBindings(current).stream().filter(b -> b.capability().kind() == kind)
                        .sorted(Comparator.comparing((ResolvedBinding b) -> b.definition().id())
                                .thenComparing(b -> b.definition().version())).toList()));
    }

    @Override
    public Flux<CapabilityDescriptor> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        if (source != null) return current(context).flatMapMany(catalog -> catalog.discover(kind, limit, context));
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMapMany(current -> {
            ContractChecks.range(limit, "limit", 1, 256);
            return Flux.fromIterable(available(current).stream().filter(c -> c.kind() == Objects.requireNonNull(kind)).limit(limit).toList());
        });
    }

    @Override
    public Mono<Void> validate(InvocationRequest<?> request) {
        if (source != null) return current(request.context()).flatMap(catalog -> catalog.validate(request));
        return resolve(request.binding(), request.capability(), request.context()).flatMap(binding -> Mono.fromRunnable(() -> {
            if (!(request.input() instanceof GenerationRequest generation) || request.kind() != CapabilityDescriptor.Kind.GENERATION
                    || !(generation.outputFormat() instanceof GenerationRequest.TextOutput)
                    || !generation.tools().isEmpty() && !binding.capability().features().contains(CapabilityDescriptor.Feature.TOOL_CALLS)) {
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
            if (generation.tools().isEmpty() && (options.maxToolSteps() != 0 || options.maxConcurrentTools() != 0)
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
