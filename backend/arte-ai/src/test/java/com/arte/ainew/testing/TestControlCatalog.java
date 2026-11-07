package com.arte.ainew.testing;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.EntitledConnectionManager;
import com.arte.ainew.application.control.ModelControlService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.infrastructure.control.*;
import com.arte.ainew.pojo.control.*;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.control.AiExecutionPolicyProvider;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;

/**
 * 测试装配辅助，业务服务只接收各自的显式端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 11:25 ✾
 */
public final class TestControlCatalog implements CapabilityCatalog, BindingManager {

    public static final DefinitionRef INPUT_SCHEMA = ControlDefinitionChecks.INPUT_SCHEMA;
    public static final DefinitionRef OUTPUT_SCHEMA = ControlDefinitionChecks.OUTPUT_SCHEMA;
    private final AiControlConfigurationSource source;
    private final ControlConfigurationReader reader;
    private final ControlReadAdapter reads;
    private final AiResourceEntitlementService entitlements;
    private final AiExecutionPolicyProvider policies;
    private final AiAdmissionAuthorizationGuard guard;
    private final ModelControlService modelControl;
    private final EntitledConnectionManager connections;

    public TestControlCatalog(TestAiConfiguration data, AdmissionAuthorization authorization, Clock clock) {
        this(data, authorization, clock, data.source(), Schedulers.immediate());
    }

    public TestControlCatalog(TestAiConfiguration data, AdmissionAuthorization authorization, Clock clock,
                              AiControlConfigurationSource source, Scheduler scheduler) {
        this(data, authorization, clock, source, scheduler, null);
    }

    public TestControlCatalog(TestAiConfiguration data, AdmissionAuthorization authorization, Clock clock,
                              AiControlConfigurationSource source, Scheduler scheduler, AiResourceEntitlementService entitlements) {
        this(data, authorization, clock, source, scheduler, entitlements, null, null);
    }

    public TestControlCatalog(TestAiConfiguration data, AdmissionAuthorization authorization, Clock clock,
                              AiControlConfigurationSource source, Scheduler scheduler, AiResourceEntitlementService entitlements,
                              AiExecutionPolicyProvider policies, AiAdmissionAuthorizationGuard guard) {
        this.source = source == null ? data.source() : source;
        Scheduler io = scheduler == null ? Schedulers.immediate() : scheduler;
        reader = new ControlConfigurationReader(this.source, io);
        reads = new SourceControlReadAdapter(this.source, io, authorization);
        this.entitlements = entitlements == null ? new SourceAiResourceEntitlementService(this.source, io, authorization) : entitlements;
        this.policies = policies == null ? new ConfigurationExecutionPolicyProvider(reader, data.limits()) : policies;
        this.guard = guard == null ? new ConfigurationAiAdmissionAuthorizationGuard(reader, authorization, this.entitlements, reads, this.policies, data.limits(), clock) : guard;
        modelControl = new ModelControlService(reads, this.entitlements, reads, this.policies, ExecutionPolicies.from(data.limits()), clock);
        connections = new EntitledConnectionManager(this.entitlements, authorization, this.guard);
    }

    public ControlReadAdapter readAdapter() {
        return reads;
    }

    public AiResourceEntitlementService entitlementAdapter() {
        return entitlements;
    }

    public AiExecutionPolicyProvider policyProvider() {
        return policies;
    }

    public AiAdmissionAuthorizationGuard admissionGuard() {
        return guard;
    }

    public Mono<ControlConfiguration> configuration(ExecutionContext context) {
        return reader.read(context);
    }

    public List<ControlConfiguration.Rate> rates() {
        return source.definitions().rates();
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
