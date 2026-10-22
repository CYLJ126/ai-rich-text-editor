package com.arte.ainew.infrastructure.http;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 手动测试必应 MCP
 * <p>
 * 仅本机模拟服务；常规测试不会访问魔搭专属地址。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 11:00 ✾
 */
public class ModelScopeBingManualClientTest {

    @Test
    public void jsonLifecycleSearchesDuFuAndPrintsMissingRemoteOutputSchema() {
        try (var remote = new Remote("json"); var output = new ByteArrayOutputStream()) {
            var result = ModelScopeBingManualClient.run(remote.connection, "杜甫", new PrintStream(output, true, StandardCharsets.UTF_8));
            assertFalse(result.path("isError").asBoolean());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"), remote.methods);
            assertEquals(1, remote.calls.get());
            assertEquals(1, remote.deletes.get());
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("问题=杜甫"));
            assertTrue(text.contains("远端 inputSchema"));
            assertTrue(text.contains("未声明"));
            assertTrue(text.contains("搜索正文"));
            assertFalse(text.contains("private-session"));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @Test(timeout = 10000)
    public void sseStopsAfterMatchingResponseWithoutWaitingForStreamClose() {
        try (var remote = new Remote("sse")) {
            var result = ModelScopeBingManualClient.run(remote.connection, "杜甫", new PrintStream(java.io.OutputStream.nullOutputStream()));
            assertTrue(result.path("content").isArray());
            assertEquals(1, remote.calls.get());
            assertEquals(1, remote.deletes.get());
        }
    }

    @Test
    public void missingToolStopsBeforeSearch() {
        try (var remote = new Remote("missing")) {
            var error = assertThrows(IllegalStateException.class, () -> ModelScopeBingManualClient.run(remote.connection, "杜甫",
                    new PrintStream(java.io.OutputStream.nullOutputStream())));
            assertTrue(error.getMessage().contains("没有 bing_search"));
            assertEquals(0, remote.calls.get());
            assertEquals(1, remote.deletes.get());
        }
    }

    @Test
    public void providerHttpFailureIsVisibleAndDoesNotRetrySearch() {
        try (var remote = new Remote("http")) {
            var error = assertThrows(IllegalStateException.class, () -> ModelScopeBingManualClient.run(remote.connection, "杜甫",
                    new PrintStream(java.io.OutputStream.nullOutputStream())));
            assertEquals("tools/call HTTP 503", error.getMessage());
            assertEquals(1, remote.calls.get());
            assertEquals(1, remote.deletes.get());
        }
    }

    @Test
    public void toolErrorIsNotReportedAsSuccessfulSearch() {
        try (var remote = new Remote("error")) {
            var error = assertThrows(IllegalStateException.class, () -> ModelScopeBingManualClient.run(remote.connection, "杜甫",
                    new PrintStream(java.io.OutputStream.nullOutputStream())));
            assertTrue(error.getMessage().contains("isError=true"));
            assertEquals(1, remote.calls.get());
            assertEquals(1, remote.deletes.get());
        }
    }

    @Test
    public void mismatchedRpcIdFailsBeforeSearch() {
        try (var remote = new Remote("id")) {
            var error = assertThrows(IllegalStateException.class, () -> ModelScopeBingManualClient.run(remote.connection, "杜甫",
                    new PrintStream(java.io.OutputStream.nullOutputStream())));
            assertTrue(error.getMessage().contains("id 不匹配"));
            assertEquals(0, remote.calls.get());
        }
    }

    private static final class Remote implements AutoCloseable {
        final List<String> methods = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger(), deletes = new AtomicInteger();
        final DisposableServer server;
        final ConnectionDefinition connection;

        Remote(String mode) {
            var json = GenerationJson.mapper(65536);
            server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
                assertEquals("/mcp", request.uri());
                assertNull(request.requestHeaders().get("Authorization"));
                if (request.method().name().equals("DELETE")) {
                    assertEquals("private-session", request.requestHeaders().get("Mcp-Session-Id"));
                    deletes.incrementAndGet();
                    return response.status(204).send();
                }
                return request.receive().aggregate().asString().flatMap(body -> {
                    var message = json.readTree(body);
                    String method = message.path("method").asString();
                    methods.add(method);
                    Object value;
                    if (method.equals("initialize")) {
                        value = Map.of("protocolVersion", McpStreamableHttpTransport.VERSION, "capabilities", Map.of("tools", Map.of()),
                                "serverInfo", Map.of("name", "local-bing", "version", "1"));
                        response.header("Mcp-Session-Id", "private-session");
                    } else {
                        assertEquals("private-session", request.requestHeaders().get("Mcp-Session-Id"));
                        assertEquals(McpStreamableHttpTransport.VERSION, request.requestHeaders().get("MCP-Protocol-Version"));
                        if (method.equals("notifications/initialized")) return response.status(202).send().then();
                        if (method.equals("tools/list")) {
                            value = Map.of("tools", List.of(Map.of("name", mode.equals("missing") ? "other" : "bing_search",
                                    "inputSchema", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"))))));
                        } else {
                            assertEquals("tools/call", method);
                            assertEquals("bing_search", message.path("params").path("name").asString());
                            var arguments = message.path("params").path("arguments");
                            assertEquals("杜甫", arguments.path("query").asString());
                            assertEquals(10, arguments.path("count").asInt());
                            assertEquals(0, arguments.path("offset").asInt());
                            calls.incrementAndGet();
                            if (mode.equals("http")) return response.status(503).send().then();
                            value = Map.of("isError", mode.equals("error"), "content", List.of(Map.of("type", "text", "text",
                                    json.writeValueAsString(Map.of("query", "杜甫", "results", List.of(), "totalResults", 0)))));
                        }
                    }
                    String payload = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", mode.equals("id") ? 99 : message.path("id").asInt(), "result", value));
                    if (mode.equals("sse"))
                        return response.header("Content-Type", "text/event-stream").sendString(Flux.concat(
                                Flux.just("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n", "data: " + payload + "\n\n"),
                                Mono.<String>never())).then();
                    return response.header("Content-Type", "application/json").sendString(Mono.just(payload)).then();
                });
            }).bindNow();
            connection = new ConnectionDefinition(new DefinitionRef("connection", "manual-bing", "v1"), "manual-bing", ToolDefinitionRevision.MCP_PROTOCOL,
                    URI.create("http://127.0.0.1:" + server.port() + "/mcp"), null, ConnectionDefinition.State.ENABLED,
                    Duration.ofSeconds(2), Duration.ofSeconds(3), 65536);
        }

        @Override
        public void close() {
            server.disposeNow();
        }
    }
}
