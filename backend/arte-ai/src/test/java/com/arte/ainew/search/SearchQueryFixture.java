package com.arte.ainew.search;

import com.arte.ainew.application.search.DefaultConversationSearchService;
import com.arte.ainew.application.search.SearchReadAuthorization;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSession;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchIndex.Pit;
import com.arte.ainew.spi.search.ConversationSearchReads;
import com.arte.ainew.spi.search.ConversationSearchSessions;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.search.SearchSyncFixture.OWNER;

final class SearchQueryFixture implements AutoCloseable {

    final SearchSyncFixture f = new SearchSyncFixture();
    final MutableClock clock = new MutableClock(Instant.now());
    final AtomicBoolean denied = new AtomicBoolean();
    final ExecutionAuthorizationResolver resolver = (name, t, w, scopes) -> denied.get() ? Mono.error(new org.springframework.security.access.AccessDeniedException("test denied")) :
            Mono.just(new ExecutionAuthorization(new ExecutionPrincipal(name.equals("bob") ? "bob" : OWNER.subjectId(), name, ExecutionPrincipal.Kind.USER), t, w, scopes, "current-grant"));
    final Scheduler scheduler = Schedulers.newBoundedElastic(4, 100, "search-query-test"), cleanup = Schedulers.newBoundedElastic(1, 100, "search-cleanup-test");
    final MybatisConversationSearchReads reads = new MybatisConversationSearchReads(f.ds, f.codec, (owner, ids) -> Mono.just(ids.stream().filter(f.archived::contains).collect(java.util.stream.Collectors.toSet())));
    final Index index = new Index();
    final NewAiSearchProperties properties;
    final MemorySessions sessions;
    final DefaultConversationSearchService service;

    SearchQueryFixture() {
        this(null, null);
    }

    SearchQueryFixture(NewAiSearchProperties.Session session, NewAiSearchProperties.Limits limits) {
        properties = new NewAiSearchProperties(true, true, false, false, false, null, session, limits);
        sessions = new MemorySessions(clock, properties);
        service = service(index, sessions, reads, properties);
        // S3 的读取不依赖后台进度表或扫描完成状态。
        for (var table : List.of("arte_ai_search_entry", "arte_ai_search_work", "arte_ai_search_checkpoint", "arte_ai_search_generation"))
            f.jdbc.execute("DROP TABLE " + table);
    }

    DefaultConversationSearchService service(ConversationSearchIndex i, ConversationSearchSessions s, ConversationSearchReads r, NewAiSearchProperties p) {
        return new DefaultConversationSearchService(i, s, r, new SearchReadAuthorization(resolver, clock), p, scheduler, cleanup, clock);
    }

    ExecutionContext context() {
        return context(OWNER, "alice");
    }

    ExecutionContext context(ExecutionOwner owner, String name) {
        return new ExecutionContext(UUID.randomUUID().toString(), "trace", new ExecutionAuthorization(new ExecutionPrincipal(owner.subjectId(), name, ExecutionPrincipal.Kind.USER), owner.tenantId(), owner.workspaceId(), Set.of("ai:conversation", "ai:read"), "grant"), clock.instant().plusSeconds(60), null, null, null, null);
    }

    static ConversationSearch.Query query(int size, String cursor) {
        return new ConversationSearch.Query("marker", List.of(), size, cursor);
    }

    ConversationSearch.Page search(int size, String cursor) {
        return service.search(query(size, cursor), context()).block(Duration.ofSeconds(5));
    }

    void title(String id) {
        f.conversation(id, "marker " + id);
        index.add(SearchIndexRecord.title(OWNER, id, "marker " + id, AT, 1));
    }

    @Override
    public void close() {
        scheduler.dispose();
        cleanup.dispose();
        f.close();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        synchronized void advance(Duration d) {
            now = now.plus(d);
        }

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public synchronized Instant instant() {
            return now;
        }
    }

    static final class Index implements ConversationSearchIndex {
        final List<Hit> hits = new ArrayList<>();
        final List<Read> requests = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger();
        String generation = "conversation-search-v1-g01";
        int count, failAt;
        Runnable onRead = () -> {
        };

        void add(SearchIndexRecord record) {
            hits.add(new Hit(record.entryKey(), record.source(), Arrays.asList(1000.0 - hits.size(), null, record.entryKey(), (long) hits.size()), Map.of()));
        }

        public Mono<Pit> open(Duration d) {
            return Mono.just(new Pit("pit-open", generation));
        }

        public Mono<Batch> read(Read read) {
            return Mono.fromCallable(() -> {
                requests.add(read);
                onRead.run();
                if (++count == failAt) throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
                int index = read.direction() == ConversationSearch.Direction.BACKWARD ? hits.size() : -1;
                if (!read.after().isEmpty()) {
                    index = -1;
                    for (int n = 0; n < hits.size(); n++)
                        if (hits.get(n).entryKey().equals(read.after().get(2))) {
                            index = n;
                            break;
                        }
                    if (index < 0) throw new IllegalArgumentException("Unknown fake boundary");
                }
                var out = new ArrayList<Hit>();
                int step = read.direction() == ConversationSearch.Direction.BACKWARD ? -1 : 1;
                for (int n = index + step; n >= 0 && n < hits.size() && out.size() < read.size(); n += step)
                    out.add(hits.get(n));
                return new Batch(new Pit("pit-" + count, read.pit().generation()), out);
            });
        }

