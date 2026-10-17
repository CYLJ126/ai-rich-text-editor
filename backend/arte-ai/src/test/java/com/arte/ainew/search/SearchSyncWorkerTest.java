package com.arte.ainew.search;

import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSource;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSyncStore;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.*;
import com.arte.ainew.spi.search.ConversationSearchSource;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static com.arte.ainew.search.SearchSyncFixture.properties;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

public class SearchSyncWorkerTest {
    SearchSyncFixture f;
    MemoryWriter writer;
    ConversationSearchSyncWorker worker;
    static final String G = "search-test-g01";

    @Before
    public void setup() {
        f = new SearchSyncFixture();
        writer = new MemoryWriter();
        worker = new ConversationSearchSyncWorker(f.source, f.store, writer, properties("search-test", G));
    }

    @After
    public void cleanup() {
        f.close();
    }

    void tick(int n) {
        for (int i = 0; i < n; i++) {
            f.due();
            worker.tick(G);
        }
    }

    void history() {
        f.conversation("conv", "old title");
        f.turn("conv", "turn", "用户问题", List.of("first", "second"));
        f.invocation("conv", "turn", "first", "保留部分回答", true, true);
        f.invocation("conv", "turn", "second", "完整新回答", false, true);
    }

    @Test
    public void discoveryBatchLargerThanProcessingBudgetStillReachesAQuiescentCheckpoint() {
        var p = properties("search-test", G);
        var limited = new com.arte.ainew.config.NewAiSearchProperties(true, false, true, true, true, p.index(), null, null,
                new com.arte.ainew.config.NewAiSearchProperties.Sync(G, Duration.ofMillis(100), 20, 1, null, Duration.ofSeconds(1), null, null, null));
        worker = new ConversationSearchSyncWorker(f.source, f.store, writer, limited);
        for (int n = 0; n < 12; n++) f.conversation("batch-" + n, "title " + n);
        boolean ready = false;
        for (int n = 0; n < 120; n++) {
            tick(1);
            if ("READY".equals(f.store.status(G).state())) {
                ready = true;
                break;
            }
        }
        assertTrue("Continuous discovery must not permanently replenish the due queue", ready);
        assertEquals(12, writer.docs.size());
        assertTrue(Math.abs(f.store.now() - System.currentTimeMillis()) < 2500);
    }

    @Test
    public void indexesEveryCandidateAndUserOnceAndNeverTouchesBusinessOutbox() {
        history();
        tick(20);
        assertEquals(4, writer.docs.size());
        assertEquals(1, writer.docs.values().stream().filter(r -> "USER".equals(r.role())).count());
        assertEquals(1, writer.docs.values().stream().filter(SearchIndexRecord::partial).count());
        assertFalse(writer.docs.values().toString().contains("internal must not index"));
        assertEquals("READY", f.store.status(G).state());
        assertEquals(Long.valueOf(0), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox", Long.class));
        String identities = String.join("", f.jdbc.queryForList("SELECT identity_json FROM arte_ai_search_entry", String.class));
        assertFalse(identities.contains("用户问题"));
        assertFalse(identities.contains("完整新回答"));
        assertEquals("READY", f.store.status(G, OWNER).state());
    }

    @Test
    public void realMetadataRenameOverridesUnchangedConversationSnapshot() {
        f.conversation("conv", "old title");
        tick(8);
        f.jdbc.update("INSERT INTO arte_ai_conversation_metadata(id_key,owner_key,conversation_id,title,active,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_sequence,sort_micros,created_micros,statistics_ready) VALUES(?,?,?,'renamed',TRUE,1,FALSE,TRUE,TRUE,0,0,0,TRUE)", key("conv"), key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId()), "conv");
        tick(10);
        assertEquals("renamed", writer.docs.values().iterator().next().title());
        assertEquals(2, writer.docs.values().iterator().next().sourceRevision());
    }

    @Test
    public void restartKeepsProgressAndAcknowledgementsAndDoesNotInventNewVersions() {
        history();
        tick(1);
        var count = f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_entry", Long.class);
        var restarted = new MybatisConversationSearchSyncStore(f.ds);
        worker = new ConversationSearchSyncWorker(f.source, restarted, writer, properties("search-test", G));
        tick(20);
        assertEquals(4, writer.docs.size());
        assertTrue(count > 0);
        assertTrue(writer.docs.values().stream().allMatch(r -> r.sourceRevision() == 1));
    }

