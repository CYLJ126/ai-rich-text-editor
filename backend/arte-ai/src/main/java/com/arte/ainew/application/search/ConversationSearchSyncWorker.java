package com.arte.ainew.application.search;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.search.ConversationSearchSource;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.*;

/**
 * 会话搜索后台同步执行器
 * <p>
 * 协调源数据扫描、差异比对和 ES 写入，处理回填、校准、归档清理及失败重试。
 * 同代际通过租约串行处理；领取后读取源数据，失去租约后不能分配同步版本或确认进度。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ConversationSearchSyncWorker {

    private final ConversationSearchSource source;
    private final ConversationSearchSyncStore store;
    private final ConversationSearchWriter writer;
    private final NewAiSearchProperties config;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String worker = UUID.randomUUID().toString();
    private final java.util.concurrent.atomic.AtomicInteger lane = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 单轮内存观测；changed 为持久同步版本变化数，written 为已写入并确认的 bulk 条目数。
     */
    public record TickResult(long elapsedMs, boolean acquired, int scanned, int processed, int changed, int written) {
    }

    private static final class TickMetrics {
        final long started = System.nanoTime();
        int scanned, processed, changed, written;

        TickResult result(boolean acquired) {
            return new TickResult((System.nanoTime() - started) / 1_000_000, acquired, scanned, processed, changed, written);
        }
    }

    public Status status(String generation) {
        return store.status(generation);
    }

    public ConversationSearchSyncWorker(ConversationSearchSource source, ConversationSearchSyncStore store, ConversationSearchWriter writer, NewAiSearchProperties config) {
        this.source = Objects.requireNonNull(source);
        this.store = Objects.requireNonNull(store);
        this.writer = Objects.requireNonNull(writer);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * 一次有界推进；只由后台调度器或显式运维调用。
     */
    public TickResult tick(String generation) {
        var metrics = new TickMetrics();
        if (!(config.scanEnabled() || config.backfillEnabled() || config.reconcileEnabled())) return metrics.result(false);
        store.register(generation, config.index().rulesVersion());
        var lease = store.acquire(generation, worker, config.sync().lease());
        if (lease == null) return metrics.result(false);
        long started = System.nanoTime();
        try {
            long databaseNow = store.now();
            boolean initialDone = true;
            for (var k : Kind.values()) if (store.checkpoint(lease, k.name()).rounds() == 0) initialDone = false;
            // 先排空当前可执行批次，再发现下一批；否则 batch > work 时持续重扫会让校准永远无法静止。
            // 尚在退避的失败不阻止新发现，身份及 next_at 仍由队列保留。
            boolean canDiscover = store.pending(lease, 1).isEmpty();
            if (canDiscover) {
                var kind = Kind.values()[Math.floorMod(lane.getAndIncrement(), 3)];
                var cp = store.checkpoint(lease, kind.name());
                boolean continuous = config.scanEnabled() || config.reconcileEnabled();
                if ((cp.rounds() == 0 || continuous) && cp.nextAt() <= databaseNow) {
                    var found = source.scan(kind, cp.cursor(), config.sync().batchSize());
                    metrics.scanned += found.size();
                    boolean end = found.size() < config.sync().batchSize();
                    var pause = config.scanEnabled() ? config.sync().tick() : config.sync().reconcileInterval();
                    store.discovered(lease, kind.name(), found, found.isEmpty() ? cp.cursor() : found.getLast().key(), end, pause);
                }
            }
            if (initialDone && canDiscover) {
                var cp = store.checkpoint(lease, "E");
                if ((cp.rounds() == 0 || config.reconcileEnabled()) && cp.nextAt() <= databaseNow) {
                    var known = store.known(lease, cp.cursor(), config.sync().batchSize());
                    metrics.scanned += known.size();
                    boolean end = known.size() < config.sync().batchSize();
                    store.reconcileDiscovered(lease, known, known.isEmpty() ? cp.cursor() : known.getLast().unit().taskKey(), end, config.sync().reconcileInterval());
                }
            }
            var pending = store.pending(lease, config.sync().workPerTick());
            var readBatch = source.batch(pending);
            for (var work : pending) {
                if (System.nanoTime() - started >= config.sync().tickBudget().toNanos()) break;
                store.renew(lease, config.sync().lease());
                metrics.processed++;
                try {
                    process(lease, work, readBatch, metrics);
                } catch (LostLease lost) {
                    throw lost;
                } catch (Exception error) {
                    long multiplier = 1L << Math.min(work.failures(), 20);
                    long delay = Math.min(config.sync().retryMax().toMillis(), config.sync().retryInitial().toMillis() * multiplier);
                    String code = error instanceof MissingResult ? "MISSING_RESULT" : error instanceof EligibilityUnavailable ? "ARCHIVE_READ_FAILED" : error instanceof Retry retry ? retry.code : "SYNC_FAILED";
                    if (error instanceof MissingResult missing) store.observe(lease, work, missing.scope(), null);
                    if (error instanceof EligibilityUnavailable unavailable)
                        store.observe(lease, work, unavailable.scope(), null);
                    store.fail(lease, work, code, Duration.ofMillis(delay));
                }
            }
        } finally {
            try {
                store.release(lease);
            } catch (LostLease ignored) {/* 新领取者持有状态 */}
        }
        return metrics.result(true);
    }

    private void process(Lease lease, Work work, ConversationSearchSource.ReadBatch readBatch, TickMetrics metrics) throws Exception {
        var snapshot = readBatch.read(work.unit(), work.scope());
        store.observe(lease, work, snapshot.scope(), snapshot.disposition());
        var old = store.entries(lease, work.unit());
        var previous = new HashMap<String, Entry>();
        for (var entry : old) previous.put(entry.entryKey(), entry);
        var scope = snapshot.scope() != null ? snapshot.scope() : old.isEmpty() ? work.scope() : old.getFirst().scope();
        var desired = new LinkedHashMap<String, SearchIndexRecord>();
        for (var r : snapshot.records())
            if (desired.put(r.entryKey(), r) != null) throw new IllegalStateException("Duplicate extracted identity");
        var staged = new LinkedHashMap<String, Entry>();
        var mutations = new ArrayList<Mutation>();
        for (var r : desired.values()) stage(lease, work, r, Desired.LIVE, staged, mutations, previous.get(r.entryKey()), metrics);
        for (var e : old)
            if (!desired.containsKey(e.entryKey())) {
                var identity = json.readValue(e.identity(), SearchIndexRecord.class);
                stage(lease, work, identity, snapshot.disposition() == Disposition.ARCHIVED ? Desired.DELETE : Desired.TOMBSTONE, staged, mutations, e, metrics);
            }
        // 重试重新抽取当前源；发送前再读一次，避免重放已改变／归档的旧正文。
        var current = readBatch.read(work.unit(), scope);
        if (!snapshotFingerprint(snapshot).equals(snapshotFingerprint(current))) throw new Retry("SOURCE_CHANGED");
        for (int at = 0; at < mutations.size(); at += 100) {
            store.renew(lease, config.sync().lease());
            var batch = mutations.subList(at, Math.min(mutations.size(), at + 100));
            var results = writer.write(lease.generation(), batch, () -> requireCurrent(lease, work, scope, snapshot, readBatch));
            if (results.size() != batch.size()) throw new Retry("INCOMPLETE_BULK");
            boolean failed = false;
            for (int n = 0; n < batch.size(); n++) {
                var result = results.get(n);
                if (!result.entryKey().equals(batch.get(n).record().entryKey())) throw new Retry("INCOMPLETE_BULK");
                if (result.success()) {
                    store.acknowledge(lease, staged.get(result.entryKey()));
                    metrics.written++;
                }
                else failed = true;
            }
            if (failed) throw new Retry("ES_ITEM_FAILED");
        }
        if (snapshot.disposition() == Disposition.ARCHIVED && scope != null) {
            store.renew(lease, config.sync().lease());
            if (!writer.purgeArchived(lease.generation(), scope, () -> requireCurrent(lease, work, scope, snapshot, readBatch))) {
                store.continueWork(lease, work, config.sync().tick());
                return;
            }
        }
        store.complete(lease, work, scope);
    }

    private void requireCurrent(Lease lease, Work work, Scope scope, Snapshot expected, ConversationSearchSource.ReadBatch readBatch) {
        store.renew(lease, config.sync().lease());
        readBatch.refreshEligibility();
        var current = readBatch.read(work.unit(), scope);
        store.renew(lease, config.sync().lease());
        if (!snapshotFingerprint(expected).equals(snapshotFingerprint(current))) throw new Retry("SOURCE_CHANGED");
    }

    private void stage(Lease l, Work w, SearchIndexRecord r, Desired desired, Map<String, Entry> staged, List<Mutation> mutations,
                       Entry previous, TickMetrics metrics) {
        var identity = copy(r, 1, false);
        String fingerprint = CanonicalJson.sha256(CanonicalJson.encode(Map.of("desired", desired.name(), "source", r.source())));
        var entry = store.stage(l, w, new Scope(r.owner(), r.conversationId()), json.writeValueAsString(identity), fingerprint, desired);
        if (previous == null || entry.revision() != previous.revision()) metrics.changed++;
        staged.put(entry.entryKey(), entry);
        if (w.forceWrite() || desired == Desired.DELETE || entry.acknowledged() != entry.revision())
            mutations.add(new Mutation(copy(r, entry.revision(), desired == Desired.LIVE), desired));
    }

    private String snapshotFingerprint(Snapshot s) {
        return CanonicalJson.sha256(CanonicalJson.encode(s));
    }

    public static SearchIndexRecord copy(SearchIndexRecord r, long revision, boolean live) {
        return new SearchIndexRecord(r.entryKey(), r.conversationKey(), r.owner(), r.conversationId(), r.recordKind(), r.role(), r.turnId(), r.messageId(), r.invocationId(), r.turnSequence(), r.candidateOrdinal(),
                r.groupIds(), live ? r.title() : null, live ? r.body() : null, r.generatedAt(), r.partial(), live, revision, r.extractorVersion(), live ? r.contentHash() : null, r.sourceRef());
    }

    private static final class Retry extends RuntimeException {
        private final String code;

        private Retry(String code) {
            super(code);
            this.code = code;
        }
    }
}
