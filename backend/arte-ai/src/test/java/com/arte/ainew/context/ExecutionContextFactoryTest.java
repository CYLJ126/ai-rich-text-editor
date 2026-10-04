package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import org.junit.After;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ExecutionContextFactoryTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);
    private final ExecutionContextRequest request = new ExecutionContextRequest(
            "tenant", "workspace", Set.of("read"), Duration.ofSeconds(10), null, null, null, "request-key");

    @After
    public void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    public void capturesMvcIdentityBeforeThreadSwitchAndDoesNotRetainCredentials() {
        var factory = factory();
        SecurityContextHolder.getContext().setAuthentication(authentication("alice"));
        Mono<ExecutionContext> pending = factory.createCurrent(request);
        SecurityContextHolder.clearContext();
        var context = pending.subscribeOn(Schedulers.parallel()).block(Duration.ofSeconds(5));
        assertNotNull(context);
        assertEquals("42", context.authorization().principal().subjectId());
        assertEquals("alice", context.authorization().principal().subjectName());
        assertEquals(clock.instant().plusSeconds(10), context.deadline());
        assertFalse(context.toString().contains("secret-password"));
        assertEquals(context.executionId(), pending.block(Duration.ofSeconds(5)).executionId());
    }

    @Test
    public void readsReactiveSecurityContextPerSubscription() {
        var pending = factory().createReactive(request);
        var alice = pending.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication("alice")));
        var bob = pending.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication("bob")));
        StepVerifier.create(Mono.zip(alice, bob)).assertNext(pair -> {
            assertEquals("alice", pair.getT1().authorization().principal().subjectName());
            assertEquals("bob", pair.getT2().authorization().principal().subjectName());
            assertNotEquals(pair.getT1().executionId(), pair.getT2().executionId());
        }).verifyComplete();
    }

    @Test
    public void rejectsMissingAnonymousAndUnauthenticatedIdentityBeforeResolution() {
        var calls = new AtomicInteger();
        var factory = new ExecutionContextFactory((name, tenant, workspace, scopes) -> {
            calls.incrementAndGet();
            return Mono.empty();
        }, clock);
        StepVerifier.create(factory.create(null, request)).expectError(AuthenticationCredentialsNotFoundException.class).verify();
        StepVerifier.create(factory.create(new UsernamePasswordAuthenticationToken("alice", "password"), request))
                .expectError(AuthenticationCredentialsNotFoundException.class).verify();
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        StepVerifier.create(factory.create(anonymous, request)).expectError(AuthenticationCredentialsNotFoundException.class).verify();
        StepVerifier.create(factory.createReactive(request)).expectError(AuthenticationCredentialsNotFoundException.class).verify();
        assertEquals(0, calls.get());
    }

    @Test
    public void failsClosedOnMissingAuthorizationAndMismatchedIdentityScope() {
        var empty = new ExecutionContextFactory((name, tenant, workspace, scopes) -> Mono.empty(), clock);
        StepVerifier.create(empty.create(authentication("alice"), request)).expectError(AccessDeniedException.class).verify();
        for (var invalid : List.of(
                authorization("bob", "tenant", "workspace", Set.of("read")),
                authorization("alice", "other-tenant", "workspace", Set.of("read")),
                authorization("alice", "tenant", "other-workspace", Set.of("read")),
                authorization("alice", "tenant", "workspace", Set.of("read", "write")))) {
            var factory = new ExecutionContextFactory((name, tenant, workspace, scopes) -> Mono.just(invalid), clock);
            StepVerifier.create(factory.create(authentication("alice"), request)).expectError(AccessDeniedException.class).verify();
        }
    }

    @Test
    public void authorizationWaitHasTheExecutionDeadline() {
        var factory = new ExecutionContextFactory((name, tenant, workspace, scopes) -> Mono.never(), clock);
        StepVerifier.withVirtualTime(() -> factory.create(authentication("alice"), request))
                .thenAwait(Duration.ofSeconds(10)).expectError(TimeoutException.class).verify();
    }

    @Test
    public void restoresDurableIdentityWithFreshAuthorizationAndRuntimeCancellation() {
        var persisted = factory().create(authentication("alice"), request).block(Duration.ofSeconds(5));
        var refresh = new ExecutionContextFactory((name, tenant, workspace, scopes) ->
                Mono.just(new ExecutionAuthorization(persisted.authorization().principal(), tenant, workspace,
                        Set.of("read"), "new-grant")), clock);
        var previousRuntime = ExecutionRuntimeContext.start(persisted);
        previousRuntime.cancellation().cancel("old-process-signal");
        var restored = refresh.restore(persisted).block(Duration.ofSeconds(5));
        assertEquals(persisted.executionId(), restored.executionId());
        assertEquals(persisted.deadline(), restored.deadline());
        assertEquals(persisted.traceId(), restored.traceId());
        assertEquals("new-grant", restored.authorization().grantRef());
        assertFalse(ExecutionRuntimeContext.start(restored).cancellation().isCancelled());
    }

    @Test
    public void restoreRejectsRevokedExpiredOrReassignedIdentity() {
        var persisted = factory().create(authentication("alice"), request).block(Duration.ofSeconds(5));
        var revoked = new ExecutionContextFactory((name, tenant, workspace, scopes) ->
                Mono.error(new AccessDeniedException("Access revoked")), clock);
        StepVerifier.create(revoked.restore(persisted)).expectError(AccessDeniedException.class).verify();
        var reassigned = new ExecutionContextFactory((name, tenant, workspace, scopes) ->
                Mono.just(new ExecutionAuthorization(new ExecutionPrincipal("43", name, ExecutionPrincipal.Kind.USER),
                        tenant, workspace, scopes, "grant")), clock);
        StepVerifier.create(reassigned.restore(persisted)).expectError(AccessDeniedException.class).verify();
        var expired = new ExecutionContextFactory((name, tenant, workspace, scopes) -> {
            fail("Expired execution must not resolve authorization");
            return Mono.empty();
        }, Clock.fixed(persisted.deadline(), ZoneOffset.UTC));
        StepVerifier.create(expired.restore(persisted)).expectError(TimeoutException.class).verify();
    }

    private ExecutionContextFactory factory() {
        return new ExecutionContextFactory((name, tenant, workspace, scopes) ->
                Mono.just(authorization(name, tenant, workspace, scopes)), clock);
    }

    private ExecutionAuthorization authorization(String name, String tenant, String workspace, Set<String> scopes) {
        return new ExecutionAuthorization(new ExecutionPrincipal("42", name, ExecutionPrincipal.Kind.USER),
                tenant, workspace, scopes, "grant");
    }

    private UsernamePasswordAuthenticationToken authentication(String name) {
        return new UsernamePasswordAuthenticationToken(name, "secret-password",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }
}
