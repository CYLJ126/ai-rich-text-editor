package com.arte.base.admission;

import com.arte.base.exception.BaseException;
import com.arte.base.model.admission.*;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LocalAdmissionControllerTest {
    final AdmissionKey key = new AdmissionKey("tenant", "model", null, null);

    AdmissionRequest request(AdmissionPriority priority, Duration wait) {
        var context = ExecutionContext.create(new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER)), "trace", Set.of());
        return new AdmissionRequest(context, key, priority, wait);
    }

    AdmissionPermit permit(AdmissionAttempt stage) throws Exception {
        return stage.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    String error(AdmissionAttempt stage) throws Exception {
        try {
            stage.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected rejection");
        } catch (ExecutionException error) {
            return ((BaseException) error.getCause()).error().code();
        }
    }

    @Test
    public void concurrencyQueueAndPriorityAreBoundedAndReleaseIsIdempotent() throws Exception {
        try (var admission = new LocalAdmissionController(1, 2, Map.of(key, new AdmissionLimits(1, 20, Duration.ofMinutes(1))), Clock.systemUTC())) {
            var active = permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO)));
            var background = admission.acquire(request(AdmissionPriority.BACKGROUND, Duration.ofSeconds(2)));
            var interactive = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(2)));
            assertEquals(CommonErrorCode.BUSY.code(), error(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(2)))));
            active.close();
            active.close();
            var next = permit(interactive);
            assertFalse(background.completion().toCompletableFuture().isDone());
            next.close();
            permit(background).close();
        }
    }

    @Test
    public void cancelledWaitDoesNotLeakPermitAndQueueWaitExpires() throws Exception {
        try (var admission = new LocalAdmissionController(1, 1, Map.of(key, new AdmissionLimits(1, 20, Duration.ofMinutes(1))), Clock.systemUTC())) {
            var active = permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO)));
            var cancelled = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(2)));
            assertTrue(cancelled.cancelWaiting());
            var waiting = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofMillis(100)));
            assertEquals(CommonErrorCode.BUSY.code(), error(waiting));
            active.close();
            permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO))).close();
        }
    }

    @Test
    public void releaseDoesNotReturnRateQuotaAndWindowWakeupAllowsWaitingWork() throws Exception {
        try (var admission = new LocalAdmissionController(1, 1, Map.of(key, new AdmissionLimits(1, 1, Duration.ofMillis(120))), Clock.systemUTC())) {
            permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO))).close();
            assertEquals(CommonErrorCode.RATE_LIMITED.code(), error(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO))));
            permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(1)))).close();
        }
    }

    @Test
    public void unknownPartitionAndShutdownNeverAllowImplicitly() throws Exception {
        var admission = new LocalAdmissionController(1, 1, Map.of(), Clock.systemUTC());
        assertEquals(CommonErrorCode.POLICY_UNAVAILABLE.code(), error(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO))));
        admission.close();
        assertEquals(CommonErrorCode.BUSY.code(), error(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO))));
    }

    @Test
    public void partitionsShareGlobalLimitWithoutBlockingReadyPartitionBehindBusyOne() throws Exception {
        var other = new AdmissionKey("tenant", "indexing", null, null);
        try (var admission = new LocalAdmissionController(2, 2, Map.of(key, new AdmissionLimits(1, 20, Duration.ofMinutes(1)), other, new AdmissionLimits(1, 20, Duration.ofMinutes(1))), Clock.systemUTC())) {
            var first = permit(admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO)));
            var queued = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(2)));
            var sample = request(AdmissionPriority.BACKGROUND, Duration.ofSeconds(2));
            var second = permit(admission.acquire(new AdmissionRequest(sample.context(), other, sample.priority(), sample.maxWait())));
            assertFalse(queued.completion().toCompletableFuture().isDone());
            first.close();
            permit(queued).close();
            second.close();
        }
    }

    @Test
    public void completionCopyCannotForgeGrantOrCancelGrantedWork() throws Exception {
        try (var admission = new LocalAdmissionController(1, 1, Map.of(key, new AdmissionLimits(1, 20, Duration.ofMinutes(1))), Clock.systemUTC())) {
            var activeAttempt = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ZERO));
            var active = permit(activeAttempt);
            assertFalse(activeAttempt.cancelWaiting());
            var waiting = admission.acquire(request(AdmissionPriority.INTERACTIVE, Duration.ofSeconds(2)));
            waiting.completion().toCompletableFuture().complete(active);
            assertFalse(waiting.completion().toCompletableFuture().isDone());
            active.close();
            permit(waiting).close();
        }
    }
}
