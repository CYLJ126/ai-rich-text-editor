package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ControlPreparationTest {
    private final Source source = new Source();
    private final Clock clock = Clock.systemUTC();
    private final AdmissionAuthorization authorization = new AdmissionAuthorization(source, source.properties, clock,
            source, Schedulers.boundedElastic());
    private final FixedControlCatalog catalog = new FixedControlCatalog(source.properties, authorization, clock,
            source, Schedulers.boundedElastic());
    private final ExecutionContextFactory factory = new ExecutionContextFactory(source, clock);

    private static final class Source implements ExecutionPreparationAuthorizationResolver,
            ExecutionReadAuthorizationResolver, AiControlConfigurationSource {
        final NewAiProperties properties = AdmissionFixture.properties();
        volatile NewAiProperties configuration = properties;
        final FixedExecutionAuthorizationResolver fixed = new FixedExecutionAuthorizationResolver(properties);
        final AtomicInteger preparations = new AtomicInteger(), configurations = new AtomicInteger(), writes = new AtomicInteger();
        volatile boolean revoked;

        public Mono<ExecutionAuthorization> resolve(String name, String tenant, String workspace, Set<String> scopes) {
            return Mono.defer(() -> {
                writes.incrementAndGet();
                return checked(name, tenant, workspace, scopes);
            });
        }

        private Mono<ExecutionAuthorization> checked(String name, String tenant, String workspace, Set<String> scopes) {
            return revoked ? Mono.error(new AccessDeniedException("revoked")) : fixed.resolve(name, tenant, workspace, scopes);
        }

        public Mono<Preparation> prepare(String name, String tenant, String workspace, Set<String> scopes) {
            return Mono.defer(() -> {
                preparations.incrementAndGet();
                return checked(name, tenant, workspace, scopes).map(auth -> new Preparation(auth, configuration));
            });
        }

        public Mono<Snapshot> readSnapshot(String name, String tenant, String workspace, Set<String> scopes, boolean configuration) {
            return checked(name, tenant, workspace, scopes).map(auth -> new Snapshot(auth, configuration ? properties : null));
        }

        public NewAiProperties configuration(ExecutionContext context) {
            configurations.incrementAndGet();
            var auth = context.authorization();
            checked(auth.principal().subjectName(), auth.tenantId(), auth.workspaceId(), auth.scopes()).block();
            return configuration;
        }

        public NewAiProperties definitions() {
            return properties;
        }

        public NewAiProperties.Grant grant(ExecutionContext context) {
            throw new AssertionError("Must reuse prepared configuration");
        }
    }

    @After
    public void clear() {
        SecurityContextHolder.clearContext();
    }

    private ExecutionContextRequest request() {
        return new ExecutionContextRequest("tenant", "workspace", AdmissionFixture.REGULAR, Duration.ofSeconds(10),
                null, "alice-budget", "release-v1", "prepare-test");
    }

    private void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(name, null, java.util.List.of()));
    }

    @Test
    public void onePreparationIsSharedAcrossThreadsAndNestedServicesButNotSubscriptions() {
        login("alice");
        var saved = new AtomicReference<ExecutionContext>();
        var operation = factory.prepareCurrent(request(), context -> {
            saved.set(context);
            return authorization.prepare(context, AdmissionFixture.REGULAR, false, current ->
                    catalog.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, current)
                            .publishOn(Schedulers.parallel())
                            .then(authorization.configurationGrant(current))
                            .then(catalog.resolveConnection(AdmissionFixture.CONNECTION, current)));
        });
        login("bob"); // MVC identity is captured before asynchronous subscription.
        operation.block(Duration.ofSeconds(5));
        assertEquals("alice", saved.get().authorization().principal().subjectName());
        assertEquals(1, source.preparations.get());
        assertEquals(0, source.configurations.get());
        assertEquals(0, source.writes.get());
        operation.block(Duration.ofSeconds(5));
        assertEquals(2, source.preparations.get());
        source.revoked = true;
        assertThrows(AccessDeniedException.class, () -> operation.block(Duration.ofSeconds(5)));
        assertThrows(AccessDeniedException.class, () -> catalog.resolveConnection(AdmissionFixture.CONNECTION, saved.get())
                .block(Duration.ofSeconds(5)));
    }

    @Test
    public void finalAdmissionAndSendAndRequireWriteBypassEarlierPreparation() {
        login("alice");
        factory.prepareCurrent(request(), context -> {
            source.revoked = true;
            return authorization.prepare(context, AdmissionFixture.REGULAR, true, Mono::just);
        }).as(value -> assertDenied(value));
        assertEquals(1, source.configurations.get());
        source.revoked = false;
        factory.prepareCurrent(request(), context -> {
            source.revoked = true;
            return catalog.resolveCurrentConnection(AdmissionFixture.CONNECTION, context);
        }).as(value -> assertDenied(value));
        assertEquals(2, source.configurations.get());
        source.revoked = false;
        factory.prepareCurrent(request(), context -> {
            source.revoked = true;
            return authorization.requireWrite(context, AdmissionAuthorization.INVOKE);
        }).as(value -> assertDenied(value));
        assertEquals(1, source.writes.get());
    }

    private <T> Void assertDenied(Mono<T> value) {
        assertThrows(AccessDeniedException.class, () -> value.block(Duration.ofSeconds(5)));
        return null;
    }

    @Test
    public void otherExecutionsAndReadSnapshotsCannotPrepareWrites() {
        login("alice");
        assertThrows(AccessDeniedException.class, () -> factory.prepareCurrent(request(), context ->
                        catalog.configuration(context.child("other", context.deadline(), context.authorization().scopes(), null)))
                .block(Duration.ofSeconds(5)));
        assertThrows(IllegalStateException.class, () -> factory.readCurrent(request(), true, context ->
                        authorization.prepare(context, Set.of(AdmissionAuthorization.INVOKE), false, Mono::just))
                .block(Duration.ofSeconds(5)));
        assertEquals(0, source.configurations.get());
    }

    @Test
    public void finalSendRejectsChangedBindingEvenWhenTheConnectionIsStillAllowed() {
        login("alice");
        assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> factory.prepareCurrent(request(), context -> {
            var binding = source.properties.bindings().getFirst();
            var changed = new com.arte.ainew.pojo.control.ResolvedBinding(binding.definition(), binding.capability(),
                    binding.connection(), "changed-model", binding.contextWindowTokens(), binding.rate());
            var p = source.properties;
            source.configuration = new NewAiProperties(p.enabled(), p.releaseRef(), p.dataSourceBean(), p.persistence(), p.limits(),
                    p.grants(), p.capabilities(), java.util.List.of(changed), p.connections(), p.rates(), p.budgets());
            var generation = new com.arte.ainew.pojo.generation.GenerationRequest(java.util.List.of(
                    new com.arte.ainew.pojo.generation.ChatMessage("message", com.arte.ainew.pojo.generation.ChatMessage.Role.USER,
                            java.util.List.of(new com.arte.ainew.pojo.generation.ChatMessage.Text("test")), java.util.List.of(), null)),
                    new com.arte.ainew.pojo.generation.GenerationOptions(256, null, null, java.util.List.of()), java.util.List.of(),
                    new com.arte.ainew.pojo.generation.GenerationRequest.TextOutput());
            var invocation = new com.arte.ainew.pojo.execution.InvocationRequest<>(AdmissionFixture.CAP, AdmissionFixture.BINDING,
                    binding.capability().kind(), generation, new com.arte.ainew.pojo.execution.ExecutionOptions(context.deadline(), 1,
                    p.limits().maxOutputBytes(), 0, 0, Duration.ofSeconds(10)), context);
            var now = clock.instant();
            var attempt = new com.arte.ainew.pojo.execution.Attempt("attempt", context.executionId(), 1, "worker", 1,
                    now.plusSeconds(20), 1, com.arte.ainew.pojo.execution.Attempt.State.RUNNING,
                    com.arte.ainew.pojo.execution.Attempt.Dispatch.MAY_HAVE_EXECUTED, "remote", "reservation",
                    com.arte.ainew.pojo.execution.Usage.unknown(), null, now, now);
            var call = new com.arte.ainew.pojo.execution.GatewayCall<>(invocation, binding, attempt,
                    com.arte.ainew.context.ExecutionRuntimeContext.start(context));
            return catalog.resolveCurrentConnection(binding.connection(), call);
        }).block(Duration.ofSeconds(5)));
        assertEquals(1, source.configurations.get());
    }
}
