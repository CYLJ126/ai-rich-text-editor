package com.arte.ainew.tool.discovery;

import com.arte.ainew.application.tool.discovery.LocalToolDiscovery;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.config.NewAiToolConfiguration;
import com.arte.ainew.config.NewAiToolProperties;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.adapter.LocalToolDefinitionSource;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.junit.Test;
import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.adapter;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.fixture;
import static org.junit.Assert.*;

/**
 * TOOL 独立开关、严格属性、显式登记、启动零副作用及资源释放。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 09:30
 */
public class LocalToolDiscoveryAssemblyTest {

    @Test
    public void bothSwitchesAreRequiredAndDisabledConfigurationNeedsNoDependencies() {
        for (var properties : List.of(Map.<String, Object>of(), Map.<String, Object>of("arte.ai-new.enabled", true),
                Map.<String, Object>of("arte.ai-new-tool.enabled", true))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                configure(context, properties);
                context.refresh();
                assertTrue(context.getBeansOfType(LocalToolDiscovery.class).isEmpty());
                assertTrue(context.getBeansOfType(com.arte.ainew.application.tool.release.ToolReleaseService.class).isEmpty());
                assertFalse(context.containsBean("newAiToolDiscoveryScheduler"));
            }
        }
    }

    @Test
    public void enabledAssemblyDoesNotReadSourcesWriteSqlOrAutoRegisterUnlistedBeans() {
        var reads = new AtomicInteger();
        var fixture = fixture("text", "v1");
        var store = new LocalToolDiscoveryTest.MemoryStore();
        reactor.core.scheduler.Scheduler scheduler;
        try (var context = new AnnotationConfigApplicationContext()) {
            dependencies(context, store);
            context.registerBean(LocalToolRegistry.class, () -> new LocalToolRegistry(List.of(fixture.registration(() -> {
                reads.incrementAndGet();
                return Mono.just(fixture.definitions());
            }))));
            context.registerBean("unlistedSource", LocalToolDefinitionSource.class, () -> () -> {
                throw new AssertionError("No arbitrary source scanning");
            });
            context.registerBean("unlistedAdapter", BusinessToolAdapter.class, () -> adapter(fixture("other", "v1").revision().tool().discoveredCapability()));
            configure(context, enabled());
            context.refresh();
            assertEquals(0, reads.get());
            assertEquals(0, store.calls.get());
            assertNotNull(context.getBean(LocalToolDiscovery.class));
            assertNotNull(context.getBean(com.arte.ainew.application.tool.release.ToolReleaseService.class));
            assertNotNull(context.getBean(com.arte.ainew.application.tool.release.LocalToolPublicationValidator.class));
            assertEquals(Duration.ofSeconds(10), context.getBean(NewAiToolProperties.class).discovery().sourceTimeout());
            scheduler = context.getBean("newAiToolDiscoveryScheduler", reactor.core.scheduler.Scheduler.class);
            assertFalse(scheduler.isDisposed());
            assertEquals(LocalToolDiscovery.Code.APPLIED, context.getBean(LocalToolDiscovery.class).refresh().block(Duration.ofSeconds(10)).code());
            assertEquals(1, reads.get());
            assertEquals(1, store.calls.get());
        }
        assertTrue(scheduler.isDisposed());
    }

    @Test
    public void enabledWithoutExplicitRegistryFailsRatherThanScanningSourceBeans() {
        try (var context = new AnnotationConfigApplicationContext()) {
            dependencies(context, new LocalToolDiscoveryTest.MemoryStore());
            context.registerBean(LocalToolDefinitionSource.class, () -> () -> Mono.just(fixture("text", "v1").definitions()));
            configure(context, enabled());
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Test
    public void strictBindingRejectsUnknownSettingsAndInvalidTimeout() {
        for (var extra : List.of(Map.<String, Object>of("arte.ai-new-tool.discovery.source-timout", "10s"),
                Map.<String, Object>of("arte.ai-new-tool.discovery.source-timeout", "0ms"),
                Map.<String, Object>of("arte.ai-new-tool.discovery.source-timeout", "61s"),
                Map.<String, Object>of("arte.ai-new-tool.discovery.auto-sync-enabled", true))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                dependencies(context, new LocalToolDiscoveryTest.MemoryStore());
                context.registerBean(LocalToolRegistry.class, () -> new LocalToolRegistry(List.of(fixture("text", "v1").registration())));
                var values = new java.util.LinkedHashMap<>(enabled());
                values.putAll(extra);
                configure(context, values);
                assertThrows(BeansException.class, context::refresh);
            }
        }
    }

    @Test
    public void explicitSourceTimeoutBindsWithUnits() {
        try (var context = new AnnotationConfigApplicationContext()) {
            dependencies(context, new LocalToolDiscoveryTest.MemoryStore());
            context.registerBean(LocalToolRegistry.class, () -> new LocalToolRegistry(List.of(fixture("text", "v1").registration())));
            var values = new java.util.LinkedHashMap<>(enabled());
            values.put("arte.ai-new-tool.discovery.source-timeout", "250ms");
            configure(context, values);
            context.refresh();
            assertEquals(Duration.ofMillis(250), context.getBean(NewAiToolProperties.class).discovery().sourceTimeout());
        }
    }

    @Test
    public void toolProfileDefaultsExampleAndFilteredResourceStayConsistent() throws Exception {
        var defaults = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../profile/common.properties"), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        }
        var example = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../profile/app.properties.example"), StandardCharsets.UTF_8)) {
            example.load(reader);
        }
        var template = Files.readString(Path.of("src/main/resources/ainew/config/application.properties"));
        for (String key : List.of("arte.ai-new-tool.enabled", "arte.ai-new-tool.discovery.source-timeout")) {
            assertTrue(template.contains(key + "=@" + key + "@"));
            assertEquals(defaults.getProperty(key), example.getProperty(key));
        }
        assertEquals("false", defaults.getProperty("arte.ai-new-tool.enabled"));
        assertEquals("10s", defaults.getProperty("arte.ai-new-tool.discovery.source-timeout"));
        var resource = new ClassPathResource("ainew/config/application.properties").getContentAsString(StandardCharsets.UTF_8);
        assertFalse(resource.contains("@arte.ai-new-tool"));
        var runtime = new Properties();
        runtime.load(new java.io.StringReader(resource));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("filtered-tool", runtime));
        var properties = Binder.get(environment).bind("arte.ai-new-tool", Bindable.of(NewAiToolProperties.class)).orElseThrow(AssertionError::new);
        assertFalse(properties.enabled());
        assertEquals(Duration.ofSeconds(10), properties.discovery().sourceTimeout());
    }

    private static Map<String, Object> enabled() {
        return Map.of("arte.ai-new.enabled", true, "arte.ai-new-tool.enabled", true);
    }

    private static void dependencies(AnnotationConfigApplicationContext context, ToolCatalogStore store) {
        context.registerBean(ToolCatalogStore.class, () -> store);
        context.registerBean(com.arte.ainew.spi.persistence.ToolBindingStore.class, () ->
                (com.arte.ainew.spi.persistence.ToolBindingStore) java.lang.reflect.Proxy.newProxyInstance(
                        com.arte.ainew.spi.persistence.ToolBindingStore.class.getClassLoader(),
                        new Class<?>[]{com.arte.ainew.spi.persistence.ToolBindingStore.class}, (proxy, method, args) -> {
                            throw new AssertionError("Assembly must not access bindings");
                        }));
        context.registerBean(com.arte.ainew.spi.persistence.ToolReleaseStore.class, () -> new com.arte.ainew.spi.persistence.ToolReleaseStore() {
            public Mono<com.arte.ainew.pojo.tool.ToolRelease> find(com.arte.ainew.common.reference.DefinitionRef tool) {
                throw new AssertionError("Assembly must not read releases");
            }

            public Mono<ToolCatalogStore.Page<com.arte.ainew.pojo.tool.ToolReleaseReceipt>> history(
                    com.arte.ainew.common.reference.DefinitionRef tool, String cursor, int limit) {
                throw new AssertionError("Assembly must not read release history");
            }

            public Mono<com.arte.ainew.pojo.execution.StoreOutcome<com.arte.ainew.pojo.tool.ToolReleaseReceipt>> execute(
                    com.arte.core.pojo.platform.TrustedActor actor, com.arte.ainew.pojo.tool.ToolReleaseCommand command,
                    com.arte.ainew.pojo.tool.ToolPublicationValidation validation) {
                throw new AssertionError("Assembly must not publish tools");
            }
        });
        context.registerBean("newAiClock", Clock.class, Clock::systemUTC);
    }

    private static void configure(AnnotationConfigApplicationContext context, Map<String, Object> values) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tool-test", values));
        context.register(NewAiToolConfiguration.class);
    }
}
