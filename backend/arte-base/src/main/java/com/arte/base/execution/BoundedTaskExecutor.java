package com.arte.base.execution;

import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.spi.execution.ExecutionTask;
import com.arte.base.spi.execution.TaskExecutor;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDK 本地有界工作池；满载拒绝，不在提交线程执行阻塞工作，不提供跨进程恢复。
 */
public final class BoundedTaskExecutor implements TaskExecutor, AutoCloseable {
    private final ThreadPoolExecutor pool;
    private final ScheduledThreadPoolExecutor timer;
    private final Clock clock;
    private final Set<Work<?>> live = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private boolean closed;

    public BoundedTaskExecutor(int threads, int queueCapacity, Clock clock) {
        if (threads <= 0 || queueCapacity < 0) throw new IllegalArgumentException("invalid executor capacity");
        this.clock = ContractChecks.required(clock, "clock");
        var number = new java.util.concurrent.atomic.AtomicLong();
        ThreadFactory factory = task -> {
            var thread = new Thread(task, "arte-work-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        BlockingQueue<Runnable> queue = queueCapacity == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(queueCapacity);
        pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, queue, factory, new ThreadPoolExecutor.AbortPolicy());
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "arte-work-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }

    @Override
    public <T> TaskHandle<T> submit(ExecutionContext context, ExecutionTask<T> task) {
        ContractChecks.required(context, "context");
        ContractChecks.required(task, "task");
        var work = new Work<T>(context, task);
        boolean reject;
        synchronized (lifecycle) {
            reject = closed;
            if (!reject) {
                live.add(work);
                try {
                    pool.execute(work);
                } catch (RejectedExecutionException failure) {
                    reject = true;
                }
            }
        }
        if (reject) work.reject();
        else work.scheduleDeadline();
        return work.handle;
    }

    @Override
    public void close() {
        ArrayList<Work<?>> pending;
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            pending = new ArrayList<>(live);
            pool.shutdown();
            timer.shutdownNow();
        }
        pending.forEach(Work::cancel);
    }

    private final class Work<T> implements Runnable {
        final ExecutionContext context;
        final ExecutionTask<T> task;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean cancellation = new AtomicBoolean();
        final TaskHandle<T> handle;
        TaskState state = TaskState.QUEUED;
        ScheduledFuture<?> deadline;

        Work(ExecutionContext context, ExecutionTask<T> task) {
            this.context = context;
            this.task = task;
            handle = new TaskHandle<>(UUID.randomUUID().toString(), context, result, this::state, this::cancel);
        }

        synchronized TaskState state() {
            return state;
        }

        boolean terminal() {
            return state != TaskState.QUEUED && state != TaskState.RUNNING;
        }

        void scheduleDeadline() {
            if (context.deadline() == null) return;
            synchronized (this) {
                if (terminal() || timer.isShutdown()) return;
                long nanos;
                try {
                    nanos = Math.max(0, Duration.between(clock.instant(), context.deadline()).toNanos());
                } catch (ArithmeticException tooFar) {
                    nanos = Long.MAX_VALUE;
                }
                try {
                    deadline = timer.schedule(this::expire, nanos, TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException shutdown) { /* close 已对 live 工作请求停止 */ }
            }
        }

        void expire() {
            boolean finish = false;
            synchronized (this) {
                if (terminal()) return;
                if (!context.isExpiredAt(clock.instant())) {
                    scheduleDeadline();
                    return;
                }
                if (state == TaskState.QUEUED) {
                    state = TaskState.TIMED_OUT;
                    finish = true;
                }
            }
            if (finish) {
                pool.remove(this);
                finish(null, ExecutionFailures.beforeStart(CommonErrorCode.DEADLINE_EXCEEDED, context, "execution"));
            }
            // RUNNING 只能在实际退出后完成；checkpoint 和连接的 I/O 超时负责合作式停止。
        }

        CancellationStatus cancel() {
            boolean finish = false;
            synchronized (this) {
                if (terminal())
                    return state == TaskState.CANCELLED ? CancellationStatus.CANCELLED : CancellationStatus.ALREADY_COMPLETED;
                cancellation.set(true);
                if (state == TaskState.QUEUED) {
                    state = TaskState.CANCELLED;
                    finish = true;
                }
            }
            if (finish) {
                pool.remove(this);
                finish(null, ExecutionFailures.beforeStart(CommonErrorCode.INTERRUPTED, context, "execution"));
            }
            return finish ? CancellationStatus.REQUEST_ACCEPTED : CancellationStatus.CANCELLING;
        }

        void reject() {
            synchronized (this) {
                state = TaskState.FAILED;
            }
            finish(null, ExecutionFailures.beforeStart(CommonErrorCode.BUSY, context, "execution"));
        }

        @Override
        public void run() {
            BaseException stopped = null;
            synchronized (this) {
                if (terminal()) return;
                if (context.isExpiredAt(clock.instant())) {
                    state = TaskState.TIMED_OUT;
                    stopped = ExecutionFailures.beforeStart(CommonErrorCode.DEADLINE_EXCEEDED, context, "execution");
                } else if (cancellation.get()) {
                    state = TaskState.CANCELLED;
                    stopped = ExecutionFailures.beforeStart(CommonErrorCode.INTERRUPTED, context, "execution");
                } else state = TaskState.RUNNING;
            }
            if (stopped != null) {
                finish(null, stopped);
                return;
            }
            T value = null;
            Throwable failure = null;
            var checkpoint = new ExecutionCheckpoint(context, clock, cancellation);
            try {
                checkpoint.check();
                value = task.execute(checkpoint);
            } catch (Throwable thrown) {
                if (thrown instanceof InterruptedException) Thread.currentThread().interrupt();
                failure = thrown instanceof BaseException ? thrown : ExecutionFailures.afterStart(
                        thrown instanceof InterruptedException ? CommonErrorCode.INTERRUPTED : CommonErrorCode.INTERNAL_ERROR, context, "execution", thrown);
            }
            synchronized (this) {
                // 返回值与停止请求在同一临界区决出本地终态；取消请求不能提前完成 future。
                if (failure == null) {
                    try {
                        checkpoint.check();
                    } catch (BaseException stop) {
                        failure = stop;
                    }
                }
                if (failure instanceof BaseException base && CommonErrorCode.DEADLINE_EXCEEDED.code().equals(base.error().code()))
                    state = TaskState.TIMED_OUT;
                else if (failure instanceof BaseException base && CommonErrorCode.INTERRUPTED.code().equals(base.error().code()))
                    state = TaskState.CANCELLED;
                else state = failure == null ? TaskState.SUCCEEDED : TaskState.FAILED;
            }
            finish(value, failure);
        }

        void finish(T value, Throwable failure) {
            synchronized (this) {
                if (deadline != null) deadline.cancel(false);
            }
            live.remove(this);
            if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
        }
    }
}
