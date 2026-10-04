package com.arte.ainew.context;

import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class ReactiveExecutionContextTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);

    @Test
    public void preservesOtherKeysAndPropagatesAcrossBothSchedulerBoundaries() {
        var runtime = runtime("alice", 10);
        var source = Mono.just("start").publishOn(Schedulers.parallel())
                .flatMap(ignored -> ReactiveExecutionContext.current())
                .flatMap(current -> Mono.deferContextual(view -> Mono.just(
                        current.execution().authorization().principal().subjectName() + ":" + view.get("other"))));
        StepVerifier.create(ReactiveExecutionContext.withContext(source, runtime)
                .contextWrite(Context.of("other", "preserved")).subscribeOn(Schedulers.boundedElastic()))
                .expectNext("alice:preserved").verifyComplete();
    }

    @Test
    public void concurrentSubscribersDoNotShareIdentity() {
        Mono<String> reusable = Mono.delay(Duration.ofMillis(1), Schedulers.parallel())
                .flatMap(ignored -> ReactiveExecutionContext.current())
                .map(runtime -> runtime.execution().authorization().principal().subjectName());
        var results = Flux.range(0, 100).flatMap(index ->
                ReactiveExecutionContext.withContext(reusable, runtime("user-" + index, 10))
                        .map(name -> name.equals("user-" + index)), 16);
        StepVerifier.create(results).recordWith(ArrayList::new).expectNextCount(100)
                .consumeRecordedWith(values -> assertTrue(values.stream().allMatch(Boolean::booleanValue)))
                .verifyComplete();
    }

    @Test
    public void nestedScopeRestoresParentWithoutMutatingItsContext() {
        var outer = runtime("alice", 10);
        var inner = runtime("bob", 10);
        var source = ReactiveExecutionContext.current().flatMap(parent ->
                ReactiveExecutionContext.withContext(ReactiveExecutionContext.current(), inner)
                        .flatMap(child -> ReactiveExecutionContext.current().map(restored ->
                                parent.execution().executionId() + ":" + child.execution().executionId()
                                        + ":" + restored.execution().executionId())));
        StepVerifier.create(ReactiveExecutionContext.withContext(source, outer))
                .expectNext("alice:bob:alice").verifyComplete();
    }

    @Test
    public void missingContextDoesNotFallbackToAnyThreadLocalIdentity() {
        StepVerifier.create(ReactiveExecutionContext.current()).expectError(IllegalStateException.class).verify();
    }

    @Test
    public void explicitCancellationInterruptsGuardedPublisherAndReplaysForLateSubscribers() {
        var runtime = runtime("alice", 10);
        var upstreamCancelled = new AtomicBoolean();
        StepVerifier.create(ReactiveExecutionContext.withContext(
                        ReactiveExecutionContext.guard(Mono.never().doOnCancel(() -> upstreamCancelled.set(true)), clock), runtime))
                .then(() -> assertTrue(runtime.cancellation().cancel("user stopped")))
                .expectError(CancellationException.class).verify();
        assertTrue(upstreamCancelled.get());
        assertFalse(runtime.cancellation().cancel("duplicate"));
        StepVerifier.create(runtime.cancellation().signal()).expectNext("user stopped").verifyComplete();
    }

    @Test
    public void cancellingWatchingSubscriptionDoesNotCancelTheExecution() {
        var runtime = runtime("alice", 10);
        StepVerifier.create(ReactiveExecutionContext.withContext(Mono.never(), runtime)).thenCancel().verify();
        assertFalse(runtime.cancellation().isCancelled());
    }

    @Test
    public void expiredOrCancelledContextDoesNotSubscribeToOperation() {
        var calls = new AtomicInteger();
        Mono<String> source = Mono.fromCallable(() -> { calls.incrementAndGet(); return "called"; });
        var expired = runtime("expired", 0);
        StepVerifier.create(ReactiveExecutionContext.withContext(ReactiveExecutionContext.guard(source, clock), expired))
                .expectError(TimeoutException.class).verify();
        var cancelled = runtime("cancelled", 10);
        cancelled.cancellation().cancel("stop");
        StepVerifier.create(ReactiveExecutionContext.withContext(ReactiveExecutionContext.guard(source, clock), cancelled))
                .expectError(CancellationException.class).verify();
        assertEquals(0, calls.get());
    }

    @Test
    public void continuousOutputDoesNotResetAbsoluteDeadline() {
        StepVerifier.withVirtualTime(() -> ReactiveExecutionContext.withContext(
                        ReactiveExecutionContext.guard(Flux.interval(Duration.ofSeconds(1)), clock), runtime("alice", 3)))
                .thenAwait(Duration.ofSeconds(2)).expectNext(0L, 1L)
                .thenAwait(Duration.ofSeconds(1)).expectError(TimeoutException.class).verify();
    }

    @Test
    public void fluxControlFailureCancelsUpstreamAndSourceTerminalSignalsDoNotWaitForDeadline() {
        var runtime = runtime("alice", 10);
        var cancelled = new AtomicBoolean();
        StepVerifier.create(ReactiveExecutionContext.withContext(ReactiveExecutionContext.guard(
                        Flux.never().doOnCancel(() -> cancelled.set(true)), clock), runtime))
                .then(() -> runtime.cancellation().cancel("stop"))
                .expectError(CancellationException.class).verify(Duration.ofSeconds(5));
        assertTrue(cancelled.get());
        StepVerifier.create(ReactiveExecutionContext.withContext(
                        ReactiveExecutionContext.guard(Flux.just("done"), clock), runtime("other", 10)))
                .expectNext("done").expectComplete().verify(Duration.ofSeconds(5));
        StepVerifier.create(ReactiveExecutionContext.withContext(
                        ReactiveExecutionContext.guard(Flux.error(new IllegalStateException("failed")), clock), runtime("other", 10)))
                .expectErrorMessage("failed").verify(Duration.ofSeconds(5));
    }

    private ExecutionRuntimeContext runtime(String name, long seconds) {
        return ExecutionRuntimeContext.start(ExecutionContexts.context(name, "1", name, clock.instant().plusSeconds(seconds)));
    }
}
