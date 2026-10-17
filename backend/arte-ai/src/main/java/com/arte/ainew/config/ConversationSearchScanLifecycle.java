package com.arte.ainew.config;

import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.infrastructure.search.ConversationSearchBootstrap;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 管理会话搜索初始化及后台扫描的启停，应用就绪后运行，销毁时停止任务。
 * <p>
 * 初始化失败退避重试，扫描失败由后续 tick 恢复；不消费执行 Outbox，不记录源正文或异常参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
@Slf4j
public final class ConversationSearchScanLifecycle {

    private final ConversationSearchSyncWorker worker;
    private final NewAiSearchProperties properties;
    private final Scheduler scheduler;
    private final ConversationSearchBootstrap bootstrap;
    private Disposable running;
    private long lastReport;
    private String lastState;
    private volatile boolean completed;
    private int initializationFailures;
    private long ticks, leaseSkips, tickElapsedMs, scanned, processed, changed, written;

    public ConversationSearchScanLifecycle(ConversationSearchSyncWorker worker, NewAiSearchProperties properties, Scheduler scheduler) {
        this(worker, properties, scheduler, null);
    }

    public ConversationSearchScanLifecycle(ConversationSearchSyncWorker worker, NewAiSearchProperties properties, Scheduler scheduler,
                                           ConversationSearchBootstrap bootstrap) {
        this.worker = worker;
        this.properties = properties;
        this.scheduler = scheduler;
        this.bootstrap = bootstrap;
    }

    /**
     * 应用就绪后启动后台循环并防止重复启动，先完成索引初始化，再按开关推进固定代际的扫描、回填或校准。
     * 定期记录同步状态；初始化失败退避重试，扫描失败在后续周期继续。
     * 未开启扫描类任务时，初始化完成即停止；仅开启回填时，目标代际达到 READY 后停止。
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (running != null && !running.isDisposed()) return;
        if (properties.initializeOnStartup() && bootstrap == null)
            throw new IllegalStateException("Search initializer is required");
        completed = false;
        running = Mono.defer(() -> Mono.fromRunnable(() -> {
                            if (bootstrap != null && !bootstrap.isReady()) {
                                try {
                                    bootstrap.initialize();
                                    initializationFailures = 0;
                                    log.info("AI search initialization ready generation={}", properties.sync().generation());
                                } catch (Exception failure) {
                                    initializationFailures = Math.min(30, initializationFailures + 1);
                                    throw new IllegalStateException(failure);
                                }
                            }
                            if (!properties.scanEnabled() && !properties.backfillEnabled() && !properties.reconcileEnabled()) {
                                completed = true;
                                return;
                            }
                            var tick = worker.tick(properties.sync().generation());
                            ticks++;
                            if (!tick.acquired()) leaseSkips++;
                            tickElapsedMs += tick.elapsedMs();
                            scanned += tick.scanned();
                            processed += tick.processed();
                            changed += tick.changed();
                            written += tick.written();
                            boolean backfillOnly = properties.backfillEnabled() && !properties.scanEnabled() && !properties.reconcileEnabled();
                            if (backfillOnly && "READY".equals(worker.status(properties.sync().generation()).state())) completed = true;
                            if (System.currentTimeMillis() - lastReport >= 60000) {
                                var status = worker.status(properties.sync().generation());
                                log.info("AI search scan generation={}, state={}, pending={}, failures={}, missing={}, rounds={}, ticks={}, leaseSkips={}, tickElapsedMs={}, lastTickMs={}, scanned={}, processed={}, changed={}, written={}, nextDelayMs={}, nextRunAt={}",
                                        status.generation(), status.state(), status.pending(), status.failures(), status.missingResults(), status.completedRounds(),
                                        ticks, leaseSkips, tickElapsedMs, tick.elapsedMs(), scanned, processed, changed, written,
                                        completed ? null : properties.sync().tick().toMillis(), completed ? null : java.time.Instant.now().plus(properties.sync().tick()));
                                ticks = leaseSkips = tickElapsedMs = scanned = processed = changed = written = 0;
                                lastReport = System.currentTimeMillis();
                                lastState = status.state();
                            }
                        }).subscribeOn(scheduler)
                        .onErrorResume(error -> {
                            if (System.currentTimeMillis() - lastReport >= 60000 || !"ERROR".equals(lastState)) {
                                Throwable cause = error.getCause() == null ? error : error.getCause();
                                String code = cause instanceof ConversationSearchBootstrap.InitializationFailure ? cause.getMessage() : cause.getClass().getSimpleName();
                                log.warn("AI search {} failed generation={}, code={}", bootstrap != null && !bootstrap.isReady() ? "initialization" : "scan tick",
                                        properties.sync().generation(), code);
                                lastReport = System.currentTimeMillis();
                                lastState = "ERROR";
                            }
                            return Mono.empty();
                        })
                        .then(Mono.defer(() -> Mono.delay(initializationFailures == 0 ? properties.sync().tick() :
                                java.time.Duration.ofMillis(Math.min(properties.sync().retryMax().toMillis(),
                                        properties.sync().retryInitial().toMillis() * (1L << (initializationFailures - 1))))))))
                .repeat(() -> !completed).subscribe();
    }

    @PreDestroy
    public synchronized void stop() {
        if (running != null) running.dispose();
    }
}
