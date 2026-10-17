package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.persistence.mybatis.mapper.SearchSyncMapper;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 会话搜索同步状态的 MyBatis 持久化实现，维护代际租约、扫描检查点、重试任务、记录指纹及同步确认进度。
 * 通过独立短事务和租约版本校验保护同步状态，业务表读取和 ES 请求保持在事务外。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class MybatisConversationSearchSyncStore implements ConversationSearchSyncStore {

    private final int errorThreshold;
    private final SearchSyncMapper mapper;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();

    public MybatisConversationSearchSyncStore(DataSource dataSource) {
        this(dataSource, 10);
    }

    public MybatisConversationSearchSyncStore(DataSource dataSource, int errorThreshold) {
        this(dataSource, errorThreshold, new NewAiSearchProperties.Diagnostics(null, null));
    }

    public MybatisConversationSearchSyncStore(DataSource dataSource, int errorThreshold, NewAiSearchProperties.Diagnostics diagnostics) {
        this.errorThreshold = errorThreshold;
        var sessions = new SqlSessionTemplate(SearchSqlSessionFactory.create(dataSource, diagnostics));
        mapper = sessions.getMapper(SearchSyncMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setTimeout(30);
    }

    private <T> T transaction(Supplier<T> work) {
        return tx.execute(s -> work.get());
    }

    @Override
    public long now() {
        return mapper.databaseTimeMillis();
    }

    public static void generationName(String g) {
        if (g == null || !g.matches("[a-z][a-z0-9_-]{0,127}"))
            throw new IllegalArgumentException("Invalid physical generation");
    }

    private SearchSyncMapper.Generation guard(Lease l) {
        var r = mapper.generation(l.generation(), true);
        if (r == null || !Objects.equals(r.worker(), l.worker()) || r.fence() != l.fence() || r.leaseUntil() <= now())
            throw new LostLease();
        return r;
    }

    @Override
    public void register(String g, String rules) {
        generationName(g);
        transaction(() -> {
            mapper.register(g, rules);
            if (!mapper.generation(g, true).rulesVersion().equals(rules))
                throw new IllegalStateException("Generation rules mismatch");
            for (var lane : List.of("C", "T", "I", "E")) mapper.ensureCheckpoint(g, lane);
            return null;
        });
    }

    @Override
    public Lease acquire(String g, String worker, Duration duration) {
        return transaction(() -> {
            var r = mapper.generation(g, true);
            if (r == null) throw new IllegalStateException("Register physical generation before scanning");
            long time = now();
            if (r.leaseUntil() > time) return null;
            long fence = Math.incrementExact(r.fence());
            mapper.lease(g, worker, fence, Math.addExact(time, duration.toMillis()));
            return new Lease(g, worker, fence);
        });
    }

    @Override
    public void renew(Lease l, Duration d) {
        transaction(() -> {
            guard(l);
            mapper.lease(l.generation(), l.worker(), l.fence(), Math.addExact(now(), d.toMillis()));
            return null;
        });
    }

    @Override
    public void release(Lease l) {
        transaction(() -> {
            guard(l);
            mapper.lease(l.generation(), null, l.fence(), 0);
            return null;
        });
    }

    @Override
    public Checkpoint checkpoint(Lease l, String lane) {
        return transaction(() -> {
            guard(l);
            var r = mapper.checkpoint(l.generation(), lane);
            return new Checkpoint(r.lane(), r.cursorKey(), r.rounds(), r.nextAt());
        });
    }

    private void queue(Lease l, Unit u, boolean force) {
        if (mapper.enqueue(l.generation(), u.taskKey(), u.kind().name(), u.key(), force) == 0)
            mapper.requeue(l.generation(), u.taskKey(), force);
    }

    @Override
    public void discovered(Lease l, String lane, List<Unit> units, String cursor, boolean end, Duration pause) {
        transaction(() -> {
            guard(l);
            for (var u : units) queue(l, u, false);
            var old = mapper.checkpoint(l.generation(), lane);
            mapper.checkpointSave(l.generation(), lane, end ? "" : cursor, old.rounds() + (end ? 1 : 0), end ? Math.addExact(now(), pause.toMillis()) : 0);
            return null;
        });
    }

    private Work work(SearchSyncMapper.Work r) {
        var scope = r.tenantId() == null ? null : new Scope(new ExecutionOwner(r.tenantId(), r.workspaceId(), r.subjectId()), r.conversationId());
        return new Work(new Unit(Kind.valueOf(r.kind()), r.sourceKey()), scope, r.forceWrite(), r.failures());
    }

    @Override
    public List<Work> known(Lease l, String after, int size) {
        return transaction(() -> {
            guard(l);
            return mapper.known(l.generation(), after, size).stream().map(this::work).toList();
        });
    }

    @Override
    public List<Work> pending(Lease l, int size) {
        return transaction(() -> {
            guard(l);
            return mapper.pending(l.generation(), now(), size).stream().map(this::work).toList();
        });
    }

    private Entry entry(SearchSyncMapper.Entry r) {
        return new Entry(r.entryKey(), new Unit(Kind.valueOf(r.kind()), r.sourceKey()), new Scope(new ExecutionOwner(r.tenantId(), r.workspaceId(), r.subjectId()), r.conversationId()),
                r.identityJson(), r.fingerprint(), r.revision(), r.acknowledged(), Desired.valueOf(r.desired()));
    }

    @Override
    public List<Entry> entries(Lease l, Unit u) {
        return transaction(() -> {
            guard(l);
            return mapper.entries(l.generation(), u.taskKey()).stream().map(this::entry).toList();
        });
    }

    private static String ownerKey(ExecutionOwner owner) {
        return CanonicalJson.key(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    @Override
    public Entry stage(Lease l, Work w, Scope scope, String identity, String fingerprint, Desired desired) {
        var projection = json.readValue(identity, SearchIndexRecord.class);
        if (projection.searchable() || projection.title() != null || projection.body() != null || projection.contentHash() != null
                || !projection.owner().equals(scope.owner()) || !projection.conversationId().equals(scope.conversationId()))
            throw new IllegalArgumentException("Background identity must not contain text");
        return transaction(() -> {
            guard(l);
            var old = mapper.entry(l.generation(), projection.entryKey());
            if (old != null && (!old.sourceKey().equals(w.unit().key()) || !old.kind().equals(w.unit().kind().name())))
                throw new IllegalStateException("Entry source changed");
            boolean changed = old == null || !old.fingerprint().equals(fingerprint) || !old.desired().equals(desired.name());
            long revision = old == null ? 1 : changed ? Math.incrementExact(old.revision()) : old.revision();
            var r = new SearchSyncMapper.Entry(projection.entryKey(), w.unit().kind().name(), w.unit().key(), scope.owner().tenantId(), scope.owner().workspaceId(), scope.owner().subjectId(),
                    scope.conversationId(), identity, fingerprint, revision, old == null ? 0 : old.acknowledged(), desired.name());
            if (old == null) mapper.insertEntry(l.generation(), r, w.unit().taskKey(), ownerKey(scope.owner()));
            else if (changed) mapper.updateEntry(l.generation(), r);
            return entry(r);
        });
    }

    @Override
    public void acknowledge(Lease l, Entry e) {
        transaction(() -> {
            guard(l);
            if (mapper.acknowledge(l.generation(), e.entryKey(), e.revision()) != 1) throw new LostLease();
            return null;
        });
    }

    @Override
    public void observe(Lease l, Work w, Scope scope, Disposition disposition) {
        if (scope != null) transaction(() -> {
            guard(l);
            mapper.bindScope(l.generation(), w.unit().taskKey(), scope.owner().tenantId(), scope.owner().workspaceId(), scope.owner().subjectId(), scope.conversationId(), ownerKey(scope.owner()), disposition == null ? null : disposition.name());
            return null;
        });
    }

    @Override
    public long prepareActivation(Lease l) {
        return transaction(() -> {
            var r = guard(l);
            long epoch = Math.incrementExact(Math.abs(r.validationEpoch()));
            mapper.validation(l.generation(), epoch);
            for (var lane : List.of("C", "T", "I", "E")) mapper.checkpointSave(l.generation(), lane, "", 0, 0);
            return epoch;
        });
    }

    @Override
    public void consumeActivation(Lease l, long epoch) {
        transaction(() -> {
            var r = guard(l);
            if (epoch <= 0 || r.validationEpoch() != epoch)
                throw new IllegalStateException("Fresh activation validation required");
            mapper.validation(l.generation(), -epoch);
            return null;
        });
    }

    @Override
    public void complete(Lease l, Work w, Scope scope) {
        transaction(() -> {
            guard(l);
            mapper.complete(l.generation(), w.unit().taskKey(), scope == null ? null : scope.owner().tenantId(), scope == null ? null : scope.owner().workspaceId(), scope == null ? null : scope.owner().subjectId(), scope == null ? null : scope.conversationId(), scope == null ? null : ownerKey(scope.owner()));
            mapper.success(l.generation(), now());
            return null;
        });
    }

    @Override
    public void fail(Lease l, Work w, String code, Duration delay) {
        transaction(() -> {
            guard(l);
            mapper.fail(l.generation(), w.unit().taskKey(), code, Math.addExact(now(), delay.toMillis()));
            return null;
        });
    }

    @Override
    public void continueWork(Lease l, Work w, Duration delay) {
        transaction(() -> {
            guard(l);
            mapper.continueWork(l.generation(), w.unit().taskKey(), Math.addExact(now(), delay.toMillis()));
            return null;
        });
    }

    private void sweepComplete(Lease l, String cursor, boolean end, Duration pause) {
        transaction(() -> {
            guard(l);
            var old = mapper.checkpoint(l.generation(), "E");
            mapper.checkpointSave(l.generation(), "E", end ? "" : cursor, old.rounds() + (end ? 1 : 0), end ? Math.addExact(now(), pause.toMillis()) : 0);
            return null;
        });
    }

    /**
     * 校准任务入队与 E 检查点由同一短事务确认。
     */
    public void reconcileDiscovered(Lease l, List<Work> work, String cursor, boolean end, Duration pause) {
        transaction(() -> {
            guard(l);
            for (var w : work) queue(l, w.unit(), true);
            sweepComplete(l, cursor, end, pause);
            return null;
        });
    }

    @Override
    public Status status(String g) {
        return transaction(() -> {
            var r = mapper.generation(g, false);
            if (r == null) return new Status(g, "NOT_READY", 0, 0, 0, 0, null, 0);
            long pending = mapper.pendingCount(g, null, false), failures = mapper.pendingCount(g, null, true);
            long rounds = List.of("C", "T", "I", "E").stream().map(lane -> mapper.checkpoint(g, lane)).filter(Objects::nonNull).mapToLong(SearchSyncMapper.Checkpoint::rounds).min().orElse(0);
            String state = mapper.maxFailures(g, null) >= errorThreshold ? "ERROR" : failures > 0 ? "DEGRADED" : pending == 0 && rounds > 0 ? "READY" : "SCANNING";
            return new Status(g, state, pending, failures, mapper.entryCount(g, null), rounds, r.lastSuccessAt(), mapper.missingCount(g, null));
        });
    }

    @Override
    public OwnerStatus status(String g, ExecutionOwner owner) {
        var overall = status(g);
        return transaction(() -> {
            String key = ownerKey(owner);
            long pending = mapper.pendingCount(g, key, false), failed = mapper.pendingCount(g, key, true);
            String state = mapper.maxFailures(g, key) >= errorThreshold ? "ERROR" : failed > 0 ? "DEGRADED" : overall.state().equals("NOT_READY") ? "NOT_READY" : pending > 0 || overall.completedRounds() == 0 ? "SCANNING" : "READY";
            return new OwnerStatus(owner, state, pending, failed, mapper.entryCount(g, key), mapper.missingCount(g, key));
        });
    }

}
