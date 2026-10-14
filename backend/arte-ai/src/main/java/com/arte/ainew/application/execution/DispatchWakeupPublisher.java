package com.arte.ainew.application.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 提交后的非耐久唤醒广播；不阻塞受理事务，失败由数据库恢复扫描兜底。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 20:06 ✾
 */
@Slf4j
public final class DispatchWakeupPublisher implements SmartLifecycle {
    private final LocalDispatchNotifier notifier;
    private final ExecutionEventBroadcast broadcast;
    private final Scheduler scheduler;
    private Disposable loop;
    private volatile boolean running;

    public DispatchWakeupPublisher(LocalDispatchNotifier notifier, ExecutionEventBroadcast broadcast, Scheduler scheduler) {
        this.notifier = notifier;
        this.broadcast = broadcast;
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        loop = notifier.commits().onBackpressureLatest().publishOn(scheduler, 1)
                .concatMap(ignored -> Mono.defer(broadcast::publishDispatchWakeup).onErrorResume(error -> {
                    log.warn("AI dispatch wakeup broadcast failed; recovery scan will recover, type={}", error.getClass().getName());
                    return Mono.empty();
                }), 1)
                .subscribe(ignored -> {
                }, error -> {
                    running = false;
                    log.error("AI dispatch wakeup publisher stopped unexpectedly, type={}", error.getClass().getName());
                });
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (loop != null) loop.dispose();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 仅受理节点也必须广播；不受本机 workerEnabled 开关限制。
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
