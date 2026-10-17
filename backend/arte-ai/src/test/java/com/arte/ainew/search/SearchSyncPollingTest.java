package com.arte.ainew.search;

import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSyncStore;
import com.arte.ainew.pojo.search.SearchSync.Checkpoint;
import com.arte.ainew.pojo.search.SearchSync.Kind;
import com.arte.ainew.pojo.search.SearchSync.LostLease;
import com.arte.ainew.pojo.search.SearchSync.Unit;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.search.SearchSyncFixture.properties;
import static org.junit.Assert.*;

public class SearchSyncPollingTest {
    private static final String G = "search-test-g01";
    private SearchSyncFixture f;
    private StoreProbe probe;

    @Before
    public void setup() {
        f = new SearchSyncFixture();
        probe = new StoreProbe();
    }

    @After
    public void cleanup() {
        f.close();
    }

    private ConversationSearchSyncWorker worker(ConversationSearchSyncStore store) {
        return new ConversationSearchSyncWorker(f.source, store, new SearchSyncWorkerTest.MemoryWriter(), properties("search-test", G));
    }

    @Test
    public void registersOnceAndReadsFreshCheckpointsOncePerTick() {
        var worker = worker(probe.store);
        worker.tick(G);
        f.jdbc.update("UPDATE arte_ai_search_checkpoint SET rounds=41,next_at=? WHERE generation=? AND lane='C'", Long.MAX_VALUE, G);
        worker.tick(G);
        assertEquals(1, probe.calls("register"));
        assertEquals(2, probe.calls("acquire"));
        assertEquals(2, probe.calls("checkpoints"));
        assertEquals(0, probe.calls("checkpoint"));
        assertEquals(41, probe.lastCheckpoints.get("C").rounds());
        assertThrows(UnsupportedOperationException.class, () -> probe.lastCheckpoints.clear());
    }

    @Test
    public void failedInitializationIsRetriedAndRestartRevalidatesWithoutResettingProgress() {
        var worker = worker(probe.store);
        probe.registrationFailures.set(1);
        assertThrows(IllegalStateException.class, () -> worker.tick(G));
        assertEquals(0, probe.calls("acquire"));
        worker.tick(G);
        worker.tick(G);
        assertEquals(2, probe.calls("register"));
        f.jdbc.update("UPDATE arte_ai_search_checkpoint SET rounds=9,next_at=? WHERE generation=?", Long.MAX_VALUE, G);
        worker(probe.store).tick(G);
        assertEquals(3, probe.calls("register"));
        assertTrue(probe.lastCheckpoints.values().stream().allMatch(cp -> cp.rounds() == 9));
    }

    @Test
    public void changingGenerationRevalidatesAndKeepsProgressIsolated() {
        var worker = worker(probe.store);
        worker.tick(G);
        f.jdbc.update("UPDATE arte_ai_search_checkpoint SET rounds=7,next_at=? WHERE generation=?", Long.MAX_VALUE, G);
        worker.tick("search-test-g02");
        worker.tick("search-test-g02");
        assertEquals(2, probe.calls("register"));
        worker.tick(G);
        assertEquals(3, probe.calls("register"));
        assertTrue(probe.lastCheckpoints.values().stream().allMatch(cp -> cp.rounds() == 7));
    }

    @Test
    public void competingInstanceCannotUseCachedInitializationAsLeaseOwnership() {
        var worker = worker(probe.store);
        worker.tick(G);
        var other = f.store.acquire(G, "other-instance", Duration.ofSeconds(60));
        assertNotNull(other);
        var skipped = worker.tick(G);
        assertFalse(skipped.acquired());
        assertEquals(1, probe.calls("register"));
        assertEquals(1, probe.calls("checkpoints"));
        f.store.release(other);
        assertTrue(worker.tick(G).acquired());
        assertEquals(2, probe.calls("checkpoints"));
    }

