package com.arte.app.platform.persistence;

import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "platform.test.redis", matches = ".+")
class RedisPlatformPersistenceIntegrationTest {
    PlatformPersistenceIntegrationTest fixture;
    RedissonClient first, second;
    Scheduler scheduler;
    PlatformPersistenceProperties properties;

    @BeforeEach
    void setup() throws Exception {
        fixture = new PlatformPersistenceIntegrationTest();
        fixture.setup();
        fixture.initialize();
        first = client();
        second = client();
        scheduler = Schedulers.newBoundedElastic(2, 8, "redis-test-persistence");
        properties =
                new PlatformPersistenceProperties(
                        true,
                        2,
                        8,
                        5,
                        15,
                        10,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ofMinutes(1),
                        "arte:test:" + UUID.randomUUID() + ":",
                        false);
    }

    RedissonClient client() {
        Config config = new Config();
        config.setThreads(2).setNettyThreads(2);
        config.useSingleServer()
                .setAddress(System.getProperty("platform.test.redis"))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                .setTimeout(1000)
                .setConnectTimeout(1000)
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    RedisOutboxPublisher publisher(RedissonClient redis) {
        return new RedisOutboxPublisher(
                redis,
                fixture.outbox,
                new PlatformPersistenceExecutor(scheduler, fixture.tx),
                fixture.json,
                properties);
    }

    @AfterEach
    void cleanup() {
        if (first != null && !first.isShutdown()) {
            first.getKeys().deleteByPattern(properties.redisPrefix() + "*");
            first.shutdown();
        }
        if (second != null && !second.isShutdown()) second.shutdown();
        if (scheduler != null) scheduler.dispose();
        fixture.cleanup();
    }

    @Test
    void independentClientsPublishOnceAndKeepDatabaseLedger() {
        try (var a = publisher(first);
             var b = publisher(second)) {
            var results = Mono.zip(a.drainOnce(), b.drainOnce()).block(Duration.ofSeconds(10));
            assertNotNull(results);
            assertEquals(1, results.getT1() + results.getT2());
            assertEquals(
                    1,
                    first.getStream(properties.redisPrefix() + "events", StringCodec.INSTANCE)
                            .size());
            assertEquals(1, fixture.count("outbox"));
            assertEquals(
                    1,
                    fixture.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM arte_platform_outbox WHERE state=2",
                            Integer.class));
            assertEquals(0, a.drainOnce().block(Duration.ofSeconds(5)));
        }
    }

    @Test
    void redisCoordinationCannotBypassDatabaseLease() {
        fixture.tx.required(() -> fixture.outbox.claim(10, Duration.ofSeconds(30)));
        first.getLock(properties.redisPrefix() + "outbox:publisher").forceUnlock();
        try (var pub = publisher(second)) {
            assertEquals(0, pub.drainOnce().block(Duration.ofSeconds(5)));
        }
        assertEquals(
                0,
                first.getStream(properties.redisPrefix() + "events", StringCodec.INSTANCE).size());
        fixture.jdbc.update(
                "UPDATE arte_platform_outbox SET available_at=TIMESTAMP '2000-01-01 00:00:00'");
        try (var pub = publisher(second)) {
            assertEquals(1, pub.drainOnce().block(Duration.ofSeconds(5)));
        }
    }

    @Test
    void redisFailureLeavesEventsPendingForRecovery() {
        first.shutdown();
        try (var pub = publisher(first)) {
            assertThrows(
                    RuntimeException.class, () -> pub.drainOnce().block(Duration.ofSeconds(5)));
        }
        assertEquals(
                1,
                fixture.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM arte_platform_outbox WHERE state=0", Integer.class));
        try (var pub = publisher(second)) {
            assertEquals(1, pub.drainOnce().block(Duration.ofSeconds(5)));
        }
    }

    @Test
    void cursorIsSharedAcrossInstancesAndBindsStableSubject() {
        var a =
                new RedisWorkspaceCursorStore(
                        first,
                        fixture.json,
                        properties.cursorTtl(),
                        properties.redisPrefix() + "cursor:");
        var b =
                new RedisWorkspaceCursorStore(
                        second,
                        fixture.json,
                        properties.cursorTtl(),
                        properties.redisPrefix() + "cursor:");
        Scope scope =
                fixture.preferences
                        .find(fixture.actor.principal().subjectId())
                        .orElseThrow()
                        .defaultScope();
        var cursor =
                new WorkspaceCursorStore.Cursor(
                        fixture.actor.principal().subjectId(), 2, 20, "",
                        WorkspaceCandidateKey.from(fixture.workspaces.find(scope).orElseThrow()));
        String token = a.save(cursor);
        assertEquals(cursor, b.load(token));
        var browser = new com.arte.app.platform.workspace.WorkspaceDirectoryBrowser(fixture.services.workspaces(), b);
        assertThrows(IllegalArgumentException.class, () -> browser.browse(fixture.actor, token,
                new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(10, "")));
        var stranger = new com.arte.core.pojo.platform.TrustedActor(new com.arte.core.pojo.platform.Principal("stranger",
                com.arte.core.enums.platform.PrincipalKindEnum.USER, com.arte.core.enums.platform.PrincipalStatusEnum.ACTIVE),
                java.time.Instant.now());
        assertThrows(IllegalArgumentException.class, () -> browser.browse(stranger, token,
                new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(20, "")));
        assertTrue(browser.browse(fixture.actor, token, new com.arte.core.pojo.workspace.WorkspaceBrowseQuery(20, "")).exhausted());
        first.getBucket(properties.redisPrefix() + "cursor:" + token).delete();
        assertEquals(com.arte.core.enums.platform.PlatformErrorCodeEnum.CURSOR_EXPIRED,
                assertThrows(com.arte.core.exception.platform.PlatformException.class, () -> b.load(token)).getCode());
    }
}
