package com.arte.base.execution;

import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.exception.BaseException;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionLimits;
import com.arte.base.model.admission.AdmissionPriority;
import com.arte.base.model.admission.AdmissionRequest;
import com.arte.base.model.error.CommonErrorCode;
import org.junit.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class LocalExecutionCompositionTest {
    @Test
    public void cancellationKeepsAdmissionPermitUntilActualWorkExit() throws Exception {
        var context = BoundedTaskExecutorTest.context(null);
        var key = new AdmissionKey(context.scope().tenantId(), "model", null, null);
        var request = new AdmissionRequest(context, key, AdmissionPriority.INTERACTIVE, Duration.ZERO);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var admission = new LocalAdmissionController(1, 0, Map.of(key, new AdmissionLimits(1, 10, Duration.ofMinutes(1))), Clock.systemUTC());
             var executor = new BoundedTaskExecutor(1, 1, Clock.systemUTC())) {
            var permit = admission.acquire(request).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            var task = executor.submit(context, checkpoint -> {
                started.countDown();
                release.await();
                return "returned-after-stop";
            });
            var released = task.completion().whenComplete((result, error) -> permit.close());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            task.requestCancellation();
            try {
                admission.acquire(request).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                fail("permit released early");
            } catch (ExecutionException failure) {
                assertEquals(CommonErrorCode.BUSY.code(), ((BaseException) failure.getCause()).error().code());
            }
            assertFalse(released.toCompletableFuture().isDone());
            release.countDown();
            assertThrows(ExecutionException.class, () -> released.toCompletableFuture().get(2, TimeUnit.SECONDS));
            admission.acquire(request).completion().toCompletableFuture().get(2, TimeUnit.SECONDS).close();
        } finally {
            release.countDown();
        }
    }
}
