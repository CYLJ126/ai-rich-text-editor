package com.arte.core.diagnostics;

import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.TimeUnit;

/**
 * 排队时间从任务提交至实际执行；延迟任务扣除计划延迟，不把定时器间隔当作线程排队。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 09:41 ✾
 */
public final class ObservedScheduler implements Scheduler {
    private final Scheduler delegate;
    private final String name;

    public ObservedScheduler(Scheduler delegate, String name) {
        this.delegate = delegate;
        this.name = name;
    }

    private Runnable wrap(Runnable task, long delay, TimeUnit unit) {
        var observation = RequestObservation.current();
        long due = System.nanoTime() + unit.toNanos(delay);
        return () -> {
            try (var ignored = RequestObservation.bind(observation)) {
                if (observation != null) observation.queued(name, Math.max(0, System.nanoTime() - due));
                task.run();
            }
        };
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task) {
        return delegate.schedule(wrap(task, 0, TimeUnit.NANOSECONDS));
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit unit) {
        return delegate.schedule(wrap(task, delay, unit), delay, unit);
    }

    @Override
    public @NonNull Disposable schedulePeriodically(@NonNull Runnable task, long delay, long period, @NonNull TimeUnit unit) {
        return delegate.schedulePeriodically(periodic(task, delay, period, unit), delay, period, unit);
    }

    private Runnable periodic(Runnable task, long delay, long period, TimeUnit unit) {
        var observation = RequestObservation.current();
        var due = new java.util.concurrent.atomic.AtomicLong(System.nanoTime() + unit.toNanos(delay));
        return () -> {
            try (var ignored = RequestObservation.bind(observation)) {
                if (observation != null)
                    observation.queued(name, Math.max(0, System.nanoTime() - due.getAndAdd(unit.toNanos(period))));
                task.run();
            }
        };
    }

    @Override
    public @NonNull Worker createWorker() {

        var worker = delegate.createWorker();

        return new Worker() {
            @Override
            public @NonNull Disposable schedule(@NonNull Runnable task) {
                return worker.schedule(wrap(task, 0, TimeUnit.NANOSECONDS));
            }

            @Override
            public @NonNull Disposable schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit unit) {
                return worker.schedule(wrap(task, delay, unit), delay, unit);
            }

            @Override
            public @NonNull Disposable schedulePeriodically(@NonNull Runnable task, long delay, long period, @NonNull TimeUnit unit) {
                return worker.schedulePeriodically(periodic(task, delay, period, unit), delay, period, unit);
            }

            @Override
            public void dispose() {
                worker.dispose();
            }

            @Override
            public boolean isDisposed() {
                return worker.isDisposed();
            }
        };
    }

    @Override
    public long now(@NonNull TimeUnit unit) {
        return delegate.now(unit);
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    @Override
    public boolean isDisposed() {
        return delegate.isDisposed();
    }
}
