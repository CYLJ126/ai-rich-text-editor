package com.arte.ainew.application.control;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.*;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 兼容控制面门面：模型发现、绑定／连接／能力解析统一委托模型使用资格端口。
 * <p>
 * 保留既有配置校验、请求／限额校验和手工装配入口；资源资格不再在此解析 Grant。
 * 生产装配使用独立业务入口及 SPI；本类仅保留旧手工装配兼容路径。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Deprecated(since = "control-ports-batch-4", forRemoval = false)
public final class FixedControlCatalog implements CapabilityCatalog, BindingManager {

    public static final DefinitionRef INPUT_SCHEMA = ControlDefinitionChecks.INPUT_SCHEMA;
    public static final DefinitionRef OUTPUT_SCHEMA = ControlDefinitionChecks.OUTPUT_SCHEMA;
    private final NewAiProperties properties;
    private final AdmissionAuthorization authorization;
    private final Clock clock;
    private AiControlConfigurationSource source;
    private Scheduler scheduler;
    private ControlConfigurationReader reader;
    private AiResourceEntitlementService entitlements;
    private AiExecutionPolicyProvider policies;
    private AiAdmissionAuthorizationGuard admissionGuard;
    private ModelControlService modelControl;
    private EntitledConnectionManager connections;

    public FixedControlCatalog(NewAiProperties properties, AdmissionAuthorization authorization, Clock clock) {
        ControlDefinitionChecks.validate(properties);
        this.properties = properties;
        this.reader = new ControlConfigurationReader(properties);
        this.authorization = authorization;
        this.clock = clock;
        this.entitlements = new FixedAiResourceEntitlementService(properties, authorization);
        configureBoundaryPorts();
    }

    public FixedControlCatalog(NewAiProperties infrastructure, AdmissionAuthorization authorization, Clock clock,
                               AiControlConfigurationSource source, Scheduler scheduler) {
        this(infrastructure, authorization, clock);
        this.source = Objects.requireNonNull(source);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.reader = new ControlConfigurationReader(source, scheduler);
        this.entitlements = new SourceAiResourceEntitlementService(source, scheduler, authorization);
        configureBoundaryPorts();
    }

    /**
     * 兼容装配显式注入统一资格端口，保证自定义提供者同时作用于发现和实际执行解析。
     */
    public FixedControlCatalog(NewAiProperties properties, AdmissionAuthorization authorization, Clock clock,
                               AiControlConfigurationSource source, Scheduler scheduler, AiResourceEntitlementService entitlements) {
        this(properties, authorization, clock);
        if (source != null) {
            this.source = source;
            this.scheduler = Objects.requireNonNull(scheduler);
            this.reader = new ControlConfigurationReader(source, scheduler);
        }
        this.entitlements = Objects.requireNonNull(entitlements);
        configureBoundaryPorts();
    }

    public FixedControlCatalog(NewAiProperties properties, AdmissionAuthorization authorization, Clock clock,
                               AiControlConfigurationSource source, Scheduler scheduler, AiResourceEntitlementService entitlements,
                               AiExecutionPolicyProvider policies, AiAdmissionAuthorizationGuard admissionGuard) {
        this(properties, authorization, clock, source, scheduler, entitlements);
        this.policies = Objects.requireNonNull(policies);
        this.admissionGuard = Objects.requireNonNull(admissionGuard);
        configureModelControl();
    }

    private void configureBoundaryPorts() {
        policies = new ConfigurationExecutionPolicyProvider(reader, properties.limits());
        admissionGuard = new ConfigurationAiAdmissionAuthorizationGuard(reader, authorization, entitlements, readAdapter(), policies, properties.limits(), clock);
        configureModelControl();
    }

    private void configureModelControl() {
        connections = new EntitledConnectionManager(entitlements, authorization, admissionGuard);
        modelControl = new ModelControlService(readAdapter(), entitlements, readAdapter(), policies, ExecutionPolicies.from(properties.limits()), clock);
    }

    public AiExecutionPolicyProvider policyProvider() {
        return policies;
    }

    public AiAdmissionAuthorizationGuard admissionGuard() {
        return admissionGuard;
    }

    public Mono<NewAiProperties> configuration(ExecutionContext context) {
        return reader.read(context);
    }

    /**
     * 手工装配旧调用方的迁移桥；生产服务通过独立 SPI 注入。
     */
    public ControlReadAdapter readAdapter() {
        return source == null ? new FixedControlReadAdapter(properties, authorization)
                : new SourceControlReadAdapter(source, scheduler, authorization);
    }

    public AiResourceEntitlementService entitlementAdapter() {
        return entitlements;
    }

    public List<NewAiProperties.Rate> rates() {
        return source == null ? properties.rates() : source.definitions().rates();
    }

    /**
     * 旧快照手工装配兼容入口，生产发现及解析不再创建临时固定授权解析器。
     */
    public FixedControlCatalog fromSnapshot(NewAiProperties value) {
        var snapshotAuthorization = new AdmissionAuthorization(
                new com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver(value), value, clock);
        return new FixedControlCatalog(value, snapshotAuthorization, clock);
    }

    public Mono<NewAiProperties.Budget> budget(String ref, ExecutionContext context) {
        return configuration(context).map(value -> value.budgets().stream().filter(b -> b.budgetRef().equals(ref)).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE)));
    }

    public NewAiProperties.Budget budget(String ref) {
        return properties.budgets().stream().filter(b -> b.budgetRef().equals(ref)).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
    }

    @Override
    public Mono<ResolvedBinding> resolve(DefinitionRef binding, DefinitionRef capability, ExecutionContext context) {
        return modelControl.resolve(binding, capability, context);
    }

    public Mono<ConnectionDefinition> resolveConnection(DefinitionRef reference, ExecutionContext context) {
        return connections.resolve(reference, context);
    }

    /**
     * 发送边界不复用发现时的允许结论。
     */
    public Mono<ConnectionDefinition> resolveCurrentConnection(DefinitionRef reference, ExecutionContext context) {
        return connections.resolveCurrent(reference, context);
    }

    public Mono<ConnectionDefinition> resolveCurrentConnection(DefinitionRef reference,
                                                               com.arte.ainew.pojo.execution.GatewayCall<?> call) {
        return connections.resolveCurrent(reference, call);
    }

    @Override
    public Mono<CapabilityDescriptor> resolve(DefinitionRef reference, ExecutionContext context) {
        return modelControl.resolve(reference, context);
    }

    public Flux<ResolvedBinding> discoverBindings(CapabilityDescriptor.Kind kind, ExecutionContext context) {
        return entitlements.discover(kind, 256, context).map(AiEntitlement::binding);
    }

    @Override
    public Flux<CapabilityDescriptor> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
        return modelControl.discover(kind, limit, context);
    }

    @Override
    public Mono<Void> validate(InvocationRequest<?> request) {
        return modelControl.validate(request);
    }
}
