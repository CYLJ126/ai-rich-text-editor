package com.arte.ainew.application.tool.outcome;

import com.arte.ainew.config.NewAiToolOutcomeProperties;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.spi.persistence.ToolOutcomeStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 独立的有界收尾 Worker；无 invoke 依赖，查询额度与租约由数据库决定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
@Slf4j
public final class ToolOutcomeWorker implements SmartLifecycle {

    private final ToolOutcomeStore store;
    private final ToolOutcomeQueryRegistry registry;
    private final NewAiToolOutcomeProperties properties;
    private final Scheduler io;
    private final Clock clock;
    private final boolean automatic;
    private final String workerId = "tool-outcome-" + UUID.randomUUID();
    private final AtomicBoolean polling = new AtomicBoolean();
    private Disposable loop;

    public ToolOutcomeWorker(ToolOutcomeStore store, ToolOutcomeQueryRegistry registry, NewAiToolOutcomeProperties properties, Scheduler io, Clock clock, boolean automatic) {
        this.store = store;
        this.registry = registry;
        this.properties = properties;
        this.io = io;
        this.clock = clock;
        this.automatic = automatic;
    }

    public Mono<Integer> pollOnce() {
        return Mono.defer(() -> {
            if (!properties.enabled() || !polling.compareAndSet(false, true)) return Mono.just(0);
            return store.recoverUnknownBatch().then(store.alertReviews()).then(store.claim(workerId, properties.batchSize())).flatMapMany(Flux::fromIterable).flatMap(claim -> process(claim).onErrorResume(error -> {
                        log.warn("AI_TOOL_OUTCOME work failed invocationId={} type={}", claim.work().invocationId(), error.getClass().getSimpleName());
                        return Mono.just(0);
                    }), properties.batchSize(), 1)
                    .reduce(0, Integer::sum).doOnSuccess(ignored -> polling.set(false)).doOnError(ignored -> polling.set(false)).doOnCancel(() -> polling.set(false));
        });
    }

    private Mono<Integer> process(ToolOutcomeCheck.Claim claim) {
        var unknown = ToolOutcomeEvidence.unknown(claim);
        var deadline = Instant.ofEpochMilli(claim.work().queryDeadline());
        var remaining = Duration.between(clock.instant(), deadline);
        Mono<ToolOutcomeEvidence> query;
        var adapter = registry.find(claim.snapshot());
        if (adapter.isEmpty()) return store.finish(claim, unknown, "OUTCOME_QUERY_UNSUPPORTED", true).thenReturn(1);
        if (remaining.isNegative() || remaining.isZero())
            return store.finish(claim, unknown, "OUTCOME_QUERY_INTERRUPTED", false).thenReturn(1);
        query = Mono.defer(() -> adapter.orElseThrow().query(claim)).switchIfEmpty(Mono.error(new IllegalStateException("Empty outcome query")))
                .timeout(remaining).onErrorResume(error -> {
                    log.warn("AI_TOOL_OUTCOME query failed invocationId={} checkId={} type={}", claim.work().invocationId(), claim.checkId(), error.getClass().getSimpleName());
                    return error instanceof IllegalArgumentException ? Mono.error(error) : Mono.just(unknown);
                });
        return query.flatMap(evidence -> store.finish(claim, evidence, evidence.decision().name(), false))
                .onErrorResume(error -> {
                    log.warn("AI_TOOL_OUTCOME commit failed invocationId={} checkId={} type={}", claim.work().invocationId(), claim.checkId(), error.getClass().getSimpleName());
                    if (error instanceof org.springframework.dao.DataAccessException) return Mono.just(false);
                    return store.finish(claim, unknown, "OUTCOME_EVIDENCE_REVIEW", true).onErrorResume(failure -> Mono.just(false));
                }).map(done -> done ? 1 : 0);
    }

    @Override
    public synchronized void start() {
        if (isRunning() || !automatic || !properties.enabled()) return;
        loop = Mono.defer(this::pollOnce).onErrorResume(error -> {
                    log.warn("AI_TOOL_OUTCOME poll failed type={}", error.getClass().getSimpleName());
                    return Mono.just(0);
                })
                .repeatWhen(done -> done.delayElements(properties.pollInterval(), io)).subscribe();
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
