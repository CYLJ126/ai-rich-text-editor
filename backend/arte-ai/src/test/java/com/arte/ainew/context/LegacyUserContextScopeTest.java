package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.core.constant.CoreConstant;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import org.junit.After;
import org.junit.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class LegacyUserContextScopeTest {
    @After
    public void clear() {
        UserContext.clear();
        MDC.clear();
    }

    @Test
    public void restoresNestedUserAndTraceAfterFailureWithoutCopyingCredentials() {
        var previous = new UserOnlineInfo().setId(99).setUserName("previous").setToken("old-token");
        UserContext.setUserOnlineInfo(previous);
        MDC.put(CoreConstant.LOG_TRACE_ID, "original-trace");
        MDC.put("unrelated", "keep");
        var alice = context("alice", "1");
        try (var outer = LegacyUserContextScope.open(alice)) {
            assertEquals("alice", UserContext.getUserName());
            assertNull(UserContext.getUserOnlineInfo().getToken());
            assertNull(UserContext.getUserOnlineInfo().getPassword());
            assertNull(UserContext.getUserOnlineInfo().getMenuOperations());
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = LegacyUserContextScope.open(context("bob", "2"))) {
                    assertEquals("bob", UserContext.getUserName());
                    throw new IllegalStateException("failed operation");
                }
            });
            assertEquals("alice", UserContext.getUserName());
            assertEquals(alice.traceId(), MDC.get(CoreConstant.LOG_TRACE_ID));
        }
        assertSame(previous, UserContext.getUserOnlineInfo());
        assertEquals("original-trace", MDC.get(CoreConstant.LOG_TRACE_ID));
        assertEquals("keep", MDC.get("unrelated"));
    }

    @Test
    public void rejectsOutOfOrderAndCrossThreadCloseAndCleansAnInitiallyEmptyThread() throws Exception {
        var outer = LegacyUserContextScope.open(context("alice", "1"));
        var inner = LegacyUserContextScope.open(context("bob", "2"));
        try {
            assertThrows(IllegalStateException.class, outer::close);
            var failure = new AtomicReference<Throwable>();
            var wrongThread = new Thread(() -> {
                try { inner.close(); } catch (Throwable e) { failure.set(e); }
            });
            wrongThread.start();
            wrongThread.join(5000);
            assertTrue(failure.get() instanceof IllegalStateException);
        } finally {
            inner.close();
            outer.close();
        }
        outer.close();
        assertFalse(UserContext.hasUserOnlineInfo());
        assertNull(MDC.get(CoreConstant.LOG_TRACE_ID));
    }

    @Test
    public void rejectsUnmappableOrServiceIdentityWithoutChangingPreviousUser() {
        var user = new UserOnlineInfo().setId(99).setUserName("previous");
        UserContext.setUserOnlineInfo(user);
        assertThrows(IllegalArgumentException.class, () -> LegacyUserContextScope.open(context("alice", "uuid")));
        var service = new ExecutionContext("service", "trace",
                new ExecutionAuthorization(new ExecutionPrincipal("1", "service", ExecutionPrincipal.Kind.SERVICE),
                        "tenant", "workspace", Set.of(), "grant"), Instant.now().plusSeconds(10), null, null, null, null);
        assertThrows(IllegalArgumentException.class, () -> LegacyUserContextScope.open(service));
        assertSame(user, UserContext.getUserOnlineInfo());
    }

    @Test
    public void blockingBridgeRunsLazilyOnDedicatedThreadAndRestoresItAfterFailure() {
        var scheduler = Schedulers.newBoundedElastic(1, 16, "legacy-context-test");
        try {
            var bridge = new BlockingExecutionBridge(scheduler, Clock.systemUTC());
            Mono<String> operation = bridge.call(context -> {
                assertTrue(Thread.currentThread().getName().startsWith("legacy-context-test"));
                assertEquals("alice", UserContext.getUserName());
                assertEquals(context.traceId(), MDC.get(CoreConstant.LOG_TRACE_ID));
                throw new IllegalStateException("domain failed");
            });
            StepVerifier.create(ReactiveExecutionContext.withContext(operation, ExecutionRuntimeContext.start(context("alice", "1"))))
                    .expectErrorMessage("domain failed").verify(Duration.ofSeconds(5));
            assertFalse(Mono.fromCallable(UserContext::hasUserOnlineInfo).subscribeOn(scheduler).block(Duration.ofSeconds(5)));
            assertNull(Mono.fromCallable(() -> MDC.get(CoreConstant.LOG_TRACE_ID)).subscribeOn(scheduler).block(Duration.ofSeconds(5)));
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    public void reusedBlockingWorkerDoesNotMixConcurrentUsers() {
        var scheduler = Schedulers.newBoundedElastic(1, 16, "legacy-isolation-test");
        try {
            var bridge = new BlockingExecutionBridge(scheduler, Clock.systemUTC());
            var results = Flux.range(1, 50).flatMap(index -> ReactiveExecutionContext.withContext(
                    bridge.call(execution -> {
                        assertEquals("user-" + index, UserContext.getUserName());
                        assertEquals(Integer.valueOf(index), UserContext.getUserOnlineInfo().getId());
                        return index;
                    }), ExecutionRuntimeContext.start(context("user-" + index, String.valueOf(index)))), 8);
            StepVerifier.create(results).expectNextCount(50).expectComplete().verify(Duration.ofSeconds(5));
            assertFalse(Mono.fromCallable(UserContext::hasUserOnlineInfo).subscribeOn(scheduler).block(Duration.ofSeconds(5)));
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    public void cancelledBlockingCallRestoresScopeOnceTheOperationExits() {
        var scheduler = Schedulers.newBoundedElastic(1, 16, "legacy-cancellation-test");
        var entered = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var runtime = ExecutionRuntimeContext.start(context("alice", "1"));
        try {
            var bridge = new BlockingExecutionBridge(scheduler, Clock.systemUTC());
            var operation = bridge.call(execution -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    exited.countDown();
                }
                return "finished";
            });
            StepVerifier.create(ReactiveExecutionContext.withContext(operation, runtime))
                    .then(() -> {
                        try {
                            assertTrue(entered.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                        runtime.cancellation().cancel("stop");
                    }).expectError(java.util.concurrent.CancellationException.class).verify(Duration.ofSeconds(5));
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertFalse(Mono.fromCallable(UserContext::hasUserOnlineInfo).subscribeOn(scheduler).block(Duration.ofSeconds(5)));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            scheduler.dispose();
        }
    }

    private ExecutionContext context(String name, String id) {
        return ExecutionContexts.context(name, id, name, Instant.now().plusSeconds(30));
    }
}
