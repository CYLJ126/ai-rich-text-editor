package com.arte.ainew.tool.execution;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.GenerationJson;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.http.McpHealthToolAdapter;
import com.arte.ainew.infrastructure.http.McpStreamableHttpTransport;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolDefinitionRevision;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.serialization.ToolCatalogDigests;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 回环 MCP＋H2 真受理／派发；不访问真实 MCP 或业务数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public class McpToolIntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    static final class Remote implements AutoCloseable {
        final List<String> methods = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger(), deletes = new AtomicInteger(), checks = new AtomicInteger();
        final AtomicBoolean allowed = new AtomicBoolean(true), revokeAfterList = new AtomicBoolean();
        final AtomicReference<String> fault = new AtomicReference<>(""), type = new AtomicReference<>("application/json");
        final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final boolean stateful;
        final DisposableServer server;
        final ConnectionDefinition connection;
        final HttpConnectionRuntime runtime;
        McpHealthToolAdapter adapter;
        final tools.jackson.databind.json.JsonMapper json = GenerationJson.mapper(65536);

        Remote() {
            this(true, false);
        }

        Remote(boolean stateful, boolean deniedOrigin) {
            this.stateful = stateful;
            server = HttpServer.create().host("127.0.0.1").port(0).handle((req, res) -> {
                assertEquals("/mcp", req.uri());
                assertNull(req.requestHeaders().get("Authorization"));
                if (req.method().name().equals("DELETE")) {
                    deletes.incrementAndGet();
                    assertEquals("private-session", req.requestHeaders().get("Mcp-Session-Id"));
                    return res.status(204).send();
                }
                assertEquals("POST", req.method().name());
                assertTrue(req.requestHeaders().get("Accept").contains("application/json"));
                assertTrue(req.requestHeaders().get("Accept").contains("text/event-stream"));
                return req.receive().aggregate().asString().flatMap(body -> {
                    var node = json.readTree(body);
                    String method = node.path("method").asString();
                    methods.add(method);
                    if (!method.equals("initialize")) {
                        assertEquals(McpStreamableHttpTransport.VERSION, req.requestHeaders().get("MCP-Protocol-Version"));
                        assertEquals(stateful ? "private-session" : null, req.requestHeaders().get("Mcp-Session-Id"));
                    }
                    if (method.equals("notifications/initialized")) return res.status(202).send().then();
                    Object value;
                    if (method.equals("initialize")) {
                        assertEquals(0, node.path("params").path("capabilities").size());
                        value = Map.of("protocolVersion", fault.get().equals("version") ? "2024-11-05" : McpStreamableHttpTransport.VERSION,
                                "capabilities", fault.get().equals("capabilities") ? Map.of() : Map.of("tools", Map.of()), "serverInfo", Map.of("name", "test", "version", "1"),
                                "instructions", "private-provider-instructions-must-not-leak");
                        if (stateful)
                            res.header("Mcp-Session-Id", fault.get().equals("session-long") ? "x".repeat(257) : "private-session");
                    } else if (method.equals("tools/list")) {
                        var tool = new HashMap<String, Object>();
                        tool.put("name", fault.get().equals("missing") ? "other" : "health");
                        tool.put("inputSchema", ToolCatalogDigests.jsonValue(adapter.definitions().schemas().get(0).document()));
                        tool.put("outputSchema", fault.get().equals("schema") ? Map.of("type", "string") : ToolCatalogDigests.jsonValue(adapter.definitions().schemas().get(1).document()));
                        var list = new HashMap<String, Object>();
                        list.put("tools", fault.get().equals("duplicate") ? List.of(tool, tool) : List.of(tool));
                        if (fault.get().equals("pagination")) list.put("nextCursor", "more");
                        value = list;
                        if (fault.get().equals("session-change")) res.header("Mcp-Session-Id", "other-session");
                        if (revokeAfterList.get()) allowed.set(false);
                    } else {
                        assertEquals("tools/call", method);
                        calls.incrementAndGet();
                        entered.countDown();
                        assertEquals("health", node.path("params").path("name").asString());
                        assertEquals(0, node.path("params").path("arguments").size());
                        var output = Map.of("status", "UP");
                        var result = new HashMap<String, Object>();
                        result.put("structuredContent", output);
                        result.put("content", List.of(Map.of("type", "text", "text", json.writeValueAsString(output))));
                        if (fault.get().equals("image"))
                            result.put("content", List.of(Map.of("type", "image", "data", "private-image", "mimeType", "image/png")));
                        if (fault.get().equals("raw-text")) {
                            result.remove("structuredContent");
                            result.put("content", List.of(Map.of("type", "text", "text", "private-text")));
                        }
                        if (fault.get().equals("mismatch"))
                            result.put("content", List.of(Map.of("type", "text", "text", "{\"status\":\"DOWN\"}")));
                        if (fault.get().equals("error")) result.put("isError", true);
                        if (fault.get().equals("extra")) result.put("task", Map.of("id", "not-final"));
                        if (fault.get().equals("oversize")) result.put("_meta", Map.of("private", "x".repeat(70000)));
                        value = result;
                    }
                    var envelope = new HashMap<String, Object>();
                    envelope.put("jsonrpc", "2.0");
                    envelope.put("id", fault.get().equals("id") ? "other" : node.path("id").asString());
                    envelope.put("result", value);
                    if (fault.get().equals("rpc")) {
                        envelope.remove("result");
                        envelope.put("error", Map.of("code", -32603, "message", "private-error"));
                    }
                    String payload = json.writeValueAsString(envelope);
                    if (fault.get().equals("duplicate-json"))
                        payload = payload.replace("\"jsonrpc\":\"2.0\"", "\"jsonrpc\":\"2.0\",\"jsonrpc\":\"1.0\"");
                    if (fault.get().equals("trailing")) payload += " {}";
                    if (type.get().equals("text/event-stream")) {
                        String leading = fault.get().equals("server-request") ? "data: {\"jsonrpc\":\"2.0\",\"id\":\"ping\",\"method\":\"sampling/createMessage\"}\n\n" : "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n";
                        payload = (fault.get().equals("events") ? leading.repeat(65) : leading) + "event: message\ndata: " + payload + "\n\n";
                    }
                    int status = fault.get().startsWith("http-") ? Integer.parseInt(fault.get().substring(5)) : 200;
                    return res.status(status).header("Content-Type", type.get()).header("Location", "/other")
                            .sendString(Mono.delay(method.equals("tools/call") ? delay.get() : Duration.ZERO).thenReturn(payload)).then();
                });
            }).bindNow();
            var origin = URI.create("http://127.0.0.1:" + server.port());
            connection = new ConnectionDefinition(ref("connection", "mcp-health", "v1"), "mcp-health", ToolDefinitionRevision.MCP_PROTOCOL,
                    origin.resolve("/mcp"), null, ConnectionDefinition.State.ENABLED, Duration.ofSeconds(2), Duration.ofSeconds(3), 65536);
            ConnectionManager manager = (ref, context) -> Mono.defer(() -> {
                checks.incrementAndGet();
                return allowed.get() ? Mono.just(connection) : Mono.error(new AccessDeniedException("revoked"));
            });
            runtime = new HttpConnectionRuntime(manager, null, (ref, context) -> Mono.error(new AssertionError("No secret for anonymous MCP")),
                    new NewAiGenerationProperties(false, List.of(deniedOrigin ? URI.create("https://denied.example") : origin), List.of(), 2, 2, 2, Duration.ofSeconds(1), 65536, 8192), Clock.systemUTC());
            adapter = new McpHealthToolAdapter(connection, new McpStreamableHttpTransport(runtime));
        }

        public void close() {
            runtime.close();
            server.disposeNow();
        }
    }

    @Test
    public void fullLifecycleIsPrivateAndFreeAndReplayDoesNotRepeatCall() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            assertTrue(r.methods.isEmpty());
            f.submit("one");
            f.service.submit(f.input(), f.context("dup", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(String.valueOf(f.invocation("one").error()) + r.methods, Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/call"), r.methods);
            assertEquals(1, r.calls.get());
            assertEquals(1, r.deletes.get());
            assertEquals(object("status", text("UP")), HttpToolIntegrationTest.result(f, "one").output());
            String payload = f.codec.encode(new InvocationResult.Tool(HttpToolIntegrationTest.result(f, "one")));
            assertFalse(payload.contains("private-"));
            assertEquals(0, f.count("arte_ai_reservation"));
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, r.calls.get());
        }
    }

    @Test
    public void jsonAndSseAndStatelessServersAreSupported() throws Exception {
        for (String type : List.of("application/json", "text/event-stream"))
            try (var r = new Remote(false, false); var f = new ToolExecutionFixture(r.adapter)) {
                r.type.set(type);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(String.valueOf(f.invocation("one").error()) + r.methods, Invocation.State.SUCCEEDED, f.invocation("one").state());
                assertEquals(1, r.calls.get());
                assertEquals(0, r.deletes.get());
            }
    }

    @Test
    public void handshakeAndCatalogDriftNeverReachToolCall() throws Exception {
        for (String fault : List.of("version", "capabilities", "schema", "missing", "duplicate", "pagination", "id", "rpc", "duplicate-json", "trailing"))
            try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
                r.fault.set(fault);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(fault, Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(fault, 0, r.calls.get());
                assertFalse(f.codec.encode(new InvocationResult.Tool(HttpToolIntegrationTest.result(f, "one"))).contains("private-"));
            }
    }

    @Test
    public void unsupportedContentAndUnfinishedTasksCannotBecomeSuccess() throws Exception {
        for (String fault : List.of("image", "raw-text", "mismatch", "error", "extra", "oversize"))
            try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
                r.fault.set(fault);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(fault, Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(1, r.calls.get());
                assertEquals(ExecutionError.SideEffect.NONE, HttpToolIntegrationTest.result(f, "one").sideEffect());
            }
    }

    @Test
    public void redirectsAuthFailureAndServerErrorNeverRetryOrFallback() throws Exception {
        for (int code : new int[]{302, 401, 403, 429, 500})
            try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
                r.fault.set("http-" + code);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(1, r.methods.size());
                assertEquals(0, r.calls.get());
            }
    }

    @Test
    public void deniedOriginAndRevocationBetweenHandshakeAndCallPreventSending() throws Exception {
        for (boolean origin : new boolean[]{true, false})
            try (var r = new Remote(true, origin); var f = new ToolExecutionFixture(r.adapter)) {
                r.revokeAfterList.set(!origin);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(0, r.calls.get());
                if (origin) assertEquals(0, r.methods.size());
            }
    }

    @Test
    public void unadvertisedServerRequestsAreRejectedWithoutExecutingLocalCapabilities() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            r.type.set("text/event-stream");
            r.fault.set("server-request");
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(0, r.calls.get());
        }
    }

    @Test
    public void newProtocolSnapshotsCannotBeRelabeledAsLegacyHttp() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            f.submit("one");
            var fixed = f.store.snapshot(f.owner, "one").block(WAIT);
            String snapshot = f.codec.encode(fixed);
            assertTrue(snapshot.contains("\"schemaVersion\":7"));
            assertThrows(IllegalArgumentException.class, () -> f.codec.decode(snapshot.replace("\"schemaVersion\":7", "\"schemaVersion\":5"), ToolExecutionSnapshot.class));
            String tool = f.codec.encode(r.adapter.revision());
            assertTrue(tool.contains("\"schemaVersion\":3"));
            assertThrows(IllegalArgumentException.class, () -> f.codec.decode(tool.replace("\"schemaVersion\":3", "\"schemaVersion\":1"), ToolDefinitionRevision.class));
        }
    }

    @Test
    public void toolChatUsesSameMcpLifecycleAndOnlyModelFees() throws Exception {
        try (var r = new Remote(); var run = new ToolChatLoopTest.Rig(false, CapabilityDescriptor.SideEffect.READ_ONLY, true, r.adapter)) {
            run.submit();
            run.tick();
            run.restart();
            run.until(v -> v.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, run.run().state());
            assertEquals(1, r.calls.get());
            assertEquals(2, run.modelCalls.get());
            assertEquals(2, run.count("arte_ai_reservation"));
            String snapshot = run.f.codec.encode(run.runs.snapshot(run.f.owner, "run").block(WAIT));
            assertTrue(snapshot.contains("\"schemaVersion\":4"));
        }
    }

    @Test
    public void cancellationDisconnectsWithoutRetryAndReleasesCapacity() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            r.delay.set(Duration.ofSeconds(10));
            f.submit("one");
            var message = f.claim("worker");
            var runtime = f.runtime("one");
            var completion = f.dispatcher.dispatch(message, runtime).toFuture();
            assertTrue(r.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            runtime.cancellation().cancel("USER_CANCELLED");
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Invocation.State.CANCELLED, f.invocation("one").state());
            assertEquals(1, r.calls.get());
            assertEquals(0, f.count("arte_ai_tool_outcome_check"));
            r.delay.set(Duration.ZERO);
            f.submit("two");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("two").state());
            assertEquals(2, r.calls.get());
        }
    }

    @Test
    public void totalDeadlineDoesNotResetDuringHandshakeOrCreateBusinessUnknownWork() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            r.delay.set(Duration.ofSeconds(10));
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(2), null, null), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, f.invocation("one").state());
            assertEquals(1, r.calls.get());
            assertEquals(ExecutionError.SideEffect.NONE, HttpToolIntegrationTest.result(f, "one").sideEffect());
            assertEquals(0, f.count("arte_ai_tool_outcome_check"));
        }
    }

    @Test
    public void immutableProtocolAndEndpointRequireNewPublishedVersion() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            var old = r.connection;
            var changed = new ConnectionDefinition(old.definition(), old.providerId(), old.protocol(), old.endpoint().resolve("/other"), null, old.state(), old.connectTimeout(), old.responseTimeout(), old.maxResponseBytes());
            var replacement = new McpHealthToolAdapter(changed, new McpStreamableHttpTransport(r.runtime));
            assertEquals(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, f.catalog.put(new com.arte.ainew.spi.persistence.ToolCatalogStore.Batch(List.of(replacement.registration().provider()), replacement.definitions().schemas(), replacement.definitions().tools())).block(WAIT).code());
            assertEquals(0, r.methods.size());
            assertThrows(IllegalArgumentException.class, () -> new com.arte.ainew.application.tool.discovery.LocalToolRegistry.Registration(r.adapter.registration().provider(), r.adapter.registration().source(), Map.of(), Map.of((ExecutionTarget.Remote) r.adapter.revision().target(), r.adapter)));
        }
    }

    @Test
    public void sessionRotationAndUnboundedSseNotificationsAreRejected() throws Exception {
        for (String fault : List.of("session-long", "session-change", "events"))
            try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
                r.fault.set(fault);
                if (fault.equals("events")) r.type.set("text/event-stream");
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(fault, Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(0, r.calls.get());
            }
    }

    @Test
    public void unreviewedArgumentsAndLocalOnlyExecutionAccessNeverStartMcp() throws Exception {
        try (var r = new Remote(); var f = new ToolExecutionFixture(r.adapter)) {
            assertThrows(RuntimeException.class, () -> f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object("url", text("http://169.254.169.254")), null, null, null), f.context("bad", "bad")).block(WAIT));
            var legacy = new com.arte.ainew.application.gateway.DefaultToolGateway(f.bindingService, f.catalog, f.registry,
                    call -> Mono.just(call.call().runtime().execution().authorization()), new com.arte.ainew.application.tool.schema.ToolSchemaValidator(), f.codec, f.toolIo, f.clock);
            f.submit("one");
            f.worker(f.dispatcher(f.executions, f.payloads, legacy)).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(0, r.methods.size());
        }
    }
}
