package com.arte.core.diagnostics;

import com.arte.core.constant.CoreConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作观测测试
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 16:52 ✾
 */
public class OperationObservationTest {

    private final List<OperationObservation.Span> spans = new CopyOnWriteArrayList<>();
    private Scheduler scheduler;

    @BeforeEach
    public void setup() {
        RequestObservation.installReactorPropagation();
        scheduler = new ObservedScheduler(Schedulers.newBoundedElastic(1, 16, "phase-observation"), "db");
    }

    @AfterEach
    public void cleanup() {
        scheduler.dispose();
        RequestObservation.removeReactorPropagation();
    }

    private <T> Mono<T> stage(String name, java.util.function.Supplier<Mono<T>> source) {
        return OperationObservation.observe(name, "invocation", "original-trace", Instant.now().plusSeconds(10), span -> {
            spans.add(span);
            return source.get();
        });
    }

    @Test
    public void nestedStagesIncludeChildSqlOnceAndRestoreContextAcrossWorkerAndTimer() {
        String result = stage("TOTAL", () -> stage("READ", () -> Mono.delay(Duration.ofMillis(5))
                .then(Mono.fromCallable(() -> {
                    assertEquals("original-trace", MDC.get(CoreConstant.LOG_TRACE_ID));
                    var sql = OperationObservation.sqlStarted();
                    assertNotNull(sql);
                    sql.completed(2_000_000, true, false);
                    return "original";
                }).subscribeOn(scheduler)))).block(Duration.ofSeconds(5));
        assertEquals("original", result);
        for (var span : spans) {
            var report = span.report();
            assertEquals("SUCCESS", report.get("outcome"));
            assertEquals(1L, report.get("sqlCount"));
            assertEquals(1L, report.get("sqlCompletedCount"));
            assertEquals(0L, report.get("sqlInFlight"));
            assertEquals(2.0, report.get("forUpdateExecuteMs"));
            assertNull(report.get("lockWaitMs"));
        }
        assertNull(OperationObservation.current());
        assertNull(RequestObservation.current());
        assertNull(MDC.get(CoreConstant.LOG_TRACE_ID));
        Mono.fromRunnable(() -> {
            assertNull(OperationObservation.current());
            assertNull(MDC.get(CoreConstant.LOG_TRACE_ID));
        }).subscribeOn(scheduler).block(Duration.ofSeconds(5));
    }

    @Test
    public void detachedWorkerDoesNotChargeSqlToTheWakingHttpRequest() {
        var request = new RequestObservation("POST", "/ai-new/tool/invokeTool", null);
        try (var ignored = RequestObservation.bind(request)) {
            RequestObservation.observe(RequestObservation.background(stage("WORKER_LOAD", () -> Mono.fromCallable(() -> {
                assertNull(RequestObservation.current());
                OperationObservation.sqlStarted().completed(1_000_000, false, false);
                return "worker";
            }).subscribeOn(scheduler)))).block(Duration.ofSeconds(5));
        }
        assertEquals(0L, request.finish(202).get("sqlCount"));
        assertEquals(1L, spans.getFirst().report().get("sqlCount"));
    }

    @Test
    public void unrepresentableDiagnosticDeadlineDoesNotChangeWorkResult() {
        var captured = new AtomicReference<OperationObservation.Span>();
        assertEquals("result", OperationObservation.observe("READ", "id", "trace", Instant.MAX, span -> {
            captured.set(span);
            return Mono.just("result");
        }).block());
        assertNull(captured.get().report().get("deadlineEpochMs"));
        assertNull(captured.get().report().get("remainingEndMs"));
    }

    @Test
    public void errorEmptyAndCancellationRetainOriginalSignalsWithoutDuplicatingSubscription() {
        var failure = new IllegalStateException("private-input-must-not-be-recorded");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stage("FAIL", () -> Mono.error(failure)).block()));
        assertNull(stage("EMPTY", Mono::empty).block());
        var never = stage("WAIT", Mono::never).subscribe();
        never.dispose();
        assertEquals(List.of("ERROR", "EMPTY", "CANCELLED"), spans.stream().map(s -> s.report().get("outcome")).toList());
        assertFalse(spans.toString().contains(failure.getMessage()));
        assertFalse(spans.getFirst().report().toString().contains(failure.getMessage()));
        assertEquals("IllegalStateException", spans.getFirst().report().get("errorType"));
        assertEquals(false, spans.getFirst().report().get("jdbcObserved"));
    }

    @Test
    public void activeBlockingStageReportsItsOwnLateFailureAfterCallerTimeout() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var late = new AtomicReference<OperationObservation.Span>();
        try {
            var result = stage("COMMIT_WAIT", () -> Mono.fromCallable(() -> OperationObservation.call("TRANSACTION", "invocation", "original-trace", Instant.now().plusSeconds(10), () -> {
                late.set(OperationObservation.current());
                var sql = OperationObservation.sqlStarted();
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Not released");
                } catch (InterruptedException error) {
                    throw new AssertionError(error);
                }
                sql.completed(10_000_000, false, true);
                throw new IllegalStateException("private-late-database-error");
            })).subscribeOn(scheduler).doFinally(ignored -> completed.countDown()).cache())
                    .timeout(Duration.ofMillis(200)).toFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertEquals("CANCELLED", spans.getFirst().report().get("outcome"));
            assertEquals(1L, spans.getFirst().report().get("sqlInFlight"));
            release.countDown();
            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertEquals("ERROR", late.get().report().get("outcome"));
            assertEquals(1L, late.get().report().get("sqlErrors"));
            assertEquals(0L, late.get().report().get("sqlInFlight"));
            assertFalse(late.get().report().toString().contains("private-late-database-error"));
        } finally {
            release.countDown();
        }
    }
}