    @Test
    public void missingDurableResultIsVisibleAndRetriedRatherThanFakedOrDropped() {
        f.conversation("conv", "title");
        f.turn("conv", "turn", "question", List.of("missing"));
        f.invocation("conv", "turn", "missing", "answer", false, false);
        tick(20);
        assertEquals(2, writer.docs.size());
        assertEquals("ERROR", f.store.status(G).state());
        assertEquals("ERROR", f.store.status(G, OWNER).state());
        assertEquals(1, f.store.status(G, OWNER).missingResults());
        assertTrue(f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_work WHERE error_code='MISSING_RESULT' AND pending=TRUE", Long.class) > 0);
    }

    @Test
    public void partialBulkOnlyAcknowledgesSuccessfulItemsAndRestartRetriesFailures() {
        history();
        writer.reject = "answer-second";
        tick(15);
        assertEquals(3, writer.docs.size());
        assertTrue(f.store.status(G).failures() > 0);
        assertEquals(Long.valueOf(1), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_entry WHERE acknowledged < revision", Long.class));
        writer.reject = null;
        worker = new ConversationSearchSyncWorker(f.source, f.store, writer, properties("search-test", G));
        tick(15);
        assertEquals(4, writer.docs.size());
        assertEquals(0, f.store.status(G).failures());
    }

    @Test
    public void reconciliationRepairsLostIndexAndDeletedAuthoritativeSource() {
        history();
        tick(15);
        writer.docs.clear();
        f.jdbc.update("DELETE FROM arte_ai_invocation WHERE id_key=?", key("first"));
        tick(30);
        assertEquals(4, writer.docs.size());
        var tombstone = writer.docs.values().stream().filter(r -> "first".equals(r.invocationId())).findFirst().orElseThrow();
        assertFalse(tombstone.searchable());
        assertNull(tombstone.body());
        assertNull(tombstone.contentHash());
    }

    @Test
    public void archivePhysicallyPurgesAndPeriodicReconciliationCleansLateWritesThenUnarchiveRestores() {
        history();
        tick(15);
        var stale = List.copyOf(writer.docs.values());
        f.archived.add("conv");
        tick(20);
        assertTrue(writer.docs.isEmpty());
        stale.forEach(r -> writer.docs.put(r.entryKey(), r));
        tick(20);
        assertTrue(writer.docs.isEmpty());
        f.archived.clear();
        tick(20);
        assertEquals(4, writer.docs.size());
        assertTrue(writer.docs.values().stream().allMatch(r -> r.sourceRevision() >= 3));
    }

    @Test
    public void rebuildingUsesIndependentGenerationAndDoesNotReviveArchivedSources() {
        history();
        tick(15);
        f.archived.add("conv");
        var other = new MemoryWriter();
        var next = "search-test-g02";
        var w = new ConversationSearchSyncWorker(f.source, f.store, other, properties("search-test", next));
        assertEquals("NOT_READY", f.store.status(next).state());
        for (int n = 0; n < 20; n++) {
            f.due();
            w.tick(next);
        }
        assertTrue(other.docs.isEmpty());
        assertEquals("READY", f.store.status(next).state());
        assertEquals(4, writer.docs.size());
    }

    @Test
    public void readStartedUnderExpiredLeaseCannotAllocateOrAcknowledgeAfterNewWorker() throws Exception {
        f.conversation("conv", "old");
        f.store.register(G, "v1");
        var a = f.store.acquire(G, "old", Duration.ofSeconds(60));
        var unit = new Unit(Kind.C, key("conv"));
        var work = new Work(unit, null, false, 0);
        var readDone = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(() -> {
                var read = f.source.read(unit, null);
                readDone.countDown();
                resume.await();
                var r = read.records().getFirst();
                return f.store.stage(a, work, read.scope(), identity(r), "a".repeat(64), Desired.LIVE);
            });
            assertTrue(readDone.await(5, TimeUnit.SECONDS));
            f.jdbc.update("UPDATE arte_ai_search_generation SET lease_until=0 WHERE generation=?", G);
            var b = f.store.acquire(G, "new", Duration.ofSeconds(60));
            assertTrue(b.fence() > a.fence());
            f.rename("conv", "new");
            var current = f.source.read(unit, null).records().getFirst();
            var entry = f.store.stage(b, work, new Scope(OWNER, "conv"), identity(current), "b".repeat(64), Desired.LIVE);
            f.store.acknowledge(b, entry);
            resume.countDown();
            var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof LostLease);
            assertThrows(LostLease.class, () -> f.store.acknowledge(a, entry));
            f.store.release(b);
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    public void leaseExcludesCompetingWorkersAndRulesCannotChangeInPlace() {
        f.store.register(G, "v1");
        var a = f.store.acquire(G, "one", Duration.ofSeconds(60));
        assertNotNull(a);
        assertNull(f.store.acquire(G, "two", Duration.ofSeconds(60)));
        assertThrows(IllegalStateException.class, () -> f.store.register(G, "v2"));
        f.store.release(a);
        assertNotNull(f.store.acquire(G, "two", Duration.ofSeconds(60)));
    }

    @Test
    public void sourceChangingBetweenExtractionAndSendNeverEmitsOldBody() {
        f.conversation("conv", "old");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ConversationSearchSource changing = new ConversationSearchSource() {
            public List<Unit> scan(Kind k, String a, int s) {
                return f.source.scan(k, a, s);
            }

            public Snapshot read(Unit u, Scope p) {
                if (reads.incrementAndGet() == 2) f.rename("conv", "new");
                return f.source.read(u, p);
            }
        };
        worker = new ConversationSearchSyncWorker(changing, f.store, writer, properties("search-test", G));
        tick(1);
        assertTrue(writer.docs.isEmpty());
        tick(15);
        assertEquals("new", writer.docs.values().iterator().next().title());
    }

    @Test
    public void activationRequiresFreshCompletedPassAndReceiptCannotBeReused() {
        history();
        tick(18);
        var maintenance = new com.arte.ainew.application.search.ConversationSearchMaintenance(writer, f.store, properties("search-test", G), reactor.core.scheduler.Schedulers.boundedElastic(), worker);
        assertThrows(IllegalStateException.class, () -> maintenance.activate(G, 1).block());
        long epoch = maintenance.prepareActivation(G).block();
        assertThrows(IllegalStateException.class, () -> maintenance.activate(G, epoch).block());
        tick(20);
        maintenance.activate(G, epoch).block();
        assertThrows(IllegalStateException.class, () -> maintenance.activate(G, epoch).block());
    }

    @Test
    public void archiveBeforeBackfillPersistsStopFactAndNoTextIdentity() {
        history();
        f.archived.add("conv");
        tick(20);
        assertTrue(writer.docs.isEmpty());
        assertEquals(Long.valueOf(0), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_entry", Long.class));
        assertEquals(Long.valueOf(4), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_work WHERE source_state='ARCHIVED'", Long.class));
    }

    @Test
    public void newerCandidateArrivingAfterInitialPassIsDiscoveredWithoutConversationActivityChange() {
        f.conversation("conv", "title");
        f.turn("conv", "turn", "question", List.of("answer"));
        tick(15);
        assertEquals(2, writer.docs.size());
        f.invocation("conv", "turn", "answer", "late durable result", false, true);
        tick(15);
        assertEquals(3, writer.docs.size());
    }

    @Test
    public void searchAdaptersConstructWithoutDatabaseAccessOrDdl() {
        var guarded = new org.springframework.jdbc.datasource.DelegatingDataSource(f.ds) {
            @Override
            public java.sql.Connection getConnection() {
                throw new AssertionError("No startup SQL");
            }

            @Override
            public java.sql.Connection getConnection(String u, String p) {
                throw new AssertionError("No startup SQL");
            }
        };
        assertNotNull(new MybatisConversationSearchSyncStore(guarded));
        assertNotNull(new MybatisConversationSearchSource(guarded, f.codec, (o, ids) -> reactor.core.publisher.Mono.just(Set.of())));
    }

    @Test
    public void backfillOnlyFinishesOnceAndMaintenanceCanPrepareFreshReconciliation() {
        history();
        var base = properties("search-test", G);
        var cfg = new com.arte.ainew.config.NewAiSearchProperties(true, false, false, true, false, base.index(), base.session(), base.limits(), base.sync());
        worker = new ConversationSearchSyncWorker(f.source, f.store, writer, cfg);
        tick(18);
        assertEquals("READY", f.store.status(G).state());
        f.rename("conv", "after backfill");
        tick(12);
        assertEquals("old title", writer.docs.values().stream().filter(r -> r.recordKind() == ConversationSearch.Kind.TITLE).findFirst().orElseThrow().title());
        var admin = new com.arte.ainew.application.search.ConversationSearchMaintenance(writer, f.store, cfg, reactor.core.scheduler.Schedulers.boundedElastic(), worker);
        long epoch = admin.prepareActivation(G).block();
        for (int n = 0; n < 18; n++) admin.advance(G).block();
        assertEquals("READY", admin.status(G).block().state());
        admin.activate(G, epoch).block();
        assertEquals("after backfill", writer.docs.values().stream().filter(r -> r.recordKind() == ConversationSearch.Kind.TITLE).findFirst().orElseThrow().title());
    }

    @Test
    public void archiveEligibilityIsBatchedPerOwnerAndRefreshedBeforeWriting() {
        f.conversation("one", "one");
        f.conversation("two", "two");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var source = new MybatisConversationSearchSource(f.ds, f.codec, (o, ids) -> {
            calls.incrementAndGet();
            assertEquals(Set.of("one", "two"), ids);
            return reactor.core.publisher.Mono.just(Set.copyOf(f.archived));
        });
        var one = new Unit(Kind.C, key("one"));
        var two = new Unit(Kind.C, key("two"));
        var batch = source.batch(List.of(new Work(one, null, false, 0), new Work(two, null, false, 0)));
        assertEquals(Disposition.LIVE, batch.read(one, null).disposition());
        assertEquals(Disposition.LIVE, batch.read(two, null).disposition());
        assertEquals(1, calls.get());
        f.archived.add("one");
        batch.refreshEligibility();
        assertEquals(Disposition.ARCHIVED, batch.read(one, null).disposition());
        assertEquals(2, calls.get());
    }

    @Test
    public void archiveAdapterFailureNeverFallsBackToUnarchivedAndPersistsOwnerFailure() {
        f.conversation("conv", "title");
        var failed = new MybatisConversationSearchSource(f.ds, f.codec, (o, ids) -> reactor.core.publisher.Mono.error(new IllegalStateException("test failure")));
        worker = new ConversationSearchSyncWorker(failed, f.store, writer, properties("search-test", G));
        tick(15);
        assertTrue(writer.docs.isEmpty());
        assertEquals("ERROR", f.store.status(G, OWNER).state());
        assertTrue(f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_work WHERE error_code='ARCHIVE_READ_FAILED'", Long.class) > 0);
    }

    @Test
    public void unfinishedArchivePageContinuesWithoutCountingProgressAsFailure() {
        f.conversation("conv", "title");
        f.archived.add("conv");
        var pages = new java.util.concurrent.atomic.AtomicInteger();
        ConversationSearchWriter paged = new ConversationSearchWriter() {
            public List<WriteResult> write(String g, List<Mutation> m, Runnable before) {
                return writer.write(g, m, before);
            }

            public boolean purgeArchived(String g, Scope scope, Runnable before) {
                before.run();
                return pages.incrementAndGet() > 1;
            }

            public void createGeneration(String g) {
            }

            public void activateGeneration(String g) {
            }
        };
        worker = new ConversationSearchSyncWorker(f.source, f.store, paged, properties("search-test", G));
        tick(1);
        assertEquals(1, f.store.status(G).pending());
        assertEquals(0, f.store.status(G).failures());
        tick(15);
        assertEquals("READY", f.store.status(G).state());
        assertEquals(0, f.store.status(G).failures());
    }

    static String identity(SearchIndexRecord r) {
        return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(ConversationSearchSyncWorker.copy(r, 1, false));
    }

    static final class MemoryWriter implements ConversationSearchWriter {
        final Map<String, SearchIndexRecord> docs = new LinkedHashMap<>();
        String reject;

        public List<WriteResult> write(String generation, List<Mutation> batch, Runnable beforeSend) {
            beforeSend.run();
            var results = new ArrayList<WriteResult>();
            for (var m : batch) {
                var r = m.record();
                boolean ok = reject == null || !Objects.equals(reject, r.messageId());
                if (ok) {
                    if (m.desired() == Desired.DELETE) docs.remove(r.entryKey());
                    else docs.put(r.entryKey(), r);
                }
                results.add(new WriteResult(r.entryKey(), ok, ok ? null : "TEST_ITEM_FAILED"));
            }
            return results;
        }

        public boolean purgeArchived(String g, Scope s, Runnable beforeDelete) {
            beforeDelete.run();
            docs.values().removeIf(r -> r.owner().equals(s.owner()) && r.conversationId().equals(s.conversationId()));
            return true;
        }

        public void createGeneration(String g) {
        }

        public void activateGeneration(String g) {
        }
    }
}
