package com.arte.ainew.application.tool.approval;

import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 有界、串行的审批到期恢复；每批完成后延迟再扫描，不堆积定时任务，单批失败不停止恢复。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
@Slf4j
public final class ToolApprovalRecovery implements SmartLifecycle {

    private final ToolApprovalStore store;
    private final NewAiToolApprovalProperties properties;
    private final Scheduler scheduler;
    private Disposable loop;

    public ToolApprovalRecovery(ToolApprovalStore store, NewAiToolApprovalProperties properties, Scheduler scheduler) {
        this.store = store;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @Override
    public synchronized void start() {
        if (isRunning() || !properties.enabled()) return;
        loop = Mono.defer(() -> store.expire(properties.batchSize())).onErrorResume(error -> {
            log.warn("AI tool approval recovery failed, type={}", error.getClass().getName());
            return Mono.just(0);
        }).repeatWhen(completed -> completed.delayElements(properties.pollInterval(), scheduler)).subscribe();
    }

    @Override
    public synchronized void stop() {
        if (loop != null) loop.dispose();
        loop = null;
    }

    @Override
    public synchronized boolean isRunning() {
        return loop != null && !loop.isDisposed();
    }
}
