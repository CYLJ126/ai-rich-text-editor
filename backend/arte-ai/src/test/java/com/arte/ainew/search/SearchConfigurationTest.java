package com.arte.ainew.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.arte.ainew.api.conversation.ConversationArchiveCommands;
import com.arte.ainew.application.conversation.UnsupportedConversationArchiveCommands;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.*;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import org.junit.Test;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import reactor.core.publisher.Mono;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class SearchConfigurationTest {
    private AnnotationConfigApplicationContext context(Map<String, Object> values) {
        var c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("search-test", values));
        c.register(NewAiSearchConfiguration.class);
        return c;
    }

    @Test
    public void fullyDisabledDoesNotCreatePortsOrInfrastructure() {
        try (var c = context(Map.of())) {
            c.refresh();
            assertTrue(c.getBeansOfType(ConversationArchiveReader.class).isEmpty());
            assertTrue(c.getBeansOfType(ConversationSearchIndex.class).isEmpty());
        }
    }

    @Test
    public void newAiEnabledSearchDisabledNeedsNoRemoteDependencies() {
        try (var c = context(Map.of("arte.ai-new.enabled", true))) {
            c.refresh();
            assertNotNull(c.getBean(ConversationArchiveReader.class));
            assertFalse(c.getBean(ConversationArchiveCommands.class).isSupported());
            assertTrue(c.getBeansOfType(ConversationSearchIndex.class).isEmpty());
            var p = c.getBean(NewAiSearchProperties.class);
            assertFalse(p.enabled());
            assertFalse(p.queryEnabled());
            assertFalse(p.scanEnabled());
            assertFalse(p.initializeOnStartup());
            assertEquals(Duration.ofMinutes(10), p.session().idleTimeout());
            assertEquals(Duration.ofMinutes(30), p.session().maxLifetime());
            assertEquals(3, p.session().maxPerSubject().intValue());
            assertFalse(p.diagnostics().sqlEnabled());
            assertEquals(Duration.ofMillis(500), p.diagnostics().slowSqlThreshold());
        }
    }

    @Test
    public void backgroundSqlDiagnosticsBindSeparatelyAndRejectInvalidThreshold() {
        try (var c = context(Map.of("arte.ai-new.enabled", true,
                "arte.ai-new-search.diagnostics.sql-enabled", true,
                "arte.ai-new-search.diagnostics.slow-sql-threshold", "2s"))) {
            c.refresh();
            var p = c.getBean(NewAiSearchProperties.class);
            assertTrue(p.diagnostics().sqlEnabled());
            assertEquals(Duration.ofSeconds(2), p.diagnostics().slowSqlThreshold());
        }
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties.Diagnostics(false, Duration.ZERO));
    }

    @Test
    public void shippedPropertiesEnableInitializationQueriesAndSynchronization() throws Exception {
        var properties = new java.util.Properties();
        try (var stream = getClass().getResourceAsStream("/ainew/config/conversation-search.properties")) {
            properties.load(stream);
        }
        try (var c = context(Map.of("arte.ai-new.enabled", true))) {
            c.getEnvironment().getPropertySources().addLast(new org.springframework.core.env.PropertiesPropertySource("shipped", properties));
            c.registerBean("index", ConversationSearchIndex.class, () -> proxy(ConversationSearchIndex.class));
            c.refresh();
            var actual = c.getBean(NewAiSearchProperties.class);
            assertTrue(actual.enabled());
            assertTrue(actual.initializeOnStartup());
            assertEquals("conversation-search-v2-g01", actual.sync().generation());
            assertTrue(actual.queryEnabled());
            assertTrue(actual.scanEnabled());
            assertTrue(actual.backfillEnabled());
            assertTrue(actual.reconcileEnabled());
            assertFalse(actual.diagnostics().sqlEnabled());
            assertEquals(Duration.ofMillis(500), actual.diagnostics().slowSqlThreshold());
        }
    }

    @Test
    public void realArchiveAdaptersReplaceDefaultsWithoutFallback() {
        var owner = new ExecutionOwner("tenant", "space", "subject");
        ConversationArchiveReader reader = (o, ids) -> Mono.just(Set.of("conv"));
        ConversationArchiveCommands commands = new UnsupportedConversationArchiveCommands();
        try (var c = context(Map.of("arte.ai-new.enabled", true))) {
            c.registerBean("realArchiveReader", ConversationArchiveReader.class, () -> reader);
            c.registerBean("realArchiveCommands", ConversationArchiveCommands.class, () -> commands);
            c.refresh();
            assertEquals(1, c.getBeansOfType(ConversationArchiveReader.class).size());
            assertSame(reader, c.getBean(ConversationArchiveReader.class));
            assertEquals(Set.of("conv"), c.getBean(ConversationArchiveReader.class).findArchivedConversationIds(owner, Set.of("conv")).block());
            assertSame(commands, c.getBean(ConversationArchiveCommands.class));
        }
    }

    @Test
    public void enabledSearchAssemblesWithoutProbingEsOrRedis() {
        try (var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.enabled", true))) {
            var transport = (ElasticsearchTransport) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{ElasticsearchTransport.class}, (p, m, args) -> {
                        throw new AssertionError("No ES call at startup: " + m.getName());
                    });
            var redis = (RedissonClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{RedissonClient.class},
                    (p, m, args) -> {
                        throw new AssertionError("No Redis call at startup: " + m.getName());
                    });
            c.registerBean(ElasticsearchClient.class, () -> new ElasticsearchClient(transport));
            c.registerBean(RedissonClient.class, () -> redis);
            c.refresh();
            assertNotNull(c.getBean(ConversationSearchIndex.class));
            assertTrue(c.containsBean("newAiSearchScheduler"));
        }
    }

    @Test
    public void scanAssemblyRequiresFixedGenerationAndDoesNotStartBeforeApplicationReady() {
        try (var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.enabled", true,
                "arte.ai-new-search.scan-enabled", true, "arte.ai-new-search.sync.generation", "conversation-search-v2-g01"))) {
            c.register(NewAiSearchSyncConfiguration.class);
            c.registerBean("rawRead", ConversationSearchIndex.class, () -> proxy(ConversationSearchIndex.class));
            c.registerBean("source", com.arte.ainew.spi.search.ConversationSearchSource.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchSource.class));
            c.registerBean("progress", com.arte.ainew.spi.search.ConversationSearchSyncStore.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchSyncStore.class));
            c.registerBean("writer", com.arte.ainew.spi.search.ConversationSearchWriter.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchWriter.class));
            c.registerBean("indexLock", com.arte.ainew.spi.search.ConversationSearchIndexLock.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchIndexLock.class));
            c.refresh();
            assertNotNull(c.getBean(ConversationSearchScanLifecycle.class));
            assertNotNull(c.getBean(com.arte.ainew.application.search.ConversationSearchMaintenance.class));
        }
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties(true, false, true, false, false, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties(true, false, true, false, false, null, null, null,
                new NewAiSearchProperties.Sync("wrong-prefix-g01", null, null, null, null, null, null, null, null)));
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{type}, (p, m, args) -> {
            if (m.getName().equals("toString")) return "TestPort";
            throw new AssertionError("No backend operation before ApplicationReady: " + m.getName());
        });
    }

    @Test
    public void querySwitchSeparatelyAssemblesWithoutRemoteCallsAndDefaultsRemainClosed() {
        try (var c = context(Map.of("arte.ai-new.enabled", true))) {
            c.register(NewAiSearchQueryConfiguration.class);
            c.refresh();
            assertTrue(c.getBeansOfType(com.arte.ainew.api.conversation.ConversationSearchService.class).isEmpty());
        }
        try (var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.enabled", true, "arte.ai-new-search.query-enabled", true))) {
            c.register(NewAiSearchQueryConfiguration.class);
            c.registerBean("index", ConversationSearchIndex.class, () -> proxy(ConversationSearchIndex.class));
            c.registerBean("sessions", com.arte.ainew.spi.search.ConversationSearchSessions.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchSessions.class));
            c.registerBean("reads", com.arte.ainew.spi.search.ConversationSearchReads.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchReads.class));
            c.registerBean("newAiClock", java.time.Clock.class, java.time.Clock::systemUTC);
            c.registerBean(com.arte.ainew.spi.auth.ExecutionAuthorizationResolver.class, () -> (name, t, w, s) -> Mono.error(new AssertionError("No startup authorization")));
            c.refresh();
            assertNotNull(c.getBean(com.arte.ainew.api.conversation.ConversationSearchService.class));
            assertNotNull(c.getBean(com.arte.ainew.spi.conversation.ConversationContentVisibility.class));
        }
    }

    @Test
    public void invalidLimitsAndVersionMismatchAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties(true, false, false, false, false, null, null, null, null, true));
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties(false, false, false, false, false, null, null, null,
                new NewAiSearchProperties.Sync("conversation-search-v2-g01", null, null, null, null, null, null, null, null), true));
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties.Session(null, Duration.ofMinutes(31), Duration.ofMinutes(30), 3, null));
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties.Index(null, null, null, null, null, null, "9.4.2", "9.3.2", null, null));
        assertThrows(IllegalArgumentException.class, () -> new NewAiSearchProperties(false, true, false, false, false, null, null, null));
        try (var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.session.max-per-subject", 0))) {
            assertThrows(RuntimeException.class, c::refresh);
        }
    }

    @Test
    public void initializationBindsSeparatelyFromScanningAndIsGatedByBothMasters() {
        try (var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.enabled", true,
                "arte.ai-new-search.initialize-on-startup", true, "arte.ai-new-search.sync.generation", "conversation-search-v2-g01"))) {
            c.registerBean("index", ConversationSearchIndex.class, () -> proxy(ConversationSearchIndex.class));
            c.refresh();
            assertTrue(c.getBean(NewAiSearchProperties.class).initializeOnStartup());
            assertFalse(c.getBean(NewAiSearchProperties.class).scanEnabled());
        }
        try (var c = context(Map.of("arte.ai-new.enabled", true))) {
            c.register(NewAiSearchSyncConfiguration.class);
            c.refresh();
            assertTrue(c.getBeansOfType(com.arte.ainew.infrastructure.search.ConversationSearchBootstrap.class).isEmpty());
            assertTrue(c.getBeansOfType(ConversationSearchScanLifecycle.class).isEmpty());
        }
    }

    @Test
    public void initializationOnlyAssemblyDefersAllRemoteOperationsUntilApplicationReady() throws Exception {
        var rest = co.elastic.clients.transport.rest5_client.low_level.Rest5Client.builder(org.apache.hc.core5.http.HttpHost.create("http://127.0.0.1:1")).build();
        var transport = new co.elastic.clients.transport.rest5_client.Rest5ClientTransport(rest,
                new co.elastic.clients.json.jackson.Jackson3JsonpMapper(tools.jackson.databind.json.JsonMapper.builder().build()));
        try (transport; var c = context(Map.of("arte.ai-new.enabled", true, "arte.ai-new-search.enabled", true,
                "arte.ai-new-search.initialize-on-startup", true, "arte.ai-new-search.sync.generation", "conversation-search-v2-g01",
                "arte.ai-new.release-ref", "test-release", "arte.ai-new.configuration-mode", "DATABASE"))) {
            c.register(NewAiSearchSyncConfiguration.class);
            c.register(PropertiesBinding.class);
            c.registerBean("appDataSource", javax.sql.DataSource.class, () -> proxy(javax.sql.DataSource.class));
            c.registerBean(ElasticsearchClient.class, () -> new ElasticsearchClient(transport));
            c.registerBean(RedissonClient.class, () -> proxy(RedissonClient.class));
            c.registerBean("source", com.arte.ainew.spi.search.ConversationSearchSource.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchSource.class));
            c.registerBean("progress", com.arte.ainew.spi.search.ConversationSearchSyncStore.class, () -> proxy(com.arte.ainew.spi.search.ConversationSearchSyncStore.class));
            c.refresh();
            var bootstrap = c.getBean(com.arte.ainew.infrastructure.search.ConversationSearchBootstrap.class);
            assertFalse(bootstrap.isReady());
            assertNotNull(c.getBean(ConversationSearchScanLifecycle.class));
            assertEquals(com.arte.ainew.common.search.SearchFailure.Code.DEPENDENCY_UNAVAILABLE,
                    assertThrows(com.arte.ainew.common.search.SearchFailure.class,
                            () -> c.getBean(ConversationSearchIndex.class).open(Duration.ofSeconds(10)).block()).code());
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(NewAiProperties.class)
    static class PropertiesBinding {
    }
}
