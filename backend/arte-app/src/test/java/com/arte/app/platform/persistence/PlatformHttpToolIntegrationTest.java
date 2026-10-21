package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.infrastructure.http.HttpHealthToolAdapter;
import com.arte.app.platform.ai.PlatformHttpToolConfiguration;
import com.arte.app.platform.ai.tool.HttpHealthToolProperties;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正式宿主 RBAC／空间／AI 资格与公开 MVC 工具链路；只使用临时测试库和回环服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
class PlatformHttpToolIntegrationTest {

    @Test
    void defaultDisabledDoesNotCreateHttpExecutorOrChangeLocalManifest() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            rig.context().withUserConfiguration(PlatformHttpToolConfiguration.class).run(c -> {
                assertNull(c.getStartupFailure());
                assertTrue(c.getBeansOfType(HttpHealthToolAdapter.class).isEmpty());
                assertFalse(c.containsBean("newAiToolHttpRuntime"));
                assertEquals(1, c.getBean(LocalToolRegistry.class).registrations().size());
                assertFalse(new HttpHealthToolProperties(false, null, null, List.of()).enabled());
            });
        }
    }

    @Test
    void httpProfileDefaultsExampleAndFilteredRuntimeAreClosed() throws Exception {
        var defaults = new java.util.Properties();
        var example = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("../profile/common.properties"))) {
            defaults.load(reader);
        }
        try (var reader = java.nio.file.Files.newBufferedReader(java.nio.file.Path.of("../profile/app.properties.example"))) {
            example.load(reader);
        }
        String resource = new org.springframework.core.io.ClassPathResource("ainew/config/application.properties")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(resource.contains("@arte.ai-new-tool-http"));
        var runtime = new java.util.Properties();
        runtime.load(new java.io.StringReader(resource));
        for (String field : List.of("enabled", "version", "endpoint", "allowed-origins")) {
            String key = "arte.ai-new-tool-http." + field;
            assertEquals(defaults.getProperty(key), example.getProperty(key));
            assertEquals(defaults.getProperty(key), runtime.getProperty(key));
        }
        var environment = new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.PropertiesPropertySource("runtime", runtime));
        var bound = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("arte.ai-new-tool-http", org.springframework.boot.context.properties.bind.Bindable.of(HttpHealthToolProperties.class)).get();
        assertFalse(bound.enabled());
        assertNull(bound.endpoint());
        assertTrue(bound.allowedOrigins().isEmpty());
    }

    @Test
    void enabledRequiresIndependentOriginAndImmutableConnectionVersion() {
        assertThrows(IllegalArgumentException.class, () -> new HttpHealthToolProperties(true, null, null, List.of()));
        assertThrows(RuntimeException.class, () -> new HttpHealthToolProperties(true, "v1", URI.create("https://service.example/health"), List.of(URI.create("https://other.example"))));
        assertThrows(IllegalArgumentException.class, () -> new HttpHealthToolProperties(true, "v1", URI.create("https://name:token@service.example/health"), List.of(URI.create("https://service.example"))));
        assertThrows(IllegalArgumentException.class, () -> new HttpHealthToolProperties(true, "v1", URI.create("http://10.0.0.1/health"), List.of(URI.create("http://10.0.0.1"))));
        assertEquals("v2", new HttpHealthToolProperties(true, "v2", URI.create("https://service.example/health"), List.of(URI.create("https://service.example"))).version());
    }

    @Test
    void publicHttpSubmissionUsesCurrentHostPermissionsAndFreeDispatch() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
            requests.incrementAndGet();
            return res.header("Content-Type", "application/json").sendString(Mono.just("{\"status\":\"UP\",\"details\":\"private\"}"));
        }).bindNow();
        try (var rig = new ToolAcceptanceRig()) {
            String origin = "http://127.0.0.1:" + server.port();
            var connection = new com.arte.ainew.pojo.control.ConnectionDefinition(new com.arte.ainew.common.reference.DefinitionRef("connection", "arte-http-health", "v1"),
                    "arte-http-health", com.arte.ainew.infrastructure.http.HttpJsonGetTransport.PROTOCOL, URI.create(origin + "/health"), null,
                    com.arte.ainew.pojo.control.ConnectionDefinition.State.ENABLED, Duration.ofSeconds(3), Duration.ofSeconds(5), 65536);
            String manifest;
            var limits = new com.arte.ainew.config.NewAiGenerationProperties(false, List.of(URI.create(origin)), List.of(), 0, 0, 0, null, 0, 0);
            try (var metadataRuntime = new com.arte.ainew.infrastructure.http.HttpConnectionRuntime(null, null, null, limits, java.time.Clock.systemUTC())) {
                var metadata = new HttpHealthToolAdapter(connection, new com.arte.ainew.infrastructure.http.HttpJsonGetTransport(metadataRuntime));
                var local = rig.tools.registry().registrations().getFirst();
                var remote = metadata.registration();
                var localDefinitions = local.source().discover().block();
                var remoteDefinitions = remote.source().discover().block();
                manifest = com.arte.ainew.application.tool.discovery.LocalToolDiscovery.manifest(new com.arte.ainew.spi.persistence.ToolCatalogStore.Batch(
                        List.of(local.provider(), remote.provider()), java.util.stream.Stream.concat(localDefinitions.schemas().stream(), remoteDefinitions.schemas().stream()).toList(),
                        java.util.stream.Stream.concat(localDefinitions.tools().stream(), remoteDefinitions.tools().stream()).toList()));
            }
            rig.context().withUserConfiguration(PlatformHttpToolConfiguration.class).withPropertyValues(
                    "arte.ai-new-tool-http.enabled=true", "arte.ai-new-tool-http.endpoint=" + origin + "/health",
                    "arte.ai-new-tool-http.allowed-origins[0]=" + origin, "arte.ai-new-execution.scheduling.tool-manifest=" + manifest).run(c -> {
                assertNull(c.getStartupFailure());
                assertEquals(0, requests.get());
                var mvc = rig.mvc(c);
                var tool = c.getBean(HttpHealthToolAdapter.class).revision().tool().definition();
                var review = Map.of("examples", List.of(Map.of("input", Map.of(), "output", Map.of("status", "UP"))),
                        "riskEvidence", "Fixed anonymous read-only GET; no business data egress; explicitly free", "minimumApproval", "NONE");
                rig.post(mvc, "tool/publishTool", Map.of("scope", rig.scope, "tool", tool, "expectedVersion", "0", "reason", "review", "review", review), "publish-http", 200);
                var binding = rig.post(mvc, "tool/prepareDefaultBinding", Map.of("scope", rig.scope, "tool", tool, "configuration", Map.of()), UUID.randomUUID().toString(), 200).get("bindingId").asString();
                var input = Map.of("bindingId", binding, "arguments", Map.of(), "timeoutSeconds", 30);
                var preview = rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", input), null, 200);
                var command = Map.of("scope", rig.scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
                String id = rig.post(mvc, "tool/invokeTool", command, "one", 202).get("executionId").asString();
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals(1, requests.get());
                var result = rig.post(mvc, "invocation/getInvocationResult", Map.of("scope", rig.scope, "invocationId", id), null, 200);
                assertTrue(result.toString().contains("UP"));
                assertFalse(result.toString().contains("private"));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement", Integer.class));
                // 当前撤权阻止新受理及原键重放；不因免费或旧调用成功继续外联。
                rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                rig.post(mvc, "tool/invokeTool", command, "one", 403);
                assertEquals(1, requests.get());
            });
        } finally {
            server.disposeNow();
        }
    }
}
