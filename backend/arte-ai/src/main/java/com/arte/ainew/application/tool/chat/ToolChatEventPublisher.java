package com.arte.ainew.application.tool.chat;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiToolChatProperties;
import com.arte.ainew.pojo.execution.OutboxMessage;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.core.diagnostics.SafeExceptionDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立 RUN_EVENT Outbox 消费，发布本机有界提示后 ACK；查询／跨节点恢复始终补读 Run 耐久游标。
 * 提示不携带正文、不冒充 Invocation 事件，也不授予读取权；S4-05 不提供 Run SSE。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@Slf4j
public final class ToolChatEventPublisher implements SmartLifecycle {

    public record Notice(ExecutionOwner owner, String runId, long sequence) {
    }

    private final ExecutionOutboxStore outbox;
    private final ToolChatRunStore runs;
    private final NewAiToolChatProperties properties;
    private final Scheduler scheduler;
    private final boolean autoStart;
    private final String worker = "tool-chat-events-" + UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Sinks.Many<Notice> notices = Sinks.many().multicast().directBestEffort();
    private volatile Disposable loop;

    public ToolChatEventPublisher(ExecutionOutboxStore outbox, ToolChatRunStore runs, NewAiToolChatProperties properties,
                                  Scheduler scheduler, boolean executionWorkerEnabled) {
        this.outbox = outbox;
        this.runs = runs;
        this.properties = properties;
        this.scheduler = scheduler;
        autoStart = executionWorkerEnabled && properties.workerEnabled();
    }

    public Flux<Notice> notices() {
        return notices.asFlux().onBackpressureLatest();
    }

    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            if (!polling.compareAndSet(false, true)) return Mono.just(0);
            return outbox.claim(OutboxMessage.Kind.RUN_EVENT, worker, properties.lease(), 128).flatMapMany(Flux::fromIterable)
                    .concatMap(message -> outbox.validateClaim(message).flatMap(claim -> {
                        if (!claim.successful()) return Mono.just(0);
                        return runs.events(message.owner(), message.executionId(), message.eventSequence() - 1, 1).flatMap(events -> {
                            if (events.size() != 1 || events.getFirst().sequence() != message.eventSequence())
                                return Mono.error(new IllegalStateException("Run notification source missing"));
                            notices.tryEmitNext(new Notice(message.owner(), message.executionId(), message.eventSequence()));
                            return outbox.acknowledge(message).map(ack -> ack.successful() ? 1 : 0);
                        });
                    }).onErrorResume(error -> {
                        log.error("AI tool chat notification deferred, runId={}, sequence={}, messageId={}, type={}",
                                message.executionId(), message.eventSequence(), message.messageId(), error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
                        return Mono.just(0);
                    }), 1).reduce(0, Integer::sum).doFinally(signal -> polling.set(false));
        });
    }

    @Override
    public synchronized void start() {
        if (isRunning()) return;
        loop = Flux.interval(java.time.Duration.ZERO, properties.pollInterval(), scheduler).onBackpressureLatest()
                .concatMap(tick -> pollOnce().onErrorResume(error -> {
                    log.error("AI tool chat event claim deferred, workerId={}, type={}", worker, error.getClass().getName(), SafeExceptionDiagnostics.forLogging(error));
                    return Mono.just(0);
                }), 1).subscribe();
    }

    @Override
    public synchronized void stop() {
        if (loop != null) {
            loop.dispose();
            loop = null;
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null && !loop.isDisposed();
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }
}
