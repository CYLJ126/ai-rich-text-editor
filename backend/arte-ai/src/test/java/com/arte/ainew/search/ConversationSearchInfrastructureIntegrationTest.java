package com.arte.ainew.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.arte.ainew.application.search.DefaultConversationSearchService;
import com.arte.ainew.application.search.SearchReadAuthorization;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchWriter;
import com.arte.ainew.infrastructure.search.RedisConversationSearchSessions;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSession;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

/**
 * 三个真实依赖，只创建随机库／索引／Redis 命名空间；不调用模型或扫描业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public class ConversationSearchInfrastructureIntegrationTest {

    DriverManagerDataSource admin;
    String database, prefix, g, namespace;
    SearchSyncFixture f;
    Rest5ClientTransport transport;
    ElasticsearchClient es;
    RedissonClient redis;
    Scheduler io, app, cleanup;
    DefaultConversationSearchService service;
    RedisConversationSearchSessions sessions;
    ElasticsearchConversationSearchWriter writer;
    NewAiSearchProperties p;
    final Set<String> searches = new HashSet<>();

    @Before
    public void setup() throws Exception {
        setup("s3");
    }

    void setup(String stage) throws Exception {
        if (!stage.matches("s[1-5]")) throw new IllegalArgumentException("Invalid acceptance stage");
        String jdbc = System.getenv("ARTE_SEARCH_S2_IT_MYSQL_URL"), url = System.getenv("ARTE_SEARCH_IT_ES_URL"), redisUrl = System.getenv("ARTE_SEARCH_IT_REDIS_URL");
        Assume.assumeTrue(jdbc != null && url != null && redisUrl != null);
        if (!jdbc.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/mysql(?:\\?.*)?"))
            throw new IllegalArgumentException("Isolated local MySQL admin URL required");
        admin = new DriverManagerDataSource(jdbc, System.getenv("ARTE_SEARCH_S2_IT_MYSQL_USER"), System.getenv("ARTE_SEARCH_S2_IT_MYSQL_PASSWORD"));
        database = "arte_search_" + stage + "_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(admin).execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        f = new SearchSyncFixture(new DriverManagerDataSource(jdbc.replace("/mysql", "/" + database), System.getenv("ARTE_SEARCH_S2_IT_MYSQL_USER"), System.getenv("ARTE_SEARCH_S2_IT_MYSQL_PASSWORD")));
        for (var table : List.of("arte_ai_search_entry", "arte_ai_search_work", "arte_ai_search_checkpoint", "arte_ai_search_generation"))
            f.jdbc.execute("DROP TABLE " + table);
        String id = UUID.randomUUID().toString().replace("-", "");
        prefix = "arte-search-" + stage + "-" + id;
        g = prefix + "-g01";
        namespace = "arte:search:" + stage + "it:" + id;
        var builder = Rest5Client.builder(HttpHost.create(url));
        String user = System.getenv("ARTE_SEARCH_IT_ES_USERNAME"), password = System.getenv("ARTE_SEARCH_IT_ES_PASSWORD");
        if (user != null && password != null)
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))});
        transport = new Rest5ClientTransport(builder.build(), new Jackson3JsonpMapper(JsonMapper.builder().build()));
        es = new ElasticsearchClient(transport);
        var cfg = new Config();
        cfg.setThreads(2).setNettyThreads(2);
        var server = cfg.useSingleServer().setAddress(redisUrl);
        String rp = System.getenv("ARTE_SEARCH_IT_REDIS_PASSWORD");
        if (rp != null && !rp.isEmpty()) server.setPassword(rp);
        redis = Redisson.create(cfg);
        io = Schedulers.newBoundedElastic(2, 100, "s3-it-es");
        app = Schedulers.newBoundedElastic(4, 100, "s3-it-app");
        cleanup = Schedulers.newBoundedElastic(1, 100, "s3-it-close");
        configure(Duration.ofMinutes(10), Duration.ofMinutes(30));
        writer = new ElasticsearchConversationSearchWriter(es, p);
        writer.createGeneration(g);
        es.indices().putAlias(b -> b.index(g).name(prefix + "-read"));
    }

    private void configure(Duration idle, Duration hard) {
        p = new NewAiSearchProperties(true, true, false, false, false, new NewAiSearchProperties.Index(prefix + "-read", prefix + "-write", prefix, null, null, null, null, null, 2, 0),
                new NewAiSearchProperties.Session(namespace, idle, hard, 3, Duration.ofSeconds(2)), null);
        sessions = new RedisConversationSearchSessions(redis, p);
        var auth = new SearchReadAuthorization((name, t, w, scopes) -> Mono.just(new ExecutionAuthorization(new ExecutionPrincipal(OWNER.subjectId(), name, ExecutionPrincipal.Kind.USER), t, w, scopes, "grant")), Clock.systemUTC());
        service = new DefaultConversationSearchService(new ElasticsearchConversationSearchIndex(es, p, io), sessions,
                new MybatisConversationSearchReads(f.ds, f.codec, (owner, ids) -> Mono.just(ids.stream().filter(f.archived::contains).collect(java.util.stream.Collectors.toSet()))), auth, p, app, cleanup, Clock.systemUTC());
    }

    private ExecutionContext context() {
        return new ExecutionContext(UUID.randomUUID().toString(), "trace", new ExecutionAuthorization(new ExecutionPrincipal(OWNER.subjectId(), "alice", ExecutionPrincipal.Kind.USER), OWNER.tenantId(), OWNER.workspaceId(), Set.of("ai:conversation", "ai:read"), "grant"), Instant.now().plusSeconds(60), null, null, null, null);
    }

    private ConversationSearch.Page page(int size, String cursor) {
        var page = service.search(new ConversationSearch.Query("marker", List.of(), size, cursor), context()).block(Duration.ofSeconds(5));
        searches.add(page.searchSessionId());
        return page;
    }

    private List<SearchIndexRecord> seed(int count) {
        f.conversation("conv", "current title");
        var records = new ArrayList<SearchIndexRecord>();
        var batch = new ArrayList<Object[]>();
        for (int n = 1; n <= count; n++) {
            String body = n == 5 ? "marker <script>alert('x')</script> & 👩‍💻" : "marker";
            var t = new Turn("turn-" + n, "conv", n, null, null, SearchSyncFixture.message("message-" + n, ChatMessage.Role.USER, body), List.of(), null, 0, AT, AT);
            batch.add(new Object[]{key(t.turnId()), key("conv"), n, f.codec.encode(t)});
            records.add(SearchIndexRecord.message(OWNER, "conv", t.turnId(), t.userMessage().messageId(), null, 0, n, body, n % 3 == 0 ? null : AT, false, 1));
        }
        f.jdbc.batchUpdate("INSERT INTO arte_ai_turn(id_key,conversation_key,sequence_no,workspace_snapshot) VALUES(?,?,?,?)", batch);
        return records;
    }

    private void index(String target, List<SearchIndexRecord> records) throws Exception {
        var bulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
        for (var r : records)
            bulk.operations(op -> op.index(i -> i.index(target).id(r.entryKey()).document(r.source())));
        assertFalse(es.bulk(bulk.build()).errors());
        es.indices().refresh(b -> b.index(target));
    }

    @After
    public void clean() throws Exception {
        try {
            if (service != null) for (String id : searches)
                try {
                    service.close(id, context()).block(Duration.ofSeconds(3));
                } catch (Exception ignored) {
                }
            if (redis != null) {
                redis.getKeys().deleteByPattern(namespace + "*");
                assertEquals(0, redis.getKeys().getKeysStreamByPattern(namespace + "*").count());
            }
            if (es != null && prefix != null) for (String target : List.of(prefix + "-g01", prefix + "-g02"))
                if (es.indices().exists(b -> b.index(target)).value()) es.indices().delete(b -> b.index(target));
        } finally {
            if (io != null) io.dispose();
            if (app != null) app.dispose();
            if (cleanup != null) cleanup.dispose();
            try {
                if (redis != null) redis.shutdown();
                if (transport != null) transport.close();
            } finally {
                if (admin != null && database != null)
                    new JdbcTemplate(admin).execute("DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    @Test
    public void multiShardForwardBackwardAndFirstKeepOriginalPitAcrossAliasSwitchWithNoLostPrefetch() throws Exception {
        var records = seed(145);
        index(g, records);
        var first = page(7, null);
        var second = page(7, first.nextCursor());
        assertEquals(first.records(), page(7, second.previousCursor()).records());
        assertEquals(second.records(), page(7, first.nextCursor()).records());
        String next = prefix + "-g02";
        writer.createGeneration(next);
        index(next, records);
        es.indices().updateAliases(b -> b.actions(a -> a.remove(r -> r.index(g).alias(prefix + "-read"))).actions(a -> a.add(v -> v.index(next).alias(prefix + "-read"))));
        var seen = new LinkedHashSet<String>();
        var page = page(7, first.firstCursor());
        int pages = 0;
        while (true) {
            for (var item : page.records()) {
                assertTrue(seen.add(item.entryKey()));
                assertEquals("current title", item.title());
                assertTrue(item.snippet().text().contains("marker"));
                assertFalse(item.snippet().highlights().isEmpty());
            }
            if (page.nextCursor() == null) break;
            assertTrue(++pages < 30);
            page = page(7, page.nextCursor());
        }
        assertEquals(145, seen.size());
        assertEquals(records.stream().map(SearchIndexRecord::entryKey).collect(java.util.stream.Collectors.toSet()), seen);
        var fresh = page(7, null);
        assertNotEquals(first.searchSessionId(), fresh.searchSessionId());
        assertEquals(g, session(first.searchSessionId()).generation());
        assertEquals(next, session(fresh.searchSessionId()).generation());
        var around = service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "turn-80", 0L, 5, null), context()).block();
        assertEquals("turn-80", around.records().get(2).turnId());
    }

    @Test
    public void realEsBudgetEmptyPageContinuesAfterOneHundredInvisibleCandidatesAndArchiveFiltersOldPit() throws Exception {
        var records = seed(1);
        var all = new ArrayList<SearchIndexRecord>();
        for (int n = 0; n < 105; n++)
            all.add(SearchIndexRecord.message(OWNER, "conv", "ghost-turn-" + n, "ghost-message-" + n, null, 0, n + 1000, "marker", AT.plusSeconds(1), false, 1));
        all.addAll(records);
        index(g, all);
        var first = page(7, null);
        assertTrue(first.records().isEmpty());
        assertTrue(first.partialPage());
        assertNotNull(first.nextCursor());
        var last = page(7, first.nextCursor());
        assertEquals(1, last.records().size());
        assertEquals("message-1", last.records().getFirst().messageId());
        assertNull(last.nextCursor());
        f.archived.add("conv");
        assertTrue(page(7, first.firstCursor()).records().isEmpty());
    }

    private SearchSession session(String id) {
        String name = redis.getKeys().getKeysStreamByPattern(namespace + ":*").filter(k -> k.endsWith(id)).findFirst().orElseThrow();
        return JsonMapper.builder().build().readValue(redis.<String>getBucket(name, StringCodec.INSTANCE).get(), SearchSession.class);
    }

    @Test
    public void realPitRenewalNearIdleBoundaryHonorsHardDeadlineAndLocationSurvivesExpiry() throws Exception {
        configure(Duration.ofSeconds(2), Duration.ofSeconds(6));
        index(g, seed(2));
        var first = page(1, null);
        var original = session(first.searchSessionId());
        Thread.sleep(1100);
        page(1, first.firstCursor());
        Thread.sleep(1100);
        assertFalse(page(1, first.firstCursor()).records().isEmpty());
        var renewed = session(first.searchSessionId());
        assertTrue(renewed.idleExpiresAt() > original.idleExpiresAt());
        assertTrue(renewed.idleExpiresAt() <= renewed.pitExpiresAt());
        assertTrue(renewed.idleExpiresAt() <= renewed.hardExpiresAt());
        while (System.currentTimeMillis() < original.hardExpiresAt() - 500) {
            Thread.sleep(650);
            if (System.currentTimeMillis() < original.hardExpiresAt() - 500) page(1, first.firstCursor());
        }
        Thread.sleep(Math.max(1, original.hardExpiresAt() - System.currentTimeMillis() + 100));
        assertEquals(SearchFailure.Code.SEARCH_EXPIRED, assertThrows(SearchFailure.class, () -> page(1, first.firstCursor())).code());
        assertEquals("turn-1", service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "turn-1", 0L, 1, null), context()).block().records().getFirst().turnId());
    }
}
