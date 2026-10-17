package com.arte.ainew.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchPreview;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.infrastructure.search.ElasticsearchConversationSearchIndex;
import com.arte.ainew.infrastructure.search.SearchIndexSchema;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.Assume;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.config.Config;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

/**
 * 显式环境变量启用；只创建随机命名的临时索引／Redis 键，finally 清理。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public class SearchInfrastructureIntegrationTest {

    private static final ExecutionOwner OWNER = new ExecutionOwner("s1-tenant", "s1-space", "s1-subject");
    private static final Instant AT = Instant.parse("2026-10-15T12:00:00.123456Z");

    @Test
    public void realIkPitForwardBackwardAndTitleOnlyWithMoreThanTenThousandHits() throws Exception {
        String url = System.getenv("ARTE_SEARCH_IT_ES_URL");
        Assume.assumeTrue(url != null);
        String prefix = "arte-search-s1-" + UUID.randomUUID().toString().replace("-", "");
        String index = prefix + "-g01", alias = prefix + "-read";
        var cfg = new NewAiSearchProperties(false, false, false, false, false,
                new NewAiSearchProperties.Index(alias, prefix + "-write", prefix, null, null, null, null, null, 2, 0), null, null);
        var builder = Rest5Client.builder(HttpHost.create(url));
        String user = System.getenv("ARTE_SEARCH_IT_ES_USERNAME"), password = System.getenv("ARTE_SEARCH_IT_ES_PASSWORD");
        if (user != null && password != null)
            builder.setDefaultHeaders(new BasicHeader[]{new BasicHeader("Authorization", "Basic " +
                    Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)))});
        var transport = new Rest5ClientTransport(builder.build(), new Jackson3JsonpMapper(JsonMapper.builder().build()));
        var client = new ElasticsearchClient(transport);
        var adapter = new ElasticsearchConversationSearchIndex(client, cfg, Schedulers.boundedElastic());
        ConversationSearchIndex.Pit pit = null, fresh = null;
        boolean created = false;
        try {
            assertEquals(cfg.index().expectedServerVersion(), client.info().version().number());
            var json = JsonMapper.builder().build();
            var schema = new LinkedHashMap<>(SearchIndexSchema.create(cfg.index()));
            schema.put("aliases", Map.of(alias, Map.of(), prefix + "-write", Map.of("is_write_index", true)));
            client.indices().create(b -> b.index(index).withJson(new StringReader(json.writeValueAsString(schema))));
            created = true;
            var smart = client.indices().analyze(b -> b.index(index).analyzer("arte_search_query").text("会话检索 Redis get_user_by_id"));
            assertFalse(smart.tokens().isEmpty());
            assertTrue(client.indices().analyze(b -> b.analyzer("ik_max_word").text("中华人民共和国国歌")).tokens().size() > 0);
            int count = 10025;
            for (int offset = 0; offset < count; offset += 500) {
                var bulk = new co.elastic.clients.elasticsearch.core.BulkRequest.Builder();
                for (int n = offset; n < Math.min(count, offset + 500); n++) {
                    var doc = SearchIndexRecord.message(OWNER, "conv", "turn-" + n, "message-" + n, null, 0, n + 1,
                            "会话检索 pagination get_user_by_id", n % 97 == 0 ? null : AT, false, 1);
                    bulk.operations(op -> op.index(i -> i.index(index).id(doc.entryKey()).document(doc.source())));
                }
                assertFalse(client.bulk(bulk.build()).errors());
            }
            var title = SearchIndexRecord.title(OWNER, "empty", "仅有标题 titleonlymarker", AT, 1);
            client.index(b -> b.index(index).id(title.entryKey()).document(title.source()));
            var stopOnly = SearchIndexRecord.title(OWNER, "stop-only", "and", AT, 1);
            client.index(b -> b.index(index).id(stopOnly.entryKey()).document(stopOnly.source()));
            var other = SearchIndexRecord.message(new ExecutionOwner("s1-tenant", "s1-space", "other"), "conv", "turn", "message", null, 0, 1,
                    "会话检索 pagination get_user_by_id", AT, false, 1);
            client.index(b -> b.index(index).id(other.entryKey()).document(other.source()));
            for (String text : List.of("alphaonly", "betaonly")) {
                var separated = SearchIndexRecord.message(OWNER, "separated", "turn-" + text, "message-" + text, null, 0, 1, text, AT, false, 1);
                client.index(b -> b.index(index).id(separated.entryKey()).document(separated.source()));
            }
            for (int n = 1; n <= 2; n++) {
                var candidate = SearchIndexRecord.message(OWNER, "history", "same-turn", "answer-" + n, "candidate-" + n, n, 1,
                        "候选检索 historicalmarker", AT, n == 1, 1);
                client.index(b -> b.index(index).id(candidate.entryKey()).document(candidate.source()));
            }
            client.indices().refresh(b -> b.index(index));
            pit = adapter.open(Duration.ofMinutes(2)).block();
            var seen = new ArrayList<String>();
            List<Object> after = List.of();
            List<Object> last = null;
            boolean inserted = false;
            while (true) {
                var batch = adapter.read(new ConversationSearchIndex.Read(OWNER, "会话检索", List.of(), pit,
                        ConversationSearch.Direction.FORWARD, after, 100, Duration.ofMinutes(2))).block();
                pit = batch.pit();
                if (batch.hits().isEmpty()) break;
                for (var hit : batch.hits()) {
                    assertFalse(hit.source().containsKey("body"));
                    assertEquals(OWNER.subjectId(), hit.source().get("subjectId"));
                    seen.add(hit.entryKey());
                }
                if (!inserted) {
                    var late = SearchIndexRecord.message(OWNER, "conv", "late-turn", "late-message", null, 0, 10026,
                            "会话检索 latemarker", AT, false, 1);
                    client.index(b -> b.index(index).id(late.entryKey()).document(late.source()));
                    client.indices().refresh(b -> b.index(index));
                    inserted = true;
                    var retry = adapter.read(new ConversationSearchIndex.Read(OWNER, "会话检索", List.of(), pit,
                            ConversationSearch.Direction.FORWARD, List.of(), 100, Duration.ofMinutes(2))).block();
                    pit = retry.pit();
                    assertEquals(batch.hits().stream().map(ConversationSearchIndex.Hit::entryKey).toList(),
                            retry.hits().stream().map(ConversationSearchIndex.Hit::entryKey).toList());
                }
                last = batch.hits().getLast().sort();
                after = last;
            }
            assertEquals(count, seen.size());
            assertEquals(count, new HashSet<>(seen).size());
            var backward = new ArrayList<String>();
            after = last;
            while (true) {
                var batch = adapter.read(new ConversationSearchIndex.Read(OWNER, "会话检索", List.of(), pit,
                        ConversationSearch.Direction.BACKWARD, after, 100, Duration.ofMinutes(2))).block();
                pit = batch.pit();
                if (batch.hits().isEmpty()) break;
                backward.addAll(batch.hits().stream().map(ConversationSearchIndex.Hit::entryKey).toList());
                after = batch.hits().getLast().sort();
            }
            var expected = new ArrayList<>(seen.subList(0, count - 1));
            Collections.reverse(expected);
            assertEquals(expected, backward);
            var titleBatch = adapter.read(new ConversationSearchIndex.Read(OWNER, "titleonlymarker", List.of(), pit,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            pit = titleBatch.pit();
            assertEquals(1, titleBatch.hits().size());
            assertEquals("TITLE", titleBatch.hits().getFirst().source().get("recordKind"));
            assertNull(titleBatch.hits().getFirst().source().get("turnId"));
            var snippet = SearchPreview.fromEncodedHighlight(titleBatch.hits().getFirst().highlights().get("title").getFirst(), 800);
            assertTrue(snippet.text().contains("titleonlymarker"));
            assertFalse(snippet.highlights().isEmpty());
            for (String query : List.of("会话检索 PAGINATION", "get_user_by_id")) {
                var mixed = adapter.read(new ConversationSearchIndex.Read(OWNER, query, List.of(), pit,
                        ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
                pit = mixed.pit();
                assertEquals(20, mixed.hits().size());
                assertFalse(SearchPreview.fromEncodedHighlight(mixed.hits().getFirst().highlights().get("body").getFirst(), 800).highlights().isEmpty());
            }
            var separated = adapter.read(new ConversationSearchIndex.Read(OWNER, "alphaonly betaonly", List.of(), pit,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            pit = separated.pit();
            assertTrue(separated.hits().isEmpty());
            assertTrue(client.indices().analyze(b -> b.index(index).analyzer("arte_search_query").text("and")).tokens().isEmpty());
            var stopped = adapter.read(new ConversationSearchIndex.Read(OWNER, "and", List.of(), pit,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            pit = stopped.pit();
            assertTrue(stopped.hits().isEmpty());
            var historical = adapter.read(new ConversationSearchIndex.Read(OWNER, "historicalmarker", List.of(), pit,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            pit = historical.pit();
            assertEquals(2, historical.hits().size());
            assertEquals(2, historical.hits().stream().map(h -> h.source().get("invocationId")).distinct().count());
            assertTrue(historical.hits().stream().anyMatch(h -> Boolean.TRUE.equals(h.source().get("partial"))));
            var noGroup = adapter.read(new ConversationSearchIndex.Read(OWNER, "会话检索", List.of("future-group"), pit,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            pit = noGroup.pit();
            assertTrue(noGroup.hits().isEmpty());
            fresh = adapter.open(Duration.ofMinutes(2)).block();
            var lateBatch = adapter.read(new ConversationSearchIndex.Read(OWNER, "latemarker", List.of(), fresh,
                    ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block();
            fresh = lateBatch.pit();
            assertEquals(1, lateBatch.hits().size());
            var expired = fresh;
            adapter.close(expired).block();
            fresh = null;
            var expiry = assertThrows(com.arte.ainew.common.search.SearchFailure.class, () -> adapter.read(
                    new ConversationSearchIndex.Read(OWNER, "latemarker", List.of(), expired,
                            ConversationSearch.Direction.FIRST, List.of(), 20, Duration.ofMinutes(2))).block());
            assertEquals(com.arte.ainew.common.search.SearchFailure.Code.SEARCH_EXPIRED, expiry.code());
            adapter.close(expired).block();
            System.out.println("S1 ES proof: ES=" + client.info().version().number() + ", IK smart/max_word available, 2 shards, " + count +
                    " forward hits, " + backward.size() + " reverse hits, stable retry/PIT snapshot/title-only/group filter passed");
        } finally {
            try {
                if (pit != null) adapter.close(pit).block();
                if (fresh != null) adapter.close(fresh).block();
            } finally {
                try {
                    if (created) client.indices().delete(b -> b.index(index));
                } finally {
                    transport.close();
                }
            }
        }
    }

    @Test
    public void realRedisUsesOnlyAnExpiringIsolatedTestKey() {
        String url = System.getenv("ARTE_SEARCH_IT_REDIS_URL");
        Assume.assumeTrue(url != null);
        var config = new Config();
        var single = config.useSingleServer().setAddress(url).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        String password = System.getenv("ARTE_SEARCH_IT_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) single.setPassword(password);
        var redis = Redisson.create(config);
        String key = "arte:ai-new:conversation-search:s1-proof:" + UUID.randomUUID();
        try {
            var bucket = redis.<String>getBucket(key);
            bucket.set("s1-ready", Duration.ofSeconds(30));
            assertEquals("s1-ready", bucket.get());
            assertTrue(bucket.remainTimeToLive() > 0);
            bucket.delete();
            assertFalse(bucket.isExists());
            System.out.println("S1 Redis proof: isolated key roundtrip/TTL/cleanup passed");
        } finally {
            redis.getBucket(key).delete();
            redis.shutdown();
        }
    }
}
