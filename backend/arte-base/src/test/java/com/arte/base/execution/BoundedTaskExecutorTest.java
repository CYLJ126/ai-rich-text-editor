package com.arte.base.execution;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class BoundedTaskExecutorTest {
    static ExecutionContext context(Instant deadline) {
        return new ExecutionContext(new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER)),
                "trace", null, deadline, null, Set.of(), null, null, null);
    }

    static BaseException error(TaskHandle<?> handle) throws Exception {
        try {
            handle.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            throw new AssertionError("expected failure");
        } catch (ExecutionException failure) {
            return (BaseException) failure.getCause();
        }
    }

    @Test
    public void boundedQueueRejectsAndNeverRunsOnSubmittingThread() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new BoundedTaskExecutor(1, 1, Clock.systemUTC())) {
            var first = executor.submit(context(null), checkpoint -> {
                started.countDown();
                release.await();
                return Thread.currentThread().getName();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var second = executor.submit(context(null), checkpoint -> "next");
            var rejected = executor.submit(context(null), checkpoint -> {
                throw new AssertionError("must not run");
            });
            assertEquals(CommonErrorCode.BUSY.code(), error(rejected).error().code());
            release.countDown();
            assertTrue(first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS).startsWith("arte-work-"));
            assertEquals("next", second.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void queuedCancellationDoesNotExecuteAndDoesNotAffectAnotherTask() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ran = new AtomicBoolean();
        try (var executor = new BoundedTaskExecutor(1, 1, Clock.systemUTC())) {
            var first = executor.submit(context(null), checkpoint -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(context(null), checkpoint -> {
                ran.set(true);
                return 2;
            });
            assertEquals(CancellationStatus.REQUEST_ACCEPTED, queued.requestCancellation());
            assertEquals(SideEffectStatus.NONE, error(queued).error().sideEffectStatus());
            assertEquals(TaskState.CANCELLED, queued.state());
            release.countDown();
            assertEquals(Integer.valueOf(1), first.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertFalse(ran.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void runningCancellationDoesNotCompleteUntilWorkUnwindsOrFreeExecutorSlot() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new BoundedTaskExecutor(1, 0, Clock.systemUTC())) {
            var task = executor.submit(context(null), checkpoint -> {
                started.countDown();
                release.await();
                checkpoint.check();
                return "never";
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals(CancellationStatus.CANCELLING, task.requestCancellation());
            assertFalse(task.completion().toCompletableFuture().isDone());
            assertEquals(TaskState.RUNNING, task.state());
            assertEquals(CommonErrorCode.BUSY.code(), error(executor.submit(context(null), checkpoint -> "blocked")).error().code());
            release.countDown();
            assertEquals(CommonErrorCode.INTERRUPTED.code(), error(task).error().code());
            assertEquals(TaskState.CANCELLED, task.state());
            assertEquals(SideEffectStatus.UNKNOWN, error(task).error().sideEffectStatus());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void expiredQueuedTaskCompletesWithoutWaitingForBusyWorker() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new BoundedTaskExecutor(1, 1, Clock.systemUTC())) {
            executor.submit(context(null), checkpoint -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(context(Instant.now().plusMillis(150)), checkpoint -> {
                throw new AssertionError("expired task ran");
            });
            assertEquals(CommonErrorCode.DEADLINE_EXCEEDED.code(), error(queued).error().code());
            assertEquals(TaskState.TIMED_OUT, queued.state());
            assertEquals(SideEffectStatus.NONE, error(queued).error().sideEffectStatus());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void futureCopyCannotPrematurelyCancelOrCompleteActualWork() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = new BoundedTaskExecutor(1, 0, Clock.systemUTC())) {
            var task = executor.submit(context(null), checkpoint -> {
                started.countDown();
                release.await();
                return "actual";
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            task.completion().toCompletableFuture().complete("forged");
            assertFalse(task.completion().toCompletableFuture().isDone());
            release.countDown();
            assertEquals("actual", task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(CancellationStatus.ALREADY_COMPLETED, task.requestCancellation());
        } finally {
            release.countDown();
        }
    }

    @Test
    public void unexpectedFailurePreservesUnknownEffectsAndCloseRejectsNewWork() throws Exception {
        var executor = new BoundedTaskExecutor(1, 1, Clock.systemUTC());
        var task = executor.submit(context(null), checkpoint -> {
            throw new IllegalStateException("diagnostic only");
        });
        var error = error(task);
        assertEquals(CommonErrorCode.INTERNAL_ERROR.code(), error.error().code());
        assertEquals(SideEffectStatus.UNKNOWN, error.error().sideEffectStatus());
        assertEquals(ResultCertainty.UNKNOWN, error.error().resultCertainty());
        assertFalse(error.error().retryable());
        assertEquals("diagnostic only", error.getCause().getMessage());
        executor.close();
        assertEquals(CommonErrorCode.BUSY.code(), error(executor.submit(context(null), checkpoint -> 1)).error().code());
    }
}
