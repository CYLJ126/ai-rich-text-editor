package com.arte.ainew.application.tool.chat;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.config.NewAiToolChatProperties;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.core.diagnostics.SafeExceptionDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 耐久 Run 工作项领取；进程内 tick 可合并，数据库租约及固定子身份决定恢复与防重。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@Slf4j
public final class ToolChatWorker implements SmartLifecycle {

    private final ToolChatRunStore runs;
    private final ToolChatCoordinator coordinator;
    private final NewAiToolChatProperties properties;
    private final Scheduler scheduler;
    private final boolean autoStart;
    private final String workerId = "tool-chat-" + UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile Disposable subscription;

    public ToolChatWorker(ToolChatRunStore runs, ToolChatCoordinator coordinator, NewAiToolChatProperties properties,
                          Scheduler scheduler, boolean executionWorkerEnabled) {
        this.runs = runs;
        this.coordinator = coordinator;
        this.properties = properties;
        this.scheduler = scheduler;
        autoStart = properties.workerEnabled() && executionWorkerEnabled;
    }

    /**
     * 手动完整轮询用于定向验收；同一实例重叠轮询不累积排队。
     */
    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            if (!polling.compareAndSet(false, true)) return Mono.just(0);
            return runs.claim(workerId, properties.batchSize(), properties.lease()).flatMap(leases -> Flux.fromIterable(leases)
                            .flatMap(lease -> coordinator.process(lease).timeout(properties.lease().dividedBy(2))
                                    .onErrorResume(error -> {
                                        if (error instanceof AdmissionException || error instanceof java.util.concurrent.TimeoutException
                                                || error instanceof org.springframework.security.access.AccessDeniedException) {
                                            log.warn("AI tool chat checkpoint deferred, runId={}, fence={}, leaseUntil={}, checkpointTimeoutMs={}, code={}, type={}",
                                                    lease.runId(), lease.fence(), lease.until(), properties.lease().dividedBy(2).toMillis(),
                                                    error instanceof AdmissionException rejected ? rejected.getResultCode() : null, error.getClass().getName());
                                        } else {
                                            log.error("AI tool chat checkpoint failed, runId={}, fence={}, leaseUntil={}, type={}",
                                                    lease.runId(), lease.fence(), lease.until(), error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
                                        }
                                        // 基础设施故障由租约到期重领；不伪造业务失败或重新分配子调用身份。
                                        return Mono.empty();
                                    }), properties.batchSize()).then(Mono.just(leases.size())))
                    .doFinally(signal -> polling.set(false));
        });
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        subscription = Flux.interval(java.time.Duration.ZERO, properties.pollInterval(), scheduler).onBackpressureLatest()
                .concatMap(tick -> pollOnce().onErrorResume(error -> {
                    log.error("AI tool chat claim failed, workerId={}, type={}", workerId, error.getClass().getName(),
                            SafeExceptionDiagnostics.forLogging(error));
                    return Mono.just(0);
                }), 1).subscribe();
    }

    @Override
    public synchronized void stop() {
        if (subscription != null) {
            subscription.dispose();
            subscription = null;
        }
    }

    @Override
    public boolean isRunning() {
        return subscription != null && !subscription.isDisposed();
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }
}
