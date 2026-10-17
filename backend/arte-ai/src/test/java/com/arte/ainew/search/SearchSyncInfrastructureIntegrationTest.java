package com.arte.ainew.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.arte.ainew.application.search.ConversationSearchMaintenance;
import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.infrastructure.search.ConversationSearchBootstrap;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchWriter;
import com.arte.ainew.persistence.mybatis.JdbcConversationSearchIndexLock;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSource;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSyncStore;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.*;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static com.arte.ainew.search.SearchSyncFixture.*;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

/**
 * 显式启用；真实 MySQL 随机数据库、ES 随机索引，finally 清理。
 */
public class SearchSyncInfrastructureIntegrationTest {
    private String database, prefix, g;
    private DriverManagerDataSource admin;
    private com.alibaba.druid.pool.DruidDataSource pool;
    private Rest5ClientTransport transport;
    private ElasticsearchClient es;
    private SearchSyncFixture f;
    private ElasticsearchConversationSearchWriter writer;
    private ConversationSearchSyncWorker worker;
    private ConversationSearchMaintenance maintenance;

    @Before
    public void setup() throws Exception {
        String jdbc = System.getenv("ARTE_SEARCH_S2_IT_MYSQL_URL"), url = System.getenv("ARTE_SEARCH_IT_ES_URL");
        Assume.assumeTrue(jdbc != null && url != null);
        if (!jdbc.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/mysql(?:\\?.*)?"))
            throw new IllegalArgumentException("Isolated localhost MySQL admin URL required");
        admin = new DriverManagerDataSource(jdbc, System.getenv("ARTE_SEARCH_S2_IT_MYSQL_USER"), System.getenv("ARTE_SEARCH_S2_IT_MYSQL_PASSWORD"));
        database = "arte_search_s2_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        pool = new com.alibaba.druid.pool.DruidDataSource();
        pool.setUrl(jdbc.replace("/mysql", "/" + database));
        pool.setUsername(System.getenv("ARTE_SEARCH_S2_IT_MYSQL_USER"));
        pool.setPassword(System.getenv("ARTE_SEARCH_S2_IT_MYSQL_PASSWORD"));
        pool.setMaxActive(8);
        pool.setMinIdle(0);
        pool.setInitialSize(0);
        pool.setMaxWait(5000);
        f = new SearchSyncFixture(pool);
        prefix = "arte-search-s2-" + UUID.randomUUID().toString().replace("-", "");
        g = prefix + "-g01";
        var builder = Rest5Client.builder(HttpHost.create(url));
        String user = System.getenv("ARTE_SEARCH_IT_ES_USERNAME"), password = System.getenv("ARTE_SEARCH_IT_ES_PASSWORD");
        if (user != null && password != null)
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))});
        transport = new Rest5ClientTransport(builder.build(), new Jackson3JsonpMapper(JsonMapper.builder().build()));
        es = new ElasticsearchClient(transport);
        var cfg = properties(prefix, g);
        writer = new ElasticsearchConversationSearchWriter(es, cfg);
        writer.createGeneration(g);
        worker = new ConversationSearchSyncWorker(f.source, f.store, writer, cfg);
        maintenance = new ConversationSearchMaintenance(writer, f.store, cfg, Schedulers.boundedElastic(), worker, new JdbcConversationSearchIndexLock(f.ds, cfg));
    }

    @After
    public void cleanup() throws Exception {
        try {
            if (es != null && prefix != null) for (String name : List.of(prefix + "-g01", prefix + "-g02"))
                if (es.indices().exists(b -> b.index(name)).value()) es.indices().delete(b -> b.index(name));
        } finally {
            try {
                if (transport != null) transport.close();
            } finally {
                if (pool != null) pool.close();
                if (admin != null && database != null)
                    new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    private void ticks(String generation, ConversationSearchSyncWorker w, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            f.due();
            w.tick(generation);
        }
        es.indices().refresh(b -> b.index(generation));
    }

    private long count(String generation) throws Exception {
        return es.count(b -> b.index(generation)).count();
    }

    private com.arte.ainew.config.NewAiSearchProperties initializationProperties(String generation) {
        var base = properties(prefix, generation);
        return new com.arte.ainew.config.NewAiSearchProperties(true, true, false, false, false,
                base.index(), base.session(), base.limits(), base.sync(), true);
    }

    private ConversationSearchBootstrap bootstrap(String generation) {
        var p = initializationProperties(generation);
        return new ConversationSearchBootstrap(es, new JdbcConversationSearchIndexLock(f.ds, p), f.store,
                new ElasticsearchConversationSearchWriter(es, p), p);
    }

    private void assertAliases(String generation) throws Exception {
        assertEquals(Set.of(generation), es.indices().getAlias(b -> b.name(prefix + "-read")).aliases().keySet());
        var write = es.indices().getAlias(b -> b.name(prefix + "-write")).aliases();
        assertEquals(Set.of(generation), write.keySet());
        assertEquals(Boolean.TRUE, write.get(generation).aliases().get(prefix + "-write").isWriteIndex());
    }

    @Test
    public void startupCreatesFirstGenerationAndReusesItWithoutResettingProgress() throws Exception {
        es.indices().delete(b -> b.index(g));
        var first = bootstrap(g);
        assertFalse(first.isReady());
        first.initialize();
        assertTrue(first.isReady());
        assertAliases(g);
        String uuid = es.indices().get(b -> b.index(g)).get(g).settings().index().uuid();
        f.conversation("bootstrap", "startup searchable");
        ticks(g, worker, 18);
        long rounds = f.store.status(g).completedRounds();
        bootstrap(g).initialize();
        assertEquals(uuid, es.indices().get(b -> b.index(g)).get(g).settings().index().uuid());
        assertEquals(rounds, f.store.status(g).completedRounds());
        assertEquals(1, count(g));
    }

    @Test
    public void startupResumesAfterRegistrationFailureAndRepairsOnlyMissingAlias() throws Exception {
        var p = initializationProperties(g);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        var store = (com.arte.ainew.spi.search.ConversationSearchSyncStore) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{com.arte.ainew.spi.search.ConversationSearchSyncStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("register") && !once.getAndSet(true))
                        throw new IllegalStateException("simulated registration failure");
                    return method.invoke(f.store, args);
                });
        var init = new ConversationSearchBootstrap(es, new JdbcConversationSearchIndexLock(f.ds, p), store, writer, p);
        assertThrows(IllegalStateException.class, init::initialize);
        assertFalse(init.isReady());
        assertEquals("NOT_READY", f.store.status(g).state());
        assertFalse(es.indices().existsAlias(b -> b.name(prefix + "-read")).value());
        init.initialize();
        assertAliases(g);
        es.indices().deleteAlias(b -> b.index(g).name(prefix + "-write"));
        bootstrap(g).initialize();
        assertAliases(g);
        es.indices().deleteAlias(b -> b.index(g).name(prefix + "-read"));
        bootstrap(g).initialize();
        assertAliases(g);
    }

    @Test
    public void startupConcurrentInstancesCreateOneIndexAndOneAliasPair() throws Exception {
        es.indices().delete(b -> b.index(g));
        var pool = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 4; i++)
                futures.add(pool.submit(() -> {
                    start.await();
                    var init = bootstrap(g);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!init.isReady()) {
                        try {
                            init.initialize();
                        } catch (IllegalStateException busy) {
                            if (!"SEARCH_INDEX_MAINTENANCE_BUSY".equals(busy.getMessage()) || System.nanoTime() > deadline)
                                throw busy;
                            Thread.sleep(50);
                        }
                    }
                    return null;
                }));
            start.countDown();
            for (var future : futures) future.get(25, TimeUnit.SECONDS);
            assertAliases(g);
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_generation", Integer.class).intValue());
            assertEquals(4, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_search_checkpoint", Integer.class).intValue());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void startupKeepsActiveAliasesDuringExplicitRebuildAndDoesNotInventNewGeneration() throws Exception {
        bootstrap(g).initialize();
        String next = prefix + "-g02";
        var unprepared = bootstrap(next);
        assertEquals("REBUILD_TARGET_NOT_PREPARED", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, unprepared::initialize).getMessage());
        assertFalse(es.indices().exists(b -> b.index(next)).value());
        writer.createGeneration(next);
        bootstrap(next).initialize();
        assertAliases(g);
        assertEquals("SCANNING", f.store.status(next).state());
    }

    @Test
    public void startupRejectsExistingWrongSchemaWithoutChangingItOrBindingAliases() throws Exception {
        es.indices().delete(b -> b.index(g));
        es.indices().create(b -> b.index(g).mappings(m -> m.properties("title", v -> v.keyword(k -> k))));
        var init = bootstrap(g);
        assertEquals("MAPPING_MISMATCH", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, init::initialize).getMessage());
        assertFalse(init.isReady());
        assertTrue(es.indices().getMapping(b -> b.index(g)).get(g).mappings().properties().get("title").isKeyword());
        assertFalse(es.indices().existsAlias(b -> b.name(prefix + "-read")).value());
        assertEquals("NOT_READY", f.store.status(g).state());
    }

    @Test
    public void startupRejectsAnalyzerChangesAndAliasMultipleTargets() throws Exception {
        var base = initializationProperties(g);
        var index = new com.arte.ainew.config.NewAiSearchProperties.Index(prefix + "-read", prefix + "-write", prefix,
                "ik_max_word", "ik_smart", null, null, null, 1, 0);
        var changed = new com.arte.ainew.config.NewAiSearchProperties(true, true, false, false, false,
                index, base.session(), base.limits(), base.sync(), true);
        var init = new ConversationSearchBootstrap(es, new JdbcConversationSearchIndexLock(f.ds, changed), f.store, writer, changed);
        assertEquals("ANALYZER_MISMATCH", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, init::initialize).getMessage());
        String next = prefix + "-g02";
        writer.createGeneration(next);
        es.indices().updateAliases(b -> b.actions(a -> a.add(v -> v.index(g).alias(prefix + "-read")))
                .actions(a -> a.add(v -> v.index(next).alias(prefix + "-read"))));
        assertEquals("ALIAS_MULTIPLE_TARGETS", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, bootstrap(g)::initialize).getMessage());
    }

    @Test
    public void explicitActivationUsesTheSameAliasLockAsStartup() throws Exception {
        var lock = new JdbcConversationSearchIndexLock(f.ds, initializationProperties(g));
        lock.locked(() -> {
            var failure = assertThrows(IllegalStateException.class, () -> maintenance.activate(g, 1).block());
            assertEquals("SEARCH_INDEX_MAINTENANCE_BUSY", failure.getMessage());
            return null;
        });
        // 锁释放后初始化可继续；被拒的激活没有抢先登记或消费校准凭据。
        assertEquals("NOT_READY", f.store.status(g).state());
        bootstrap(g).initialize();
        assertAliases(g);
    }

    @Test
    public void startupRejectsMissingIndexWithOldLedgerAndDisagreeingAliases() throws Exception {
        f.store.register(g, "v2");
        es.indices().delete(b -> b.index(g));
        var init = bootstrap(g);
        assertEquals("REGISTERED_INDEX_MISSING", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, init::initialize).getMessage());
        assertFalse(es.indices().exists(b -> b.index(g)).value());
        writer.createGeneration(g);
        String next = prefix + "-g02";
        writer.createGeneration(next);
        es.indices().updateAliases(b -> b.actions(a -> a.add(v -> v.index(g).alias(prefix + "-read")))
                .actions(a -> a.add(v -> v.index(next).alias(prefix + "-write").isWriteIndex(true))));
        assertEquals("ALIASES_DISAGREE", assertThrows(ConversationSearchBootstrap.InitializationFailure.class, init::initialize).getMessage());
        assertEquals(Set.of(next), es.indices().getAlias(b -> b.name(prefix + "-write")).aliases().keySet());
    }

    @Test
    public void initializationFailureRetriesOffThreadAndQueriesAreUnavailableUntilReady() throws Exception {
        var p = initializationProperties(g);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var delegate = new JdbcConversationSearchIndexLock(f.ds, p);
        var lock = new com.arte.ainew.spi.search.ConversationSearchIndexLock() {
            public <T> T locked(Callable<T> action) throws Exception {
                if (calls.incrementAndGet() < 3) throw new IllegalStateException("temporary dependency failure");
                return delegate.locked(action);
            }
        };
        var init = new ConversationSearchBootstrap(es, lock, f.store, writer, p);
        var scheduler = Schedulers.newBoundedElastic(1, 20, "search-init-regression");
        var lifecycle = new com.arte.ainew.config.ConversationSearchScanLifecycle(worker, p, scheduler, init);
        var index = new ElasticsearchConversationSearchIndex(es, p, scheduler, init::isReady);
        try {
            var failure = assertThrows(com.arte.ainew.common.search.SearchFailure.class, () -> index.open(Duration.ofSeconds(10)).block());
            assertEquals(com.arte.ainew.common.search.SearchFailure.Code.DEPENDENCY_UNAVAILABLE, failure.code());
            lifecycle.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!init.isReady()) {
                assertTrue("initialization did not recover", System.nanoTime() < deadline);
                Thread.sleep(25);
            }
            var pit = index.open(Duration.ofSeconds(10)).block();
            assertNotNull(pit);
            index.close(pit).block();
            assertEquals(0, f.store.status(g).completedRounds());
            assertEquals(0, f.store.status(g).entries());
            assertEquals(3, calls.get());
        } finally {
            lifecycle.stop();
            scheduler.dispose();
        }
    }

    @Test
    public void scheduledSecondRoundUsesDatabaseEpochWithoutForcingCheckpointsDue() throws Exception {
        assertTrue("Database epoch must ignore JDBC/session timezone mismatch", Math.abs(f.store.now() - System.currentTimeMillis()) < 2500);
        f.conversation("clock", "before-clock-change");
        var scheduler = Schedulers.newBoundedElastic(1, 20, "search-clock-regression");
        var lifecycle = new com.arte.ainew.config.ConversationSearchScanLifecycle(worker, properties(prefix, g), scheduler);
        try {
            lifecycle.start();
            String id = SearchIndexRecord.title(OWNER, "clock", "before-clock-change", AT, 1).entryKey();
            long deadline = System.currentTimeMillis() + 20000;
            while (!es.get(b -> b.index(g).id(id), Map.class).found()) {
                assertTrue(System.currentTimeMillis() < deadline);
                Thread.sleep(100);
            }
            f.rename("clock", "after-clock-change");
            while (true) {
                var result = es.get(b -> b.index(g).id(id), Map.class);
                if ("after-clock-change".equals(result.source().get("title"))) break;
                assertTrue("Continuous scan did not resume after its first round", System.currentTimeMillis() < deadline);
                Thread.sleep(100);
            }
        } finally {
            lifecycle.stop();
            scheduler.dispose();
        }
    }

    @Test
    public void mysqlLateCommitBehindCheckpointRestartAndLeaseFencingWithVersionedEsWrites() throws Exception {
        f.conversation("conv", "current title");
        f.turn("conv", "turn", "用户问题", List.of("answer"));
        f.invocation("conv", "turn", "answer", "耐久部分回答", true, true);
        ticks(g, worker, 18);
        assertEquals(3, count(g));
        assertEquals("READY", f.store.status(g).state());
        // 已扫边界之后才提交的旧键，没有全局提交序；下一轮必须补回。
        var connection = f.ds.getConnection();
        connection.setAutoCommit(false);
        try {
            String lateId = java.util.stream.IntStream.range(0, 10000).mapToObj(i -> "late-" + i).filter(id -> key(id).compareTo(key("conv")) < 0).findFirst().orElseThrow();
            var late = new Conversation(lateId, OWNER, "late committed title", 0, null, List.of(), Conversation.State.ACTIVE, AT.minusSeconds(3600), AT.minusSeconds(3600));
            try (var statement = connection.prepareStatement("INSERT INTO arte_ai_conversation_new(id_key,owner_key,version_no,workspace_snapshot) VALUES(?,?,0,?)")) {
                statement.setString(1, key(lateId));
                statement.setString(2, key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId()));
                statement.setString(3, f.codec.encode(late));
                statement.executeUpdate();
            }
            f.jdbc.update("UPDATE arte_ai_search_checkpoint SET cursor_key=?,next_at=0 WHERE generation=? AND lane='C'", key("conv"), g);
            worker.tick(g);
            connection.commit();
        } finally {
            connection.close();
        }
        worker = new ConversationSearchSyncWorker(new MybatisConversationSearchSource(f.ds, f.codec, (o, ids) -> reactor.core.publisher.Mono.just(Set.of())), new MybatisConversationSearchSyncStore(f.ds), writer, properties(prefix, g));
        ticks(g, worker, 18);
        assertEquals(4, count(g));
        var unit = new Unit(Kind.C, key("conv"));
        var work = new Work(unit, new Scope(OWNER, "conv"), false, 0);
        var old = f.store.acquire(g, "old-read", Duration.ofSeconds(60));
        var stale = f.source.read(unit, work.scope()).records().getFirst();
        var entry = f.store.entries(old, unit).getFirst();
        f.jdbc.update("UPDATE arte_ai_search_generation SET lease_until=0 WHERE generation=?", g);
        var newer = f.store.acquire(g, "new-read", Duration.ofSeconds(60));
        f.rename("conv", "updated title");
        var current = f.source.read(unit, work.scope()).records().getFirst();
        var next = f.store.stage(newer, work, work.scope(), SearchSyncWorkerTest.identity(current), "f".repeat(64), Desired.LIVE);
        assertTrue(writer.write(g, List.of(new Mutation(ConversationSearchSyncWorker.copy(current, next.revision(), true), Desired.LIVE))).getFirst().success());
        assertThrows(LostLease.class, () -> f.store.stage(old, work, work.scope(), SearchSyncWorkerTest.identity(stale), "e".repeat(64), Desired.LIVE));
        // 实际并发迟到请求不允许覆盖更高版本；旧删除也不能删除恢复后的正文。
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> writer.write(g, List.of(new Mutation(ConversationSearchSyncWorker.copy(stale, entry.revision(), true), Desired.LIVE))).getFirst());
            var two = pool.submit(() -> writer.write(g, List.of(new Mutation(ConversationSearchSyncWorker.copy(stale, entry.revision(), false), Desired.DELETE))).getFirst());
            assertFalse(one.get(10, TimeUnit.SECONDS).success());
            assertFalse(two.get(10, TimeUnit.SECONDS).success());
        } finally {
            pool.shutdownNow();
            f.store.release(newer);
        }
        var doc = es.get(b -> b.index(g).id(current.entryKey()), Map.class);
        assertEquals("updated title", doc.source().get("title"));
        assertEquals(Long.valueOf(0), f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox", Long.class));
    }

    @Test
    public void realBulkPartialFailureArchiveStraysRestoreAndIndependentGenerationActivation() throws Exception {
        f.conversation("conv", "title");
        f.turn("conv", "turn", "question", List.of("one", "two"));
        f.invocation("conv", "turn", "one", "old partial", true, true);
        f.invocation("conv", "turn", "two", "new complete", false, true);
        ticks(g, worker, 18);
        assertEquals(4, count(g));
        var good = SearchIndexRecord.title(OWNER, "bulk-good", "good", AT, 1);
        var bad = SearchIndexRecord.title(OWNER, "bulk-bad", "bad", Instant.parse("3000-01-01T00:00:00Z"), 1);
        var result = writer.write(g, List.of(new Mutation(good, Desired.LIVE), new Mutation(bad, Desired.LIVE)));
        assertTrue(result.get(0).success());
        assertFalse(result.get(1).success());
        es.delete(b -> b.index(g).id(good.entryKey()));
        var original = f.source.read(new Unit(Kind.C, key("conv")), null).records().getFirst();
        f.archived.add("conv");
        ticks(g, worker, 20);
        assertEquals(0, count(g));
        // 模拟 ES 删除版本保留期以后迟到的旧请求，周期校准必须再次物理清理。
        es.index(b -> b.index(g).id(original.entryKey()).document(original.source()));
        var unknown = SearchIndexRecord.message(OWNER, "conv", "unknown-turn", "unknown-msg", "unknown-candidate", 7, 99, "unknown historical stray", AT, true, 1);
        es.index(b -> b.index(g).id(unknown.entryKey()).document(unknown.source()));
        es.indices().refresh(b -> b.index(g));
        assertEquals(2, count(g));
        assertThrows(LostLease.class, () -> writer.purgeArchived(g, new Scope(OWNER, "conv"), () -> {
            throw new LostLease();
        }));
        assertEquals(2, count(g));
        var strayBulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        for (int n = 0; n < 205; n++) {
            var stray = SearchIndexRecord.message(OWNER, "conv", "stray-turn-" + n, "stray-message-" + n, "stray-invocation-" + n, 8, n + 100,
                    "old archived stray", AT, true, 1);
            strayBulk.operations(op -> op.index(i -> i.index(g).id(stray.entryKey()).document(stray.source())));
        }
        assertFalse(es.bulk(strayBulk.build()).errors());
        es.indices().refresh(b -> b.index(g));
        assertEquals(207, count(g));
        for (int n = 0; n < 20 && count(g) > 0; n++) ticks(g, worker, 1);
        ticks(g, worker, 25);
        assertEquals(0, count(g));
        String next = prefix + "-g02";
        writer.createGeneration(next);
        var nextWorker = new ConversationSearchSyncWorker(f.source, f.store, writer, properties(prefix, next));
        ticks(next, nextWorker, 18);
        assertEquals(0, count(next));
        assertEquals("READY", f.store.status(next).state());
        f.archived.clear();
        ticks(g, worker, 18);
        ticks(next, nextWorker, 18);
        assertEquals(4, count(g));
        assertEquals(4, count(next));
        long firstEpoch = maintenance.prepareActivation(g).block();
        ticks(g, worker, 18);
        maintenance.activate(g, firstEpoch).block();
        var reader = new ElasticsearchConversationSearchIndex(es, properties(prefix, g), Schedulers.boundedElastic());
        var pit = reader.open(Duration.ofMinutes(2)).block();
        try {
            long nextEpoch = maintenance.prepareActivation(next).block();
            ticks(next, nextWorker, 18);
            maintenance.activate(next, nextEpoch).block();
            assertEquals(Set.of(next), es.indices().getAlias(b -> b.name(prefix + "-read")).aliases().keySet());
            assertTrue(es.indices().exists(b -> b.index(g)).value());
            assertEquals(g, pit.generation());
            var hits = reader.read(new com.arte.ainew.spi.search.ConversationSearchIndex.Read(OWNER, "question", List.of(), pit, com.arte.ainew.pojo.search.ConversationSearch.Direction.FORWARD, List.of(), 10, Duration.ofMinutes(2))).block();
            pit = hits.pit();
            assertFalse(hits.hits().isEmpty());
            // S5：回退同样重新校准，不能让旧代际中的已归档正文随 alias 恢复。
            var secondPit = reader.open(Duration.ofMinutes(2)).block();
            try {
                assertEquals(next, secondPit.generation());
                f.archived.add("conv");
                long rollbackEpoch = maintenance.prepareActivation(g).block();
                ticks(g, worker, 22);
                maintenance.activate(g, rollbackEpoch).block();
                assertEquals(Set.of(g), es.indices().getAlias(b -> b.name(prefix + "-read")).aliases().keySet());
                assertEquals(0, count(g));
                assertTrue(es.indices().exists(b -> b.index(next)).value());
                assertEquals(next, secondPit.generation());
                var rolledBackPit = reader.open(Duration.ofMinutes(2)).block();
                try {
                    assertEquals(g, rolledBackPit.generation());
                } finally {
                    reader.close(rolledBackPit).block();
                }
            } finally {
                reader.close(secondPit).block();
            }
        } finally {
            reader.close(pit).block();
        }
    }
}
