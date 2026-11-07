package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.infrastructure.control.SourceAiResourceEntitlementService;
import com.arte.ainew.infrastructure.control.SourceControlReadAdapter;
import com.arte.ainew.pojo.control.*;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.testing.TestAiConfiguration;
import com.arte.ainew.testing.TestAuthorizationResolver;
import com.arte.ainew.testing.TestAuthorizations;
import com.arte.ainew.testing.TestControlCatalog;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.Assert.*;

public class AiResourceEntitlementServiceTest {
    private final TestAiConfiguration properties = AdmissionFixture.properties();
    private final TestAuthorizationResolver resolver = new TestAuthorizationResolver(properties);
    private final AdmissionAuthorization authorization = TestAuthorizations.create(resolver, properties, Clock.systemUTC());
    private static final Duration WAIT = Duration.ofSeconds(5);

    private ExecutionContext context(Set<String> scopes) {
        var auth = resolver.resolve("alice", "tenant", "workspace", scopes).block(WAIT);
        return new ExecutionContext("entitlement-execution", "trace", auth, Instant.now().plusSeconds(60),
                null, "alice-budget", properties.releaseRef(), null);
    }

    private ExecutionContext context() {
        return context(AdmissionFixture.REGULAR);
    }

    private TestAiConfiguration copy(List<ControlConfiguration.Grant> grants, List<CapabilityDescriptor> capabilities,
                                 List<ResolvedBinding> bindings, List<ConnectionDefinition> connections) {
        return new TestAiConfiguration(properties.enabled(), properties.dataSourceBean(), properties.releaseRef(), properties.persistence(),
                properties.limits(), grants, capabilities, bindings, connections, properties.rates(), properties.budgets());
    }