    @Test
    public void checkpointSnapshotRejectsStaleLeaseAndMissingLane() {
        f.store.register(G, "v2");
        var old = f.store.acquire(G, "old", Duration.ofSeconds(60));
        f.jdbc.update("UPDATE arte_ai_search_generation SET lease_until=0 WHERE generation=?", G);
        var current = f.store.acquire(G, "current", Duration.ofSeconds(60));
        assertThrows(LostLease.class, () -> f.store.checkpoints(old));
        assertEquals(4, f.store.checkpoints(current).size());
        f.jdbc.update("DELETE FROM arte_ai_search_checkpoint WHERE generation=? AND lane='I'", G);
        assertThrows(IllegalStateException.class, () -> f.store.checkpoints(current));
        f.store.release(current);
    }

    @Test
    public void concurrentTicksShareOnlySuccessfulInitialization() throws Exception {
        var worker = worker(probe.store);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = List.of(pool.submit(() -> {
                        start.await();
                        return worker.tick(G);
                    }),
                    pool.submit(() -> {
                        start.await();
                        return worker.tick(G);
                    }));
            start.countDown();
            for (var result : results) result.get(5, TimeUnit.SECONDS);
            assertEquals(1, probe.calls("register"));
            assertEquals(2, probe.calls("acquire"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void steadyIdleTickIncludesQueueDeadlineReadWithOneCheckpointReadAndNoRegistration() {
        var dataSource = new CountingDataSource();
        var store = new MybatisConversationSearchSyncStore(dataSource);
        var worker = worker(store);
        worker.tick(G);
        f.jdbc.update("UPDATE arte_ai_search_checkpoint SET rounds=1,next_at=? WHERE generation=?", Long.MAX_VALUE, G);
        dataSource.sql.clear();
        worker.tick(G);
        assertEquals(dataSource.sql.toString(), 13, dataSource.sql.size());
        assertEquals(1, dataSource.sql.stream().filter(sql -> sql.contains("lane IN ('C','T','I','E')")).count());
        assertTrue(dataSource.sql.stream().noneMatch(sql -> sql.startsWith("INSERT IGNORE")));
    }

    @Test
    public void retryDeadlineIncludesFutureWorkAndIgnoresCompletedTasks() {
        f.store.register(G, "v2");
        var lease = f.store.acquire(G, "deadline", Duration.ofSeconds(60));
        assertNull(f.store.nextWorkAt(lease));
        var units = List.of(new Unit(Kind.C, "a".repeat(64)), new Unit(Kind.C, "b".repeat(64)));
        f.store.discovered(lease, "C", units, units.getLast().key(), false, Duration.ZERO);
        var pending = f.store.pending(lease, 2);
        f.store.fail(lease, pending.getFirst(), "TEST_RETRY", Duration.ofSeconds(10));
        f.store.fail(lease, pending.getLast(), "TEST_RETRY", Duration.ofSeconds(20));
        assertTrue(f.store.pending(lease, 2).isEmpty());
        long first = f.store.nextWorkAt(lease);
        assertTrue(first > f.store.now());
        f.store.complete(lease, pending.getFirst(), null);
        assertTrue(f.store.nextWorkAt(lease) > first);
        f.store.release(lease);
        assertThrows(LostLease.class, () -> f.store.nextWorkAt(lease));
    }

    @Test
    public void lifecycleUsesTheLongerDelayOnlyAfterACompleteQuietRound() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        var lifecycle = new com.arte.ainew.config.ConversationSearchScanLifecycle(worker(probe.store), properties("search-test", G),
                reactor.core.scheduler.Schedulers.immediate());
        try {
            lifecycle.start();
            assertEquals(1, probe.calls("acquire"));
            clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(2, probe.calls("acquire"));
            clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(3, probe.calls("acquire"));
            clock.advanceTimeBy(Duration.ofMillis(199));
            assertEquals(3, probe.calls("acquire"));
            f.conversation("after-idle", "new title");
            f.due();
            clock.advanceTimeBy(Duration.ofMillis(1));
            assertEquals(4, probe.calls("acquire"));
            assertEquals(Long.valueOf(1), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_entry", Long.class));
            clock.advanceTimeBy(Duration.ofMillis(99));
            assertEquals(4, probe.calls("acquire"));
            clock.advanceTimeBy(Duration.ofMillis(1));
            assertEquals(5, probe.calls("acquire"));
            lifecycle.stop();
            clock.advanceTimeBy(Duration.ofSeconds(10));
            assertEquals(5, probe.calls("acquire"));
        } finally {
            lifecycle.stop();
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    public void lifecycleBacksOffFailedTicksAndResumesAtActiveIntervalAfterRecovery() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        probe.registrationFailures.set(2);
        var lifecycle = new com.arte.ainew.config.ConversationSearchScanLifecycle(worker(probe.store), properties("search-test", G),
                reactor.core.scheduler.Schedulers.immediate());
        try {
            lifecycle.start();
            assertEquals(1, probe.calls("register"));
            clock.advanceTimeBy(Duration.ofMillis(99));
            assertEquals(1, probe.calls("register"));
            clock.advanceTimeBy(Duration.ofMillis(1));
            assertEquals(2, probe.calls("register"));
            clock.advanceTimeBy(Duration.ofMillis(199));
            assertEquals(2, probe.calls("register"));
            clock.advanceTimeBy(Duration.ofMillis(1));
            assertEquals(3, probe.calls("register"));
            assertEquals(1, probe.calls("acquire"));
            clock.advanceTimeBy(Duration.ofMillis(100));
            assertEquals(2, probe.calls("acquire"));
            assertEquals(3, probe.calls("register"));
        } finally {
            lifecycle.stop();
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    @Test
    public void backfillOnlyLifecycleStopsWhenAllCheckpointsAndWorkAreComplete() {
        var clock = reactor.test.scheduler.VirtualTimeScheduler.getOrSet();
        var base = properties("search-test", G);
        var config = new com.arte.ainew.config.NewAiSearchProperties(true, false, false, true, false, base.index(), null, null, base.sync());
        var worker = new ConversationSearchSyncWorker(f.source, probe.store, new SearchSyncWorkerTest.MemoryWriter(), config);
        var lifecycle = new com.arte.ainew.config.ConversationSearchScanLifecycle(worker, config, reactor.core.scheduler.Schedulers.immediate());
        try {
            lifecycle.start();
            clock.advanceTimeBy(Duration.ofSeconds(5));
            int ticks = probe.calls("acquire");
            assertTrue(ticks >= 4);
            assertEquals("READY", f.store.status(G).state());
            clock.advanceTimeBy(Duration.ofSeconds(10));
            assertEquals(ticks, probe.calls("acquire"));
        } finally {
            lifecycle.stop();
            reactor.test.scheduler.VirtualTimeScheduler.reset();
        }
    }

    private final class StoreProbe {
        final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        final AtomicInteger registrationFailures = new AtomicInteger();
        volatile Map<String, Checkpoint> lastCheckpoints;
        final ConversationSearchSyncStore store = (ConversationSearchSyncStore) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ConversationSearchSyncStore.class}, (proxy, method, args) -> {
                    counts.computeIfAbsent(method.getName(), name -> new AtomicInteger()).incrementAndGet();
                    if (method.getName().equals("register") && registrationFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
                        throw new IllegalStateException("Injected initialization failure");
                    try {
                        var result = method.invoke(f.store, args);
                        if (method.getName().equals("checkpoints")) {
                            @SuppressWarnings("unchecked") var checkpoints = (Map<String, Checkpoint>) result;
                            lastCheckpoints = checkpoints;
                        }
                        return result;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });

        int calls(String name) {
            return counts.getOrDefault(name, new AtomicInteger()).get();
        }
    }

    private final class CountingDataSource extends DelegatingDataSource {
        final List<String> sql = new CopyOnWriteArrayList<>();

        CountingDataSource() {
            super(f.ds);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var target = super.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement"))
                    sql.add(((String) args[0]).replaceAll("\\s+", " ").trim());
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            });
        }
    }
}
