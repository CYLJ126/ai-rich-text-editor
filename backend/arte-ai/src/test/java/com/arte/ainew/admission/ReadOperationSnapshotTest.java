package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
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

import static org.junit.Assert.*;

public class ReadOperationSnapshotTest {
    private final Fixture holder = new Fixture();

    private static class Fixture {
        final com.arte.ainew.config.NewAiProperties properties = AdmissionFixture.properties();
        final FixedExecutionAuthorizationResolver fixed = new FixedExecutionAuthorizationResolver(properties);
        final AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        volatile boolean revoked;
        final ExecutionReadAuthorizationResolver resolver = new ExecutionReadAuthorizationResolver() {
            public Mono<ExecutionAuthorization> resolve(String name, String tenant, String workspace, Set<String> scopes) {
                writes.incrementAndGet();
                return revoked ? Mono.error(new AccessDeniedException("revoked")) : fixed.resolve(name, tenant, workspace, scopes);
            }

            public Mono<Snapshot> readSnapshot(String name, String tenant, String workspace, Set<String> scopes, boolean configuration) {
                reads.incrementAndGet();
                return revoked ? Mono.error(new AccessDeniedException("revoked"))
                        : fixed.resolve(name, tenant, workspace, scopes).map(auth -> new Snapshot(auth, configuration ? properties : null));
            }
        };
    }

    @After
    public void clear() {
        SecurityContextHolder.clearContext();
    }

    void login(String name) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(name, null, java.util.List.of()));
    }

    ExecutionContextRequest request() {
        return new ExecutionContextRequest("tenant", "workspace", Set.of(AdmissionAuthorization.READ), Duration.ofSeconds(10), null, null, "release-v1", null);
    }

    @Test
    public void snapshotIsBoundToOneSubscriptionAndCannotAuthorizeWritesOrOtherContexts() {
        login("alice");
        var factory = new ExecutionContextFactory(holder.resolver, Clock.systemUTC());
        var auth = new AdmissionAuthorization(holder.resolver, holder.properties, Clock.systemUTC());
        var saved = new AtomicReference<ExecutionContext>();
        var operation = factory.readCurrent(request(), false, ctx -> {
            saved.set(ctx);
            return auth.require(ctx, AdmissionAuthorization.READ).publishOn(Schedulers.parallel())
                    .flatMap(current -> auth.require(current, AdmissionAuthorization.READ))
                    .flatMap(current -> auth.requireWrite(current, AdmissionAuthorization.READ)
                            .thenReturn(false).onErrorResume(IllegalStateException.class, error -> Mono.just(true)));
        });
        login("bob"); // 捕获名称不能延迟到订阅或读取可变认证对象。
        assertTrue(operation.block(Duration.ofSeconds(5)));
        assertEquals("alice", saved.get().authorization().principal().subjectName());
        assertTrue(operation.block(Duration.ofSeconds(5)));
        assertEquals(2, holder.reads.get());
        assertEquals(0, holder.writes.get());
        holder.revoked = true;
        assertThrows(AccessDeniedException.class, () -> auth.require(saved.get(), AdmissionAuthorization.READ).block(Duration.ofSeconds(5)));
        assertEquals(3, holder.reads.get());
        assertThrows(AccessDeniedException.class, () -> operation.block(Duration.ofSeconds(5)));
    }

    @Test
    public void writeBoundaryUsesFreshResolverAndScopeMismatchFailsClosed() {
        login("alice");
        var factory = new ExecutionContextFactory(holder.resolver, Clock.systemUTC());
        var auth = new AdmissionAuthorization(holder.resolver, holder.properties, Clock.systemUTC());
        assertThrows(AccessDeniedException.class, () -> factory.readCurrent(request(), false,
                        ctx -> auth.require(ctx.child("other", ctx.deadline(), Set.of(AdmissionAuthorization.READ), null), AdmissionAuthorization.READ))
                .block(Duration.ofSeconds(5)));
        var context = factory.createCurrent(request()).block(Duration.ofSeconds(5));
        auth.requireWrite(context, AdmissionAuthorization.READ).block(Duration.ofSeconds(5));
        assertEquals(2, holder.writes.get());
        holder.revoked = true;
        assertThrows(AccessDeniedException.class, () -> auth.requireWrite(context, AdmissionAuthorization.READ).block(Duration.ofSeconds(5)));
    }
}