    private TestAiConfiguration noModels() {
        var grant = properties.grants().getFirst();
        var denied = new ControlConfiguration.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(),
                grant.workspaceId(), grant.grantRef(), true, grant.scopes(), Set.of(), grant.budgetRefs());
        return copy(List.of(denied), properties.capabilities(), properties.bindings(), properties.connections());
    }

    @Test
    public void reservedPersonalBindingsCannotUseLegacyGrantButOrdinaryPersonalNamesRemainCompatible() {
        ResolvedBinding original = properties.bindings().getFirst();
        ControlConfiguration.Grant old = properties.grants().getFirst();
        for (String id : List.of("personal-legacy-model", "resource-" + "a".repeat(64))) {
            DefinitionRef reference = new DefinitionRef("binding", id, "v1");
            ResolvedBinding binding = new ResolvedBinding(reference, original.capability(), original.target(), original.contextWindowTokens(), original.rate());
            ControlConfiguration.Grant grant = new ControlConfiguration.Grant(old.subjectName(), old.subjectId(), old.principalKind(), old.tenantId(), old.workspaceId(),
                    old.grantRef(), old.enabled(), old.scopes(), Set.of(id), old.budgetRefs());
            SourceAiResourceEntitlementService service = new SourceAiResourceEntitlementService(copy(List.of(grant), properties.capabilities(), List.of(binding), properties.connections()).source(), Schedulers.immediate(), authorization);
            if (id.equals("personal-legacy-model")) {
                assertEquals(binding, service.resolve(reference, original.capability().definition(), context()).block(WAIT).binding());
            } else {
                unavailable(service.resolve(reference, original.capability().definition(), context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
                assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 10, context()).collectList().block(WAIT).isEmpty());
            }
        }
    }

    private final class Source implements AiControlConfigurationSource {
        TestAiConfiguration current = properties;
        boolean revoked;
        final AtomicInteger reads = new AtomicInteger();

        public ControlConfiguration configuration(ExecutionContext context) {
            reads.incrementAndGet();
            if (revoked) throw new AccessDeniedException("revoked");
            return (current).snapshot();
        }

        public ControlConfiguration definitions() {
            return (properties).snapshot();
        }

        public ControlConfiguration.Grant grant(ExecutionContext context) {
            throw new AssertionError("Must use the same snapshot");
        }
    }

    private void unavailable(Mono<?> operation, ResultCodeEnum code) {
        assertEquals(code, assertThrows(AdmissionException.class, () -> operation.block(WAIT)).getResultCode());
    }

    @Test
    public void fixedDiscoveryAndResolutionHaveIdenticalBindingConnectionAndQualification() {
        var service = new SourceAiResourceEntitlementService(properties.source(), Schedulers.immediate(), authorization);
        var context = context();
        var found = service.discover(CapabilityDescriptor.Kind.GENERATION, 256, context).collectList().block(WAIT);
        assertEquals(1, found.size());
        var resolved = service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context).block(WAIT);
        assertEquals(found.getFirst(), resolved);
        assertEquals(AdmissionFixture.owner("alice-id"), resolved.executionOwner());
        assertEquals(Set.of(MODEL_USE), resolved.allowedActions());
        assertEquals(AiEntitlement.Source.DATABASE, resolved.source());
        assertEquals(resolved.connection(), service.resolveConnection(AdmissionFixture.CONNECTION, context).block(WAIT));
        assertEquals(resolved.binding().capability(), service.resolveCapability(AdmissionFixture.CAP, context).block(WAIT));
    }

    @Test
    public void unavailableModelsCannotBeFoundOrResolvedEvenWhenTheirDefinitionsArePublished() {
        var service = new SourceAiResourceEntitlementService(noModels().source(), Schedulers.immediate(), authorization);
        assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 10, context()).collectList().block(WAIT).isEmpty());
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        unavailable(service.resolveConnection(AdmissionFixture.CONNECTION, context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        unavailable(service.resolveCapability(AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
    }

    @Test
    public void disabledConnectionIsExcludedAndExplicitUseFails() {
        var old = properties.connections().getFirst();
        var disabled = new ConnectionDefinition(old.definition(), old.providerId(), old.protocol(), old.endpoint(), old.credential(),
                ConnectionDefinition.State.DISABLED, old.connectTimeout(), old.responseTimeout(), old.maxResponseBytes());
        var service = new SourceAiResourceEntitlementService(copy(properties.grants(), properties.capabilities(), properties.bindings(), List.of(disabled)).source(), Schedulers.immediate(), authorization);
        assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 256, context()).collectList().block(WAIT).isEmpty());
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CAPABILITY_DISABLED);
    }

    @Test
    public void disabledCapabilityIsExcludedAndExplicitUseFails() {
        var old = properties.capabilities().getFirst();
        var disabled = new CapabilityDescriptor(old.definition(), old.kind(), old.inputSchema(), old.outputSchema(), old.features(),
                old.sideEffect(), CapabilityDescriptor.Availability.DISABLED);
        var binding = properties.bindings().getFirst();
        var changed = new ResolvedBinding(binding.definition(), disabled, binding.target(), binding.contextWindowTokens(), binding.rate());
        var service = new SourceAiResourceEntitlementService(copy(properties.grants(), List.of(disabled), List.of(changed), properties.connections()).source(), Schedulers.immediate(), authorization);
        assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 256, context()).collectList().block(WAIT).isEmpty());
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CAPABILITY_DISABLED);
    }

    @Test
    public void fixedVersionsStayDistinctAndLimitsAndActionsAreEnforced() {
        var binding = properties.bindings().getFirst();
        var second = new ResolvedBinding(new DefinitionRef("binding", "text", "v2"), binding.capability(), binding.target(), binding.contextWindowTokens(), binding.rate());
        var service = new SourceAiResourceEntitlementService(copy(properties.grants(), properties.capabilities(), List.of(second, binding), properties.connections()).source(), Schedulers.immediate(), authorization);
        assertEquals(List.of(binding.definition(), second.definition()), service.discover(CapabilityDescriptor.Kind.GENERATION, 256, context())
                .map(value -> value.binding().definition()).collectList().block(WAIT));
        assertEquals(1, service.discover(CapabilityDescriptor.Kind.GENERATION, 1, context()).collectList().block(WAIT).size());
        assertThrows(IllegalArgumentException.class, () -> service.discover(CapabilityDescriptor.Kind.GENERATION, 257, context()).collectList().block(WAIT));
        unavailable(service.resolve(binding.definition(), binding.capability().definition(), new Action("model:edit"), context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        unavailable(service.resolve(new DefinitionRef("binding", "text", "v3"), AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
    }

    @Test
    public void sourceReadsAreCurrentAndCannotBorrowFixedAuthorityAfterRevocation() {
        var source = new Source();
        var service = new SourceAiResourceEntitlementService(source, Schedulers.immediate(), authorization);
        assertEquals(AiEntitlement.Source.DATABASE, service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()).block(WAIT).source());
        source.current = noModels();
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        source.revoked = true;
        assertThrows(AccessDeniedException.class, () -> service.discover(CapabilityDescriptor.Kind.GENERATION, 256, context()).collectList().block(WAIT));
        assertEquals(3, source.reads.get());
    }

    @Test
    public void qualificationSharesOneReadSnapshotAndRefreshesOnTheNextOperation() {
        var source = new Source();
        var service = new SourceAiResourceEntitlementService(source, Schedulers.immediate(), authorization);
        var published = new SourceControlReadAdapter(source, Schedulers.immediate(), authorization);
        var context = context();
        var value = published.inSnapshot(context, ignored -> {
            source.current = noModels();
            return service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context);
        }).block(WAIT);
        assertEquals(AdmissionFixture.BINDING, value.binding().definition());
        assertEquals(1, source.reads.get());
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context), ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
        assertEquals(2, source.reads.get());
    }

    @Test
    public void expiredAndReadOnlyContextsCannotAcquireModelUse() {
        var service = new SourceAiResourceEntitlementService(properties.source(), Schedulers.immediate(), authorization);
        assertThrows(AccessDeniedException.class, () -> service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP,
                context(Set.of(AdmissionAuthorization.READ))).block(WAIT));
        var original = context();
        var expired = new ExecutionContext(original.executionId(), original.traceId(), original.authorization(), Instant.now().minusSeconds(1),
                original.parentExecutionId(), original.budgetRef(), original.releaseRef(), original.idempotencyKey());
        unavailable(service.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, expired), ResultCodeEnum.AI_DEADLINE_EXCEEDED);
    }

    @Test
    public void injectedProviderControlsBothDiscoveryAndTheExecutionFacade() {
        AiResourceEntitlementService denied = new AiResourceEntitlementService() {
            public Flux<AiEntitlement> discover(CapabilityDescriptor.Kind kind, int limit, ExecutionContext context) {
                return Flux.empty();
            }

            public Mono<AiEntitlement> resolve(DefinitionRef binding, DefinitionRef capability, Action action, ExecutionContext context) {
                return Mono.error(new AccessDeniedException("custom model policy"));
            }
        };
        var facade = new TestControlCatalog(properties, authorization, Clock.systemUTC(), null, Schedulers.immediate(), denied);
        var reads = new SourceControlReadAdapter(properties.source(), Schedulers.immediate(), authorization);
        var discovery = new ChatConfigurationQueryService(properties.deployment(), authorization, denied, reads, reads, () -> new ModelGateway() {
            public boolean supportsTextChat(ResolvedBinding binding, ConnectionDefinition connection) {
                return true;
            }

            public Flux<com.arte.ainew.pojo.generation.GenerationSignal> generate(
                    com.arte.ainew.pojo.execution.GatewayCall<com.arte.ainew.pojo.generation.GenerationRequest> call) {
                return Flux.error(new AssertionError("Discovery must not invoke a provider"));
            }
        }, facade.policyProvider());
        assertTrue(discovery.discover(context()).block(WAIT).options().isEmpty());
        assertThrows(AccessDeniedException.class, () -> facade.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context()).block(WAIT));
        assertTrue(facade.discoverBindings(CapabilityDescriptor.Kind.GENERATION, context()).collectList().block(WAIT).isEmpty());
    }
}
