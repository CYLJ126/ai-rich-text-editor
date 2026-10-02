package com.arte.base.admission;

import com.arte.base.api.admission.AdmissionController;
import com.arte.base.execution.ExecutionFailures;
import com.arte.base.model.admission.*;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单实例并发／固定窗口速率与有界优先队列。分区必须预登记，不提供多实例全局配额。
 */
public final class LocalAdmissionController implements AdmissionController, AutoCloseable {
    private final Object lock = new Object();
    private final Map<AdmissionKey, Bucket> buckets = new HashMap<>();
    private final List<Pending> queued = new ArrayList<>();
    private final int maxConcurrent, maxQueued;
    private final Clock clock;
    private final ScheduledThreadPoolExecutor timer;
    private ScheduledFuture<?> wakeup;
    private Instant lastNow;
    private int active;
    private long sequence;
    private boolean closed;

    public LocalAdmissionController(int maxConcurrent, int maxQueued, Map<AdmissionKey, AdmissionLimits> policies, Clock clock) {
        if (maxConcurrent <= 0 || maxQueued < 0) throw new IllegalArgumentException("invalid admission capacity");
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        this.clock = ContractChecks.required(clock, "clock");
        ContractChecks.required(policies, "policies").forEach((key, limits) -> buckets.put(ContractChecks.required(key, "key"), new Bucket(ContractChecks.required(limits, "limits"))));
        timer = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "arte-admission");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
    }

    @Override
    public AdmissionAttempt acquire(AdmissionRequest request) {
        ContractChecks.required(request, "request");
        var completions = new ArrayList<Runnable>();
        CompletableFuture<AdmissionPermit> result = new CompletableFuture<>();
        synchronized (lock) {
            Instant now = clock.instant();
            var bucket = buckets.get(request.key());
            CommonErrorCode rejection = null;
            if (closed) rejection = CommonErrorCode.BUSY;
            else if (bucket == null || (lastNow != null && now.isBefore(lastNow)))
                rejection = CommonErrorCode.POLICY_UNAVAILABLE;
            else if (request.context().isExpiredAt(now)) rejection = CommonErrorCode.DEADLINE_EXCEEDED;
            if (rejection != null) fail(result, request, rejection, completions);
            else {
                lastNow = now;
                drain(now, completions);
                if (queued.isEmpty() && available(bucket, now))
                    admit(new Pending(request, bucket, now, sequence++, result), completions);
                else if (request.maxWait().isZero() || queued.size() >= maxQueued)
                    fail(result, request, rateAvailable(bucket, now) ? CommonErrorCode.BUSY : CommonErrorCode.RATE_LIMITED, completions);
                else {
                    Instant until = now.plus(request.maxWait());
                    if (request.context().deadline() != null && request.context().deadline().isBefore(until))
                        until = request.context().deadline();
                    queued.add(new Pending(request, bucket, until, sequence++, result));
                    result.whenComplete((permit, failure) -> {
                        if (result.isCancelled()) refresh();
                    });
                    drain(now, completions);
                }
                schedule(now);
            }
        }
        completions.forEach(Runnable::run);
        // 等待可取消；授予并发许可与 cancel 的竞态由 admit 完成失败时归还许可。
        return new AdmissionAttempt(result);
    }

    private boolean rateAvailable(Bucket bucket, Instant now) {
        if (bucket.windowEnd == null || !now.isBefore(bucket.windowEnd)) {
            bucket.starts = 0;
            bucket.windowEnd = now.plus(bucket.limits.window());
        }
        return bucket.starts < bucket.limits.startsPerWindow();
    }

    private boolean available(Bucket bucket, Instant now) {
        return active < maxConcurrent && bucket.active < bucket.limits.maxConcurrent() && rateAvailable(bucket, now);
    }

    private void admit(Pending pending, List<Runnable> completions) {
        active++;
        pending.bucket.active++;
        pending.bucket.starts++;
        var permit = new Permit(pending);
        completions.add(() -> {
            if (!pending.result.complete(permit)) permit.close();
        });
    }

    private void fail(CompletableFuture<AdmissionPermit> result, AdmissionRequest request, CommonErrorCode code, List<Runnable> completions) {
        completions.add(() -> result.completeExceptionally(ExecutionFailures.beforeStart(code, request.context(), "admission")));
    }

    private void drain(Instant now, List<Runnable> completions) {
        queued.sort(Comparator.comparing((Pending p) -> p.request.priority()).thenComparingLong(p -> p.sequence));
        var iterator = queued.iterator();
        while (iterator.hasNext()) {
            var pending = iterator.next();
            if (pending.result.isCancelled()) {
                iterator.remove();
                continue;
            }
            if (!now.isBefore(pending.until)) {
                iterator.remove();
                fail(pending.result, pending.request, pending.request.context().isExpiredAt(now) ? CommonErrorCode.DEADLINE_EXCEEDED : CommonErrorCode.BUSY, completions);
            } else if (available(pending.bucket, now)) {
                iterator.remove();
                admit(pending, completions);
            }
        }
    }

    private void schedule(Instant now) {
        if (wakeup != null) wakeup.cancel(false);
        if (closed || queued.isEmpty()) return;
        Instant next = queued.stream().map(p -> p.until).min(Instant::compareTo).orElseThrow();
        for (var pending : queued) {
            if (!rateAvailable(pending.bucket, now) && pending.bucket.windowEnd.isBefore(next))
                next = pending.bucket.windowEnd;
        }
        long delay;
        try {
            delay = Math.max(1, Duration.between(now, next).toNanos());
        } catch (ArithmeticException tooFar) {
            delay = Long.MAX_VALUE;
        }
        wakeup = timer.schedule(this::refresh, delay, TimeUnit.NANOSECONDS);
    }

    private void refresh() {
        var completions = new ArrayList<Runnable>();
        synchronized (lock) {
            if (closed) return;
            Instant now = clock.instant();
            if (lastNow != null && now.isBefore(lastNow)) {
                queued.forEach(p -> fail(p.result, p.request, CommonErrorCode.POLICY_UNAVAILABLE, completions));
                queued.clear();
            } else {
                lastNow = now;
                drain(now, completions);
                schedule(now);
            }
        }
        completions.forEach(Runnable::run);
    }

    @Override
    public void close() {
        var completions = new ArrayList<Runnable>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            queued.forEach(p -> fail(p.result, p.request, CommonErrorCode.BUSY, completions));
            queued.clear();
            timer.shutdownNow();
        }
        completions.forEach(Runnable::run);
    }

    private final class Permit implements AdmissionPermit {
        final Pending pending;
        final AtomicBoolean released = new AtomicBoolean();

        Permit(Pending pending) {
            this.pending = pending;
        }

        @Override
        public AdmissionRequest request() {
            return pending.request;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            synchronized (lock) {
                active--;
                pending.bucket.active--;
            }
            refresh();
        }
    }

    private static final class Bucket {
        final AdmissionLimits limits;
        int active, starts;
        Instant windowEnd;

        Bucket(AdmissionLimits limits) {
            this.limits = limits;
        }
    }

    private record Pending(AdmissionRequest request, Bucket bucket, Instant until, long sequence,
                           CompletableFuture<AdmissionPermit> result) {
    }
}
