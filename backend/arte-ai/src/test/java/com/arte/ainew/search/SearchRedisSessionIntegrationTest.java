package com.arte.ainew.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.infrastructure.search.RedisConversationSearchSessions;
import com.arte.ainew.pojo.search.SearchSession;
import com.arte.ainew.spi.search.ConversationSearchIndex.Pit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static org.junit.Assert.*;

public class SearchRedisSessionIntegrationTest {
    RedissonClient redis;
    RedisConversationSearchSessions sessions;
    String namespace;
    NewAiSearchProperties properties;
    static final Duration WAIT = Duration.ofSeconds(2);

    @Before
    public void setup() {
        String url = System.getenv("ARTE_SEARCH_IT_REDIS_URL");
        Assume.assumeTrue(url != null);
        var config = new Config();
        config.setThreads(2).setNettyThreads(2);
        var server = config.useSingleServer().setAddress(url);
        String password = System.getenv("ARTE_SEARCH_IT_REDIS_PASSWORD");
        if (password != null && !password.isEmpty()) server.setPassword(password);
        redis = Redisson.create(config);
        namespace = "arte:search:s3:" + UUID.randomUUID().toString().replace("-", "");
        properties = new NewAiSearchProperties(true, true, false, false, false, null, new NewAiSearchProperties.Session(namespace, Duration.ofSeconds(2), Duration.ofSeconds(6), 3, WAIT), null);
        sessions = new RedisConversationSearchSessions(redis, properties);
    }

    @After
    public void cleanup() {
        if (redis != null) {
            try {
                redis.getKeys().deleteByPattern(namespace + "*");
                assertEquals(0, redis.getKeys().getKeysStreamByPattern(namespace + "*").count());
            } finally {
                redis.shutdown();
            }
        }
    }

    SearchSession reserve(ExecutionOwner owner) {
        return sessions.reserve(owner, UUID.randomUUID().toString(), "a".repeat(64), 2, "v1", WAIT);
    }

    SearchSession complete(ExecutionOwner owner, SearchSession s) {
        s = sessions.pit(owner, s, new Pit("proof-pit", "proof-generation"), s.hardExpiresAt(), WAIT);
        return sessions.complete(owner, s, WAIT);
    }

    @Test
    public void quotaIsAtomicAcrossWorkspacesAndDoesNotEvictExistingSessions() throws Exception {
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<SearchSession>>();
        try {
            for (int n = 0; n < 8; n++) {
                int slot = n;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return reserve(new ExecutionOwner(OWNER.tenantId(), "space-" + slot, OWNER.subjectId()));
                    } catch (SearchFailure error) {
                        assertEquals(SearchFailure.Code.SEARCH_LIMIT_REACHED, error.code());
                        return null;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : results) if (future.get(5, TimeUnit.SECONDS) != null) accepted++;
            assertEquals(3, accepted);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void mutexLatestPitFailureReleaseAndCloseCannotBeResurrected() {
        var s = complete(OWNER, reserve(OWNER));
        var lease = sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT);
        var busy = assertThrows(SearchFailure.class, () -> sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT));
        assertEquals(SearchFailure.Code.SEARCH_BUSY, busy.code());
        var updated = sessions.pit(OWNER, lease, new Pit("updated-pit", s.generation()), s.hardExpiresAt(), WAIT);
        long activity = updated.lastSuccessAt();
        sessions.release(OWNER, updated, WAIT);
        var next = sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT);
        assertEquals("updated-pit", next.pitId());
        assertEquals(activity, next.lastSuccessAt());
        assertNotNull(sessions.close(OWNER, s.id(), WAIT));
        assertNull(sessions.close(OWNER, s.id(), WAIT));
        assertEquals(SearchFailure.Code.SEARCH_EXPIRED, assertThrows(SearchFailure.class, () -> sessions.complete(OWNER, next, WAIT)).code());
        assertEquals(SearchFailure.Code.SEARCH_EXPIRED, assertThrows(SearchFailure.class, () -> sessions.reserve(OWNER, s.id(), s.queryHash(), 2, "v1", WAIT)).code());
    }

    @Test
    public void cancelledUnknownReservationDoesNotOccupyQuotaWhenItsCreateArrivesLate() {
        String cancelled = UUID.randomUUID().toString();
        sessions.cancelReservation(OWNER, cancelled, WAIT);
        assertEquals(SearchFailure.Code.SEARCH_EXPIRED, assertThrows(SearchFailure.class, () -> sessions.reserve(OWNER, cancelled, "a".repeat(64), 2, "v1", WAIT)).code());
        reserve(OWNER);
        reserve(OWNER);
        reserve(OWNER);
        assertEquals(SearchFailure.Code.SEARCH_LIMIT_REACHED, assertThrows(SearchFailure.class, () -> reserve(OWNER)).code());
    }

    @Test
    public void logicalIdleAndHardExpiryUseRedisTimeAndNeverOutliveConfirmedPit() throws Exception {
        var s = complete(OWNER, reserve(OWNER));
        assertTrue(s.idleExpiresAt() <= s.pitExpiresAt());
        Thread.sleep(1100);
        var renewed = sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT);
        renewed = sessions.pit(OWNER, renewed, new Pit("renewed", s.generation()), s.hardExpiresAt(), WAIT);
        renewed = sessions.complete(OWNER, renewed, WAIT);
        assertTrue(renewed.idleExpiresAt() > s.idleExpiresAt());
        assertTrue(renewed.idleExpiresAt() <= renewed.pitExpiresAt());
        Thread.sleep(2100);
        assertEquals(SearchFailure.Code.SEARCH_EXPIRED, assertThrows(SearchFailure.class, () -> sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT)).code());
        assertNotNull(reserve(OWNER));
    }

    @Test
    public void redisContainsControlMetadataAndStableHistoryKeyWithoutBodyOrBookmarks() {
        var s = complete(OWNER, reserve(OWNER));
        String one = sessions.historySigningKey(WAIT), two = sessions.historySigningKey(WAIT);
        assertEquals(one, two);
        var stateKey = redis.getKeys().getKeysStreamByPattern(namespace + ":*").filter(k -> k.endsWith(s.id())).findFirst().orElseThrow();
        String json = redis.<String>getBucket(stateKey, StringCodec.INSTANCE).get();
        assertFalse(json.contains("body"));
        assertFalse(json.contains("snippet"));
        assertFalse(json.contains("records"));
        assertFalse(json.contains("marker"));
        assertTrue(redis.getBucket(stateKey).remainTimeToLive() <= 2000);
        var other = new ExecutionOwner(OWNER.tenantId(), "other-space", OWNER.subjectId());
        assertNull(sessions.close(other, s.id(), WAIT));
        assertNotNull(sessions.acquire(OWNER, s.id(), s.queryHash(), 2, WAIT));
    }
}
