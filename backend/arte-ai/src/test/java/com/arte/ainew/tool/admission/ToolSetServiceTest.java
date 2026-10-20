package com.arte.ainew.tool.admission;

import com.arte.ainew.application.tool.chat.ToolSetService;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 真实绑定、发布与 Schema 存储上的集合固定；不提交 Invocation 或预算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 15:20 ✾
 */
public class ToolSetServiceTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private ToolManagementAccess.Session session(ToolAdmissionFixture fixture, Set<String> scopes, AtomicBoolean allowed, AtomicInteger checks) {
        return (scope, operation) -> Mono.defer(() -> {
            checks.incrementAndGet();
            if (!allowed.get()) return Mono.error(new AccessDeniedException("revoked"));
            return Mono.just(new ToolManagementAccess.Permit() {
                public TrustedActor actor() {
                    return fixture.actor;
                }

                public ExecutionOwner owner() {
                    return fixture.owner;
                }

                public ToolManagementAccess.Operation operation() {
                    return operation;
                }

                public Set<String> executionScopes() {
                    return scopes;
                }

                public void requireInCurrentTransaction(Set<String> required) {
                    throw new AssertionError("Selection cannot authorize tool execution");
                }
            });
        });
    }

    @Test
    public void fixesExactBindingSchemaAndPolicyAndFreshSelectionTracksCurrentVersion() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var service = new ToolSetService(f.bindingService, f.catalog, f.metadata, WAIT);
            var checks = new AtomicInteger();
            var allowed = new AtomicBoolean(true);
            var session = session(f, Set.of("resource:read"), allowed, checks);
            var scope = new Scope("tenant", "workspace");
            var restriction = new ToolExecutionPolicy(Duration.ofSeconds(2), 1, Duration.ZERO, 512, 512, ToolExecutionPolicy.ApprovalMode.NONE);
            var preparation = service.prepare(session, scope, List.of(new ToolSetService.Selection(f.binding.bindingId(), restriction)));
            assertEquals(0, checks.get());
            var first = preparation.block(WAIT);
            assertEquals("v1", first.entries().getFirst().binding().tool().tool().definition().version());
            assertEquals(512, first.entries().getFirst().policy().maxInputBytes());
            assertEquals(Duration.ofSeconds(2), first.entries().getFirst().policy().timeout());
            f.publish(f.v2, f.v1);
            f.changeConfiguration();
            var second = preparation.block(WAIT);
            assertEquals("v2", second.entries().getFirst().binding().tool().tool().definition().version());
            assertNotEquals(first.entries().getFirst().binding(), second.entries().getFirst().binding());
            assertEquals(f.v1.revision().tool().definition(), first.descriptions().getFirst().tool());
            assertEquals(2, checks.get());
            assertEquals(0, f.count("arte_ai_invocation"));
            allowed.set(false);
            assertThrows(AccessDeniedException.class, () -> preparation.block(WAIT));
        }
    }

    @Test
    public void rejectsUnavailableForeignAndDuplicateBindingsAndMissingResourceScopes() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var service = new ToolSetService(f.bindingService, f.catalog, f.metadata, WAIT);
            var scope = new Scope("tenant", "workspace");
            var selected = List.of(new ToolSetService.Selection(f.binding.bindingId(), null));
            var allowed = new AtomicBoolean(true);
            var checks = new AtomicInteger();
            var session = session(f, Set.of("resource:read"), allowed, checks);
            assertThrows(AccessDeniedException.class, () -> service.prepare(session(f, Set.of(), allowed, checks), scope, selected).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> service.prepare(session, new Scope("tenant", "other"), selected).block(WAIT));
            assertThrows(IllegalArgumentException.class, () -> service.prepare(session, scope, List.of(selected.getFirst(), selected.getFirst())));
            var another = f.createBinding("other-config", com.arte.ainew.pojo.tool.ToolBindingSettings.VersionPolicy.PINNED);
            assertThrows(IllegalArgumentException.class, () -> service.prepare(session, scope, List.of(selected.getFirst(), new ToolSetService.Selection(another.bindingId(), null))).block(WAIT));
            assertThrows(RuntimeException.class, () -> service.prepare(session, scope, List.of(new ToolSetService.Selection("nonexistent", null))).block(WAIT));
            f.disable();
            assertThrows(RuntimeException.class, () -> service.prepare(session, scope, selected).block(WAIT));
        }
    }
}
