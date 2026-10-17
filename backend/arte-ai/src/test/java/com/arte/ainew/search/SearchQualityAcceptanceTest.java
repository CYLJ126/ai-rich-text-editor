package com.arte.ainew.search;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.execution.DefaultExecutionEventService;
import com.arte.ainew.application.search.ConversationSearchSyncWorker;
import com.arte.ainew.application.search.DefaultConversationSearchService;
import com.arte.ainew.application.search.SearchReadAuthorization;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.ConversationSearchScanLifecycle;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.ConversationHttpContext;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiConversationController;
import com.arte.ainew.web.controller.NewAiConversationSearchController;
import com.arte.ainew.web.controller.NewAiInvocationController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S5 固定标注集与真实投影链路。只使用随机隔离资源；不依赖业务账号，不调用模型。
 * 期望身份来自预先提交的 corpus.json，绝不由搜索结果生成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public class SearchQualityAcceptanceTest {

    final ConversationSearchInfrastructureIntegrationTest env = new ConversationSearchInfrastructureIntegrationTest();
    final JsonMapper json = JsonMapper.builder().build();
    final Map<String, SearchIndexRecord> targets = new LinkedHashMap<>();
    final Map<String, Object> report = new LinkedHashMap<>();
    final AtomicBoolean denied = new AtomicBoolean();
    final List<Map<String, Object>> monitor = new CopyOnWriteArrayList<>();
    JsonNode corpus;
    SearchSyncFixture f;
    DefaultConversationSearchService service;
    DefaultConversationService conversations;
    DefaultExecutionEventService events;
    ConversationSearchSyncWorker worker;
    ConversationSearchScanLifecycle lifecycle;
    NewAiSearchProperties config;
    MockMvc mvc;
    final Clock clock = Clock.systemUTC();
    ExecutionAuthorizationResolver resolver;
    long started;
    com.zaxxer.hikari.HikariDataSource pool;

    @Before
    public void setup() throws Exception {
        env.setup("s5");
        env.app.dispose();
        env.app = Schedulers.newBoundedElastic(2, 1000, "s5-it-app");
        pool = new com.zaxxer.hikari.HikariDataSource();
        pool.setJdbcUrl(((org.springframework.jdbc.datasource.DriverManagerDataSource) env.f.ds).getUrl());
        pool.setUsername(System.getenv("ARTE_SEARCH_S2_IT_MYSQL_USER"));
        pool.setPassword(System.getenv("ARTE_SEARCH_S2_IT_MYSQL_PASSWORD"));
        pool.setMaximumPoolSize(8);
        pool.setMinimumIdle(2);
        pool.setConnectionTimeout(5000);
        env.f = new SearchSyncFixture(pool, false);
        f = env.f;
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-search-sync-migration.sql")).execute(f.ds);
        config = new NewAiSearchProperties(true, true, true, true, true, env.p.index(), env.p.session(), env.p.limits(),
                new NewAiSearchProperties.Sync(env.g, null, null, null, null, null, null, null, null));
        resolver = (name, t, w, scopes) -> denied.get() ? Mono.error(new org.springframework.security.access.AccessDeniedException("test revoked")) :
                Mono.just(new ExecutionAuthorization(new ExecutionPrincipal(name.equals("alice") ? OWNER.subjectId() : name, name, ExecutionPrincipal.Kind.USER), t, w, scopes, "test-grant"));
        var reads = new MybatisConversationSearchReads(f.ds, f.codec, (o, ids) -> Mono.just(ids.stream().filter(f.archived::contains).collect(java.util.stream.Collectors.toSet())));
        service = new DefaultConversationSearchService(new ElasticsearchConversationSearchIndex(env.es, config, env.io), env.sessions, reads,
                new SearchReadAuthorization(resolver, clock), config, env.app, env.cleanup, clock);
        var store = new MybatisExecutionPersistence(f.ds, f.codec, env.app);
        conversations = new DefaultConversationService(store, new AdmissionAuthorization(resolver, null, clock), clock,
                (o, ids) -> Mono.just(ids.stream().filter(f.archived::contains).collect(java.util.stream.Collectors.toSet())));
        events = new DefaultExecutionEventService(new AdmissionAuthorization(resolver, null, clock), store, store,
                new MybatisPayloadPersistence(f.ds, f.codec, env.app), null, clock, null, null,
                (owner, ids) -> Mono.fromCallable(() -> reads.readableInvocations(owner, ids)).subscribeOn(env.app));
        var props = new NewAiProperties(true, "appDataSource", "release", new NewAiProperties.Persistence(2, 100),
                new NewAiProperties.Limits(1000, 1000, 10000, Duration.ofSeconds(30), Duration.ofMinutes(5)), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "DATABASE");
        var http = new NewAiHttpContext(new ExecutionContextFactory(resolver, clock), props);
        mvc = MockMvcBuilders.standaloneSetup(new NewAiConversationSearchController(service, http, props),
                        new NewAiConversationController(conversations, new ConversationHttpContext(http, props)),
                        new NewAiInvocationController(null, events, http, props, null))
                .setControllerAdvice(new ConversationExceptionHandler()).build();
        corpus = json.readTree(new ClassPathResource("search-s5/corpus.json").getInputStream());
        started = System.currentTimeMillis();
        report.put("startedAt", Instant.now().toString());
        report.put("corpusVersion", corpus.path("version").asString());
        report.put("corpusSha256", com.arte.ainew.serialization.CanonicalJson.sha256(new String(new ClassPathResource("search-s5/corpus.json").getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        report.put("server", env.es.info().version().number());
        report.put("indexRules", config.index().rulesVersion());
        report.put("isolatedResources", Map.of("database", env.database, "index", env.g, "redisNamespace", env.namespace));
        report.put("sync", config.sync());
        report.put("shards", config.index().shards());
        report.put("runtime", Map.of("java", System.getProperty("java.version"), "os", System.getProperty("os.name"), "arch", System.getProperty("os.arch"), "cpu", Runtime.getRuntime().availableProcessors(), "mysqlPoolMax", 8));
    }

    ExecutionContext context(String name, String space) {
        return new ExecutionContext(UUID.randomUUID().toString(), "s5", new ExecutionAuthorization(new ExecutionPrincipal(name.equals("alice") ? OWNER.subjectId() : name, name, ExecutionPrincipal.Kind.USER), OWNER.tenantId(), space, Set.of("ai:conversation", "ai:read"), "test-grant"), Instant.now().plusSeconds(30), null, null, null, null);
    }

    ExecutionContext context() {
        return context("alice", OWNER.workspaceId());
    }

    ConversationSearch.Page page(String q, String cursor, ExecutionContext c) {
        var page = service.search(new ConversationSearch.Query(q, List.of(), 20, cursor), c).block(Duration.ofSeconds(10));
        return page;
    }

    void close(ConversationSearch.Page p, ExecutionContext c) {
        service.close(p.searchSessionId(), c).block(Duration.ofSeconds(5));
    }

    void seed() {
        for (var s : corpus.path("sources")) {
            String id = s.path("id").asString(), kind = s.path("kind").asString(), text = s.path("text").asString();
            f.conversation(id, kind.equals("TITLE") ? text : "标注会话 " + id);
            if (kind.equals("TITLE")) targets.put(id, SearchIndexRecord.title(OWNER, id, text, AT, 1));
            else if (kind.equals("USER")) {
                f.turn(id, "turn-" + id, text, List.of());
                targets.put(id, SearchIndexRecord.message(OWNER, id, "turn-" + id, "user-turn-" + id, null, 0, 1, text, AT, false, 1));
            } else {
                var ids = s.path("twins").asBoolean() ? List.of(id, id + "-twin", "latest-" + id) : List.of(id);
                f.turn(id, "turn-" + id, "请分析案例 " + id, ids);
                for (int n = 0; n < ids.size(); n++) {
                    String candidate = ids.get(n), body = candidate.startsWith("latest-") ? "这是新的候选，没有重复历史内容" : text;
                    boolean partial = s.path("partial").asBoolean() && n == 0;
                    var inv = f.invocation(id, "turn-" + id, candidate, body, partial, true);
                    var a = new Attempt(inv.activeAttemptId(), candidate, 1, "s5", 1, AT.plusSeconds(60), 1, partial ? Attempt.State.CANCELLED : Attempt.State.SUCCEEDED,
                            Attempt.Dispatch.CONFIRMED, null, null, Usage.unknown(), null, AT, AT);
                    f.jdbc.update("INSERT INTO arte_ai_attempt(id_key,invocation_key,attempt_number,purpose,workspace_snapshot) VALUES(?,?,1,'EXECUTE',?)", key(a.attemptId()), key(candidate), f.codec.encode(a));
                    targets.put(candidate, SearchIndexRecord.message(OWNER, id, "turn-" + id, "answer-" + candidate, candidate, n + 1, 1, body, AT, partial, 1));
                }
            }
        }
        for (int n = 0; n < 40; n++) {
            String id = "page-%02d".formatted(n);
            f.conversation(id, "分页样本 " + n);
            f.turn(id, "turn-" + id, "灰度验收 公共分页材料 " + n, List.of());
            targets.put(id, SearchIndexRecord.message(OWNER, id, "turn-" + id, "user-turn-" + id, null, 0, 1, "灰度验收 公共分页材料 " + n, AT, false, 1));
        }
        for (int n = 0; n < 500; n++) {
            String id = "background-" + n;
            f.conversation(id, "日常记录 " + n);
            f.turn(id, "turn-" + id, "日常记录：天气晴朗，今日工作安排 " + n, List.of());
        }
        for (String id : List.of("foreign-user", "foreign-space")) {
            var owner = new ExecutionOwner(OWNER.tenantId(), id.equals("foreign-space") ? "other-space" : OWNER.workspaceId(), id.equals("foreign-user") ? "bob" : OWNER.subjectId());
            var c = new Conversation(id, owner, "灰度验收 旅行 行程 端口冲突", 0, null, List.of(), Conversation.State.ACTIVE, AT, AT);
            f.jdbc.update("INSERT INTO arte_ai_conversation_new(id_key,owner_key,version_no,workspace_snapshot) VALUES(?,?,0,?)", key(id), key(owner.tenantId(), owner.workspaceId(), owner.subjectId()), f.codec.encode(c));
            targets.put(id, SearchIndexRecord.title(owner, id, c.title(), AT, 1));
        }
        var metadata = new ArrayList<Object[]>();
        for (var raw : f.jdbc.queryForList("SELECT workspace_snapshot FROM arte_ai_conversation_new", String.class)) {
            var c = f.codec.decode(raw, Conversation.class);
            long micros = AT.getEpochSecond() * 1_000_000 + AT.getNano() / 1000;
            metadata.add(new Object[]{key(c.conversationId()), key(c.owner().tenantId(), c.owner().workspaceId(), c.owner().subjectId()), c.conversationId(), c.title(), micros, micros});
        }
        f.jdbc.batchUpdate("INSERT INTO arte_ai_conversation_metadata(id_key,owner_key,conversation_id,title,active,metadata_version,pinned,title_manually_edited,auto_title_resolved,preview_sequence,user_message_count,sort_micros,created_micros,statistics_ready) VALUES(?,?,?,?,1,0,0,1,1,0,0,?,?,1)", metadata);
    }

    void startScan() {
        worker = new ConversationSearchSyncWorker(f.source, f.store, env.writer, config);
        lifecycle = new ConversationSearchScanLifecycle(worker, config, env.io);
        lifecycle.start();
    }

    void awaitReady() throws Exception {
        long deadline = System.currentTimeMillis() + 600_000;
        while (true) {
            var status = f.store.status(env.g);
            monitor.add(Map.of("elapsedMs", System.currentTimeMillis() - started, "status", status));
            if ("READY".equals(status.state())) return;
            assertTrue("Backfill failed: " + status, status.failures() == 0);
            assertTrue("Backfill timeout: " + status, System.currentTimeMillis() < deadline);
            writeReport();
            Thread.sleep(1000);
        }
    }

    @Test
    public void labeledQualityFreshnessAndRollout() throws Exception {
        seed();
        // 投影刚开始时即可查询，未同步的内容不能被数据库兜底合成为搜索结果。
        var early = page("旅行 行程", null, context());
        assertTrue(early.records().isEmpty());
        close(early, context());
        startScan();
        awaitReady();
        env.es.indices().refresh(b -> b.index(env.g)); // 仅用于固定质量集准备；新鲜度阶段绝不手动 refresh。
        report.put("initialBackfillMs", System.currentTimeMillis() - started);
        report.put("initialIndexedDocuments", env.es.count(b -> b.index(env.g)).count());
        var results = new ArrayList<Map<String, Object>>();
        int ranked = 0, top5 = 0, located = 0;
        var issues = new ArrayList<String>();
        for (var label : corpus.path("queries")) {
            String q = label.path("query").asString(), id = label.path("id").asString();
            if (label.path("invalid").asBoolean()) {
                assertThrows(IllegalArgumentException.class, () -> new ConversationSearch.Query(q, List.of(), 20, null));
                results.add(Map.of("id", id, "invalidRejected", true));
                continue;
            }
            var expected = ids(label.path("expected"));
            var wantedTop = ids(label.path("top"));
            var all = new ArrayList<ConversationSearch.Item>();
            var c = context();
            var p = page(q, null, c);
            int pages = 0;
            try {
                while (true) {
                    all.addAll(p.records());
                    if (p.nextCursor() == null) break;
                    assertTrue("Unbounded pages", ++pages < 20);
                    p = page(q, p.nextCursor(), c);
                }
            } finally {
                close(p, c);
            }
            var actual = all.stream().map(ConversationSearch.Item::entryKey).toList();
            if (!new HashSet<>(actual).equals(expected) || actual.size() != new HashSet<>(actual).size())
                issues.add(id + ": expected=" + expected + ", actual=" + actual);
            assertTrue(id + " foreign result", Collections.disjoint(actual, ids(label.path("forbidden"))));
            if (!wantedTop.isEmpty()) {
                ranked++;
                if (all.stream().limit(5).map(ConversationSearch.Item::entryKey).anyMatch(wantedTop::contains)) top5++;
            }
            for (var item : all) {
                if (!expected.contains(item.entryKey()) || !item.canLocateMessage()) continue;
                var around = service.queryTurnsAround(new ConversationSearch.AroundQuery(item.conversationId(), item.turnId(), 0L, 10, null), c).block();
                var t = around.records().stream().filter(v -> v.turnId().equals(item.turnId())).findFirst().orElseThrow();
                if (item.invocationId() == null) assertEquals(item.messageId(), t.userMessage().messageId());
                else {
                    assertTrue(t.invocationIds().contains(item.invocationId()));
                    var payload = events.result(item.invocationId(), c).block();
                    assertTrue(payload instanceof InvocationResult.Generation);
                    assertTrue(((InvocationResult.Generation) payload).value().outputs().stream().anyMatch(m -> m.messageId().equals(item.messageId())));
                }
                located++;
            }
            results.add(Map.of("id", id, "pages", pages + 1, "count", all.size(), "expected", expected, "actual", actual));
        }
        report.put("queries", results);
        report.put("mismatches", issues);
        report.put("rankedQueries", ranked);
        report.put("top5Queries", top5);
        report.put("locationChecks", located);
        writeReport();
        assertTrue("Fixed label mismatches: " + issues, issues.isEmpty());
        assertTrue("Top5 gate", top5 >= Math.ceil(ranked * .90));
        verifyHttpAndAccess();
        measureFreshness();
        awaitBrowser();
        verifyRollbackAndFailure();
        report.put("finishedAt", Instant.now().toString());
        writeReport();
    }

    Set<String> ids(JsonNode labels) {
        var result = new LinkedHashSet<String>();
        for (var label : labels) result.add(targets.get(label.asString()).entryKey());
        return result;
    }

    String send(String endpoint, Map<String, Object> values, int status) throws Exception {
        var body = new LinkedHashMap<>(values);
        body.put("scope", Map.of("tenantId", OWNER.tenantId(), "workspaceId", OWNER.workspaceId()));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "test", List.of()));
        try {
            var r = mvc.perform(post("/ai-new/" + endpoint).contentType("application/json").content(json.writeValueAsString(body))).andReturn();
            if (r.getRequest().isAsyncStarted()) r = mvc.perform(asyncDispatch(r)).andReturn();
            assertEquals(r.getResponse().getContentAsString(), status, r.getResponse().getStatus());
            return r.getResponse().getContentAsString();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    void verifyHttpAndAccess() throws Exception {
        var response = json.readTree(send("conversation/searchConversations", Map.of("query", "端口冲突", "size", 20), 200)).path("data");
        assertEquals(2, response.path("records").size());
        send("conversation/queryTurnsAround", Map.of("conversationId", "A03", "turnId", "turn-A03", "expectedVersion", 0, "size", 10), 200);
        assertTrue(send("invocation/getInvocationSnapshots", Map.of("invocationIds", List.of("A03", "A03-twin")), 200).contains("answer-A03-twin"));
        send("conversation/closeSearch", Map.of("searchSessionId", response.path("searchSessionId").asString()), 200);
        var bob = context("bob", OWNER.workspaceId());
        var b = page("旅行 行程", null, bob);
        assertEquals(List.of("foreign-user"), b.records().stream().map(ConversationSearch.Item::conversationId).toList());
        close(b, bob);
        var other = context("alice", "other-space");
        var o = page("旅行 行程", null, other);
        assertEquals(List.of("foreign-space"), o.records().stream().map(ConversationSearch.Item::conversationId).toList());
        close(o, other);
        var p = page("端口冲突", null, context());
        try {
            f.archived.add("A03");
            assertTrue(page("端口冲突", p.firstCursor(), context()).records().isEmpty());
            send("conversation/getConversation", Map.of("conversationId", "A03"), 404);
            send("invocation/getInvocationSnapshots", Map.of("invocationIds", List.of("A03")), 200);
            denied.set(true);
            send("conversation/searchConversations", Map.of("query", "端口冲突", "size", 20), 403);
        } finally {
            denied.set(false);
            f.archived.clear();
            close(p, context());
        }
        report.put("realDependencyHttpAndOwnerMatrix", "PASS; test authorization resolver, not production RBAC certification");
    }

    void measureFreshness() throws Exception {
        var sample = new LinkedHashMap<String, List<Long>>();
        for (String k : List.of("user", "assistant", "title")) sample.put(k, new ArrayList<>());
        var load = Executors.newFixedThreadPool(2);
        var running = new AtomicBoolean(true);
        var queries = new AtomicInteger();
        var errors = new AtomicInteger();
        var latencies = new CopyOnWriteArrayList<Long>();
        var futures = new ArrayList<Future<?>>();
        for (int n = 0; n < 2; n++) {
            futures.add(load.submit(() -> {
                while (running.get()) try {
                    long requestAt = System.nanoTime();
                    var c = context();
                    var p = page("日常记录", null, c);
                    try {
                        assertFalse(p.records().isEmpty());
                    } finally {
                        close(p, c);
                    }
                    latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestAt));
                    queries.incrementAndGet();
                    Thread.sleep(500);
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }));
        }
        long start = System.currentTimeMillis();
        try {
            // 一批十组持久化事件；记录各自提交后的时刻，避免把十次全库扫描串行成单一低负载样本。
            var pending = new LinkedHashMap<String, Long>();
            for (int n = 0; n < 10; n++) {
                String id = "fresh-" + n, token = "新鲜度探针 " + n;
                f.conversation(id, "新鲜度样本 " + n);
                f.turn(id, "turn-" + id, token + " question", List.of(id));
                pending.put(id + ":user", System.nanoTime());
                f.invocation(id, "turn-" + id, id, token + " durable answer", n % 2 == 0, true);
                pending.put(id + ":assistant", System.nanoTime());
                f.rename(id, token + " renamed");
                pending.put(id + ":title", System.nanoTime());
            }
            long deadline = System.currentTimeMillis() + 180000;
            while (!pending.isEmpty()) {
                var c = context();
                var p = page("新鲜度探针", null, c);
                try {
                    while (true) {
                        for (var item : p.records()) {
                            String kind = item.matchKind() == ConversationSearch.Kind.TITLE ? "title" : item.invocationId() == null ? "user" : "assistant";
                            Long at = pending.remove(item.conversationId() + ":" + kind);
                            if (at != null) sample.get(kind).add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - at));
                        }
                        if (p.nextCursor() == null) break;
                        p = page("新鲜度探针", p.nextCursor(), c);
                    }
                } finally {
                    close(p, c);
                }
                assertTrue("Freshness timeout " + pending.keySet(), System.currentTimeMillis() < deadline);
                if (!pending.isEmpty()) Thread.sleep(500);
            }
        } finally {
            running.set(false);
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            load.shutdownNow();
        }
        var stats = new LinkedHashMap<String, Object>();
        sample.forEach((kind, values) -> {
            var sorted = values.stream().sorted().toList();
            stats.put(kind, Map.of("samplesMs", values, "p95Ms", sorted.get((int) Math.ceil(sorted.size() * .95) - 1), "maxMs", sorted.getLast()));
        });
        report.put("freshness", stats);
        report.put("freshnessTargetMs", 30000);
        report.put("freshnessTargetMet", sample.values().stream().allMatch(values -> values.stream().mapToLong(Long::longValue).max().orElse(Long.MAX_VALUE) <= 30000));
        report.put("freshnessSamplePattern", "10 conversation groups, 30 committed events in one burst; 2 concurrent search clients");
        report.put("freshnessLoad", Map.of("concurrentBackgroundQueries", 2, "queries", queries.get(), "errors", errors.get(), "elapsedMs", System.currentTimeMillis() - start, "manualRefresh", false));
        report.put("queryLatency", Map.of("samples", latencies.size(), "p95Ms", latencies.stream().sorted().toList().get(Math.max(0, (int) Math.ceil(latencies.size() * .95) - 1)), "includesClose", true));
        assertEquals("Load query failures", 0, errors.get());
        writeReport();
    }

    void verifyRollbackAndFailure() throws Exception {
        lifecycle.stop();
        // 真实依赖故障：仅破坏本测试 alias／本测试 Redis 客户端，普通详情仍可读取，不触碰共享服务。
        env.es.indices().deleteAlias(b -> b.index(env.g).name(env.prefix + "-read"));
        try {
            assertEquals(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, assertThrows(SearchFailure.class, () -> page("旅行", null, context())).code());
            assertEquals("T01", conversations.find("T01", context()).block().conversationId());
        } finally {
            env.es.indices().putAlias(b -> b.index(env.g).name(env.prefix + "-read"));
        }
        env.redis.shutdown();
        assertEquals(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, assertThrows(SearchFailure.class, () -> page("旅行", null, context())).code());
        assertEquals("T01", conversations.find("T01", context()).block().conversationId());
        // 清理需要可用客户端；后续真实代际切换／校准回退由 S2 的真实依赖用例复验。
        var cfg = new org.redisson.config.Config();
        cfg.setThreads(2).setNettyThreads(2);
        var server = cfg.useSingleServer().setAddress(System.getenv("ARTE_SEARCH_IT_REDIS_URL"));
        String password = System.getenv("ARTE_SEARCH_IT_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) server.setPassword(password);
        env.redis = org.redisson.Redisson.create(cfg);
        report.put("dependencyIsolation", "PASS: missing read alias and closed dedicated Redis client; ordinary MySQL detail unaffected");
    }

    /**
     * 可选的本机浏览器桥；只公开固定测试身份的只读白名单，不能接入业务库。
     */
    void awaitBrowser() throws Exception {
        String port = System.getenv("ARTE_SEARCH_S5_BROWSER_PORT");
        if (port == null) return;
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 0);
        var executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        var allowed = Set.of("conversation/searchConversations", "conversation/closeSearch", "conversation/queryTurnsAround", "conversation/getConversation", "conversation/getConversationMetadata", "conversation/queryConversationWindow", "conversation/listConversationsCursor", "conversation/queryTurnsOfConversation", "invocation/getInvocationSnapshots");
        server.createContext("/arte/ai-new/", exchange -> {
            try {
                String endpoint = exchange.getRequestURI().getPath().substring("/arte/ai-new/".length());
                if (!exchange.getRequestMethod().equals("POST") || !allowed.contains(endpoint)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "test", List.of()));
                byte[] body = exchange.getRequestBody().readNBytes(65537);
                if (body.length > 65536) {
                    exchange.sendResponseHeaders(413, -1);
                    return;
                }
                var r = mvc.perform(post("/ai-new/" + endpoint).contentType("application/json").content(body)).andReturn();
                if (r.getRequest().isAsyncStarted()) r = mvc.perform(asyncDispatch(r)).andReturn();
                byte[] bytes = r.getResponse().getContentAsByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
                exchange.sendResponseHeaders(r.getResponse().getStatus(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception e) {
                exchange.sendResponseHeaders(500, -1);
            } finally {
                SecurityContextHolder.clearContext();
                exchange.close();
            }
        });
        Path ready = Path.of("target/search-s5-browser-ready"), stop = Path.of("target/search-s5-browser-stop");
        Files.deleteIfExists(stop);
        try {
            server.start();
            Files.writeString(ready, "http://127.0.0.1:" + port + "/arte/ai-new/\n");
            long deadline = System.currentTimeMillis() + 900_000;
            while (!Files.exists(stop)) {
                assertTrue("Browser acceptance timed out", System.currentTimeMillis() < deadline);
                Thread.sleep(250);
            }
            report.put("browserBridge", "Completed; fixed test identity + MVC controllers + real dependencies");
        } finally {
            server.stop(0);
            executor.shutdownNow();
            Files.deleteIfExists(ready);
            Files.deleteIfExists(stop);
        }
    }

    void writeReport() throws Exception {
        report.put("monitor", monitor);
        report.put("elapsedMs", System.currentTimeMillis() - started);
        Path path = Path.of("target/search-s5-report.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    @After
    public void cleanup() throws Exception {
        if (lifecycle != null) lifecycle.stop();
        if (pool != null) pool.close();
        env.clean();
    }
}
