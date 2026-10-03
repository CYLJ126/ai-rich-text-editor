package com.arte.app.ainew;

import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.model.generation.ModelResult;
import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.execution.TaskHandle;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionPermit;
import com.arte.base.model.admission.AdmissionPriority;
import com.arte.base.model.admission.AdmissionRequest;
import com.arte.base.spi.observability.Telemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据库队列消费者。先持久化受理，后异步准入；关闭时保持租约直到真实退出或围栏失效。
 */
public final class DurableModelWorker implements SmartLifecycle {
    private record Active(JdbcModelWorkQueue.Lease lease, TaskHandle<ModelResult> task) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(DurableModelWorker.class);
    private final JdbcModelWorkQueue queue;
    private final InvocationCoordinator coordinator;
    private final BoundedTaskExecutor executor;
    private final LocalAdmissionController admission;
    private final Telemetry telemetry;
    private final int threads;
    private final Duration poll, grace;
    private final ConcurrentMap<String, Active> live = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "arte-model-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean signalled = new AtomicBoolean();
    private volatile boolean running, stopping;
    private boolean owned;
    private long nextMaintenance;
    private long nextWarning;

    public DurableModelWorker(JdbcModelWorkQueue queue, InvocationCoordinator coordinator, BoundedTaskExecutor executor,
                              LocalAdmissionController admission, Telemetry telemetry, int threads, Duration poll, Duration grace) {
        if (threads < 1 || poll.compareTo(Duration.ofMillis(50)) < 0 || grace.isNegative() || grace.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("invalid worker lifecycle settings");
        this.queue = queue;
        this.coordinator = coordinator;
        this.executor = executor;
        this.admission = admission;
        this.telemetry = telemetry;
        this.threads = threads;
        this.poll = poll;
        this.grace = grace;
        queue.onAvailable(this::signal);
    }

    @Override
    public void start() {
        if (running) return;
        owned = queue.acquire(); // 缺表会明确启动失败；已有活跃租约时等待而不派发。
        running = true;
        timer.scheduleWithFixedDelay(this::tick, 0, poll.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void signal() {
        if (!running || stopping || !signalled.compareAndSet(false, true)) return;
        try {
            timer.execute(() -> {
                try {
                    tick();
                } finally {
                    signalled.set(false);
                }
            });
        } catch (RejectedExecutionException shutdown) {
            signalled.set(false);
        }
    }

    private void tick() {
        if (!running) return;
        try {
            if (stopping) {
                if (owned) queue.heartbeat();
                return;
            }
            long now = System.nanoTime();
            if (now >= nextMaintenance || !owned) {
                if (owned && !queue.heartbeat()) {
                    owned = false;
                    live.values().forEach(item -> item.task().requestCancellation());
                }
                if (!owned) {
                    if (!live.isEmpty()) return;
                    owned = queue.acquire();
                    if (!owned) return;
                }
                queue.recover();
                nextMaintenance = now + poll.multipliedBy(2).toNanos();
            }
            for (var item : live.values()) if (queue.cancelled(item.lease())) item.task().requestCancellation();
            for (int i = 0; i < threads && live.size() < threads && !stopping && queue.hasReady(); i++) {
                // 不在 HTTP 或 Turn 行锁中等待准入；数据库速率窗口跨重启保留。
                var candidate = queue.candidateContext();
                if (candidate.isEmpty()) break;
                AdmissionPermit permit;
                try {
                    permit = admission.acquire(new AdmissionRequest(candidate.get(), new AdmissionKey(candidate.get().scope().tenantId(), "ai.interactive", null, null),
                            AdmissionPriority.INTERACTIVE, Duration.ZERO)).completion().toCompletableFuture().join();
                } catch (CompletionException deferred) {
                    telemetry.increment("arte.ai.queue.deferred", 1, Map.of(Telemetry.Label.OPERATION, "admission"));
                    break;
                }
                Optional<JdbcModelWorkQueue.Lease> claimed;
                try {
                    claimed = queue.claim();
                } catch (RuntimeException failed) {
                    permit.close();
                    throw failed;
                }
                if (claimed.isEmpty()) {
                    permit.close();
                    break;
                }
                var lease = claimed.get();
                var store = queue.fenced(lease);
                TaskHandle<ModelResult> task;
                try {
                    task = executor.submit(lease.call().workerContext(), checkpoint -> {
                        var elapsed = Duration.between(lease.queuedAt(), Instant.now());
                        telemetry.duration("arte.ai.execution.queue.wait", elapsed.isNegative() ? Duration.ZERO : elapsed,
                                Map.of(Telemetry.Label.COMPONENT, "ai-new", Telemetry.Label.OPERATION, "model.execute"));
                        return coordinator.executeQueued(lease.execution(), lease.call(), store, checkpoint);
                    });
                } catch (RuntimeException failed) {
                    try {
                        coordinator.queuedFailure(lease.execution(), lease.call(), store, failed);
                    } finally {
                        permit.close();
                    }
                    throw failed;
                }
                var active = new Active(lease, task);
                String id = lease.execution().executionId();
                live.put(id, active);
                task.completion().whenComplete((result, failure) -> {
                    try {
                        if (failure != null) coordinator.queuedFailure(lease.execution(), lease.call(), store, failure);
                    } catch (RuntimeException unavailable) {
                        LOG.warn("model execution requires recovery: executionId={}", id);
                    } finally {
                        live.remove(id, active);
                        permit.close();
                        signal();
                    }
                });
            }
        } catch (RuntimeException unavailable) {
            telemetry.increment("arte.ai.worker.errors", 1, Map.of(Telemetry.Label.OPERATION, "maintenance"));
            if (System.nanoTime() >= nextWarning) {
                nextWarning = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                LOG.warn("durable model worker temporarily unavailable; dispatch remains fenced");
            }
        }
    }

    @Override
    public void stop() {
        if (!running || stopping) return;
        stopping = true;
        try {
            try {
                queue.drain();
            } catch (RuntimeException unavailable) {
                LOG.warn("worker draining could not persist; local admission is closed");
            }
            executor.shutdownGracefully(grace);
        } finally {
            try {
                queue.release();
            } catch (RuntimeException unavailable) {
                LOG.warn("worker lease release could not persist; expiry will fence remaining work");
            } finally {
                running = false;
                timer.shutdownNow();
            }
        }
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