        public Mono<Void> close(Pit pit) {
            closed.incrementAndGet();
            return Mono.empty();
        }
    }

    static final class MemorySessions implements ConversationSearchSessions {
        final Map<String, SearchSession> values = new HashMap<>();
        final Set<String> cancelled = new HashSet<>();
        final MutableClock clock;
        final NewAiSearchProperties p;
        final String history = SearchCursor.randomKey();

        MemorySessions(MutableClock clock, NewAiSearchProperties p) {
            this.clock = clock;
            this.p = p;
        }

        private SearchSession current(ExecutionOwner owner, String id) {
            var s = values.get(id);
            if (s == null || !s.ownerKey().equals(SearchCursor.owner(owner)) || s.idleExpiresAt() <= clock.millis() || s.hardExpiresAt() <= clock.millis())
                throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
            return s;
        }

        private SearchSession guard(ExecutionOwner owner, SearchSession lease) {
            var s = current(owner, lease.id());
            if (!s.operationToken().equals(lease.operationToken()) || s.operationUntil() <= clock.millis())
                throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
            return s;
        }

        private SearchSession save(SearchSession s, String token, long until, String pit, String generation, long pitEnd, long idle, long success) {
            var next = new SearchSession(s.id(), s.ownerKey(), s.queryHash(), s.size(), s.rulesVersion(), s.secret(), pit, generation, s.createdAt(), success, s.hardExpiresAt(), idle, pitEnd, token, until, clock.millis());
            values.put(s.id(), next);
            return next;
        }

        public synchronized SearchSession reserve(ExecutionOwner owner, String id, String hash, int size, String rule, Duration timeout) {
            if (cancelled.contains(id)) throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
            long active = values.values().stream().filter(s -> s.ownerKey().equals(SearchCursor.owner(owner)) && s.idleExpiresAt() > clock.millis()).count();
            if (active >= p.session().maxPerSubject()) throw new SearchFailure(SearchFailure.Code.SEARCH_LIMIT_REACHED);
            long now = clock.millis(), hard = now + p.session().maxLifetime().toMillis(), idle = Math.min(now + p.session().idleTimeout().toMillis(), hard);
            var s = new SearchSession(id, SearchCursor.owner(owner), hash, size, rule, SearchCursor.randomKey(), "", "", now, 0, hard, idle, 0, UUID.randomUUID().toString(), Math.min(now + 30000, idle), now);
            values.put(id, s);
            return s;
        }

        public synchronized SearchSession acquire(ExecutionOwner owner, String id, String hash, int size, Duration timeout) {
            var s = current(owner, id);
            if (!s.queryHash().equals(hash) || s.size() != size)
                throw new SearchFailure(SearchFailure.Code.INVALID_CURSOR);
            if (s.operationUntil() > clock.millis()) throw new SearchFailure(SearchFailure.Code.SEARCH_BUSY);
            return save(s, UUID.randomUUID().toString(), Math.min(clock.millis() + 30000, s.idleExpiresAt()), s.pitId(), s.generation(), s.pitExpiresAt(), s.idleExpiresAt(), s.lastSuccessAt());
        }

        public synchronized SearchSession pit(ExecutionOwner owner, SearchSession lease, Pit pit, long end, Duration timeout) {
            var s = guard(owner, lease);
            return save(s, s.operationToken(), s.operationUntil(), pit.id(), pit.generation(), end, s.idleExpiresAt(), s.lastSuccessAt());
        }

        public synchronized SearchSession complete(ExecutionOwner owner, SearchSession lease, Duration timeout) {
            var s = guard(owner, lease);
            return save(s, "", 0, s.pitId(), s.generation(), s.pitExpiresAt(), Math.min(clock.millis() + p.session().idleTimeout().toMillis(), Math.min(s.hardExpiresAt(), s.pitExpiresAt())), clock.millis());
        }

        public synchronized void release(ExecutionOwner owner, SearchSession lease, Duration timeout) {
            var s = guard(owner, lease);
            save(s, "", 0, s.pitId(), s.generation(), s.pitExpiresAt(), s.idleExpiresAt(), s.lastSuccessAt());
        }

        public synchronized SearchSession close(ExecutionOwner owner, String id, Duration timeout) {
            var s = values.get(id);
            if (s != null && s.ownerKey().equals(SearchCursor.owner(owner))) {
                values.remove(id);
                cancelled.add(id);
                return s;
            }
            return null;
        }

        public synchronized SearchSession cancelReservation(ExecutionOwner owner, String id, Duration timeout) {
            cancelled.add(id);
            return close(owner, id, timeout);
        }

        public String historySigningKey(Duration timeout) {
            return history;
        }
    }
}
