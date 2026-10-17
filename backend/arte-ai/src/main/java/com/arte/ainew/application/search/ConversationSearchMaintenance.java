package com.arte.ainew.application.search;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchSync.Status;
import com.arte.ainew.spi.search.ConversationSearchIndexLock;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * 内部运维入口，不开放用户 HTTP；不自动删除旧代际。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ConversationSearchMaintenance {

    private final ConversationSearchIndexLock indexLock;
    private final ConversationSearchWriter writer;
    private final ConversationSearchSyncStore store;
    private final NewAiSearchProperties properties;
    private final Scheduler scheduler;
    private final ConversationSearchSyncWorker worker;

    public ConversationSearchMaintenance(ConversationSearchWriter writer, ConversationSearchSyncStore store, NewAiSearchProperties properties, Scheduler scheduler, ConversationSearchSyncWorker worker) {
        this(writer, store, properties, scheduler, worker, null);
    }

    public ConversationSearchMaintenance(ConversationSearchWriter writer, ConversationSearchSyncStore store, NewAiSearchProperties properties, Scheduler scheduler, ConversationSearchSyncWorker worker, ConversationSearchIndexLock indexLock) {
        this.writer = writer;
        this.store = store;
        this.properties = properties;
        this.scheduler = scheduler;
        this.worker = worker;
        this.indexLock = indexLock;
    }

    public Mono<Void> create(String generation) {
        return Mono.<Void>fromCallable(() -> {
            writer.createGeneration(generation);
            store.register(generation, properties.index().rulesVersion());
            return null;
        }).subscribeOn(scheduler);
    }

    /**
     * 显式推进指定新代际；多实例仍由数据库租约串行仲裁。
     */
    public Mono<Status> advance(String generation) {
        return Mono.fromCallable(() -> {
            worker.tick(generation);
            return store.status(generation);
        }).subscribeOn(scheduler);
    }

    public Mono<Status> status(String generation) {
        return Mono.fromCallable(() -> store.status(generation)).subscribeOn(scheduler);
    }

    /**
     * 创建一次性校准凭据；Worker 完整扫描和校准后才能切换（回退也一样）。
     */
    public Mono<Long> prepareActivation(String generation) {
        return Mono.fromCallable(() -> {
            store.register(generation, properties.index().rulesVersion());
            var lease = store.acquire(generation, "prepare-" + java.util.UUID.randomUUID(), properties.sync().lease());
            if (lease == null) throw new IllegalStateException("Generation is being scanned");
            try {
                return store.prepareActivation(lease);
            } finally {
                store.release(lease);
            }
        }).subscribeOn(scheduler);
    }

    public Mono<Void> activate(String generation, long validationEpoch) {
        return Mono.<Void>fromCallable(() -> {
            if (indexLock != null) return indexLock.locked(() -> activateLocked(generation, validationEpoch));
            return activateLocked(generation, validationEpoch);
        }).subscribeOn(scheduler);
    }

    private Void activateLocked(String generation, long validationEpoch) throws Exception {
        var lease = store.acquire(generation, "activate-" + java.util.UUID.randomUUID(), properties.sync().lease());
        if (lease == null) throw new IllegalStateException("Generation is being scanned");
        try {
            if (!"READY".equals(store.status(generation).state()))
                throw new IllegalStateException("Backfill and reconciliation must complete before alias switch");
            store.consumeActivation(lease, validationEpoch);
            writer.activateGeneration(generation);
        } finally {
            store.release(lease);
        }
        return null;
    }
}
