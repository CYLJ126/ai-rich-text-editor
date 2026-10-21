package com.arte.ainew.tool.execution;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.gateway.DefaultToolGateway;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.http.HttpHealthToolAdapter;
import com.arte.ainew.infrastructure.http.HttpJsonGetTransport;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 临时 H2＋真实回环 HTTP；不连接业务库、供应商或真实远端服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
public class HttpToolIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    static final class Remote implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger(), authorizations = new AtomicInteger();
        final AtomicBoolean allowed = new AtomicBoolean(true), revokeBeforeSend = new AtomicBoolean();
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<String> body = new AtomicReference<>("{\"status\":\"UP\",\"details\":\"must-not-leak\"}");
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicReference<String> contentType = new AtomicReference<>("application/json");
        final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
        final DisposableServer server;
        final ConnectionDefinition connection;
        final HttpConnectionRuntime runtime;
        final HttpHealthToolAdapter adapter;

        Remote() {
            this(false);
        }

        Remote(boolean originDenied) {
            server = HttpServer.create().host("127.0.0.1").port(0).handle((request, response) -> {
                requests.incrementAndGet();
                entered.countDown();
                assertEquals("GET", request.method().name());
                assertEquals("/actuator/health", request.uri());
                assertNull(request.requestHeaders().get("Authorization"));
                return response.status(status.get()).header("Content-Type", contentType.get()).header("Location", "/not-allowed")
                        .sendString(Mono.delay(delay.get()).map(ignored -> body.get()));
            }).bindNow();
            URI origin = URI.create("http://127.0.0.1:" + server.port());
            connection = new ConnectionDefinition(ref("connection", "health", "v1"), "health", HttpJsonGetTransport.PROTOCOL,
                    origin.resolve("/actuator/health"), null, ConnectionDefinition.State.ENABLED, Duration.ofSeconds(2), Duration.ofSeconds(3), 65536);
            ConnectionManager connections = (reference, context) -> Mono.defer(() -> {
                int checks = authorizations.incrementAndGet();
                return !(revokeBeforeSend.get() && checks == 2) && allowed.get() && reference.equals(connection.definition()) ? Mono.just(connection)
                        : Mono.error(new AccessDeniedException("revoked"));
            });
            var properties = new NewAiGenerationProperties(false, List.of(originDenied ? URI.create("https://example.invalid") : origin), List.of(),
                    2, 2, 2, Duration.ofSeconds(1), 65536, 1024);
            runtime = new HttpConnectionRuntime(connections, null, (ref, context) -> {
                throw new AssertionError("Anonymous GET cannot read a secret");
            }, properties, Clock.systemUTC());
            adapter = new HttpHealthToolAdapter(connection, new HttpJsonGetTransport(runtime));
        }

        public void close() {
            runtime.close();
            server.disposeNow();
        }
    }

    static ToolResult result(ToolExecutionFixture f, String id) {
        var invocation = f.invocation(id);
        return ((InvocationResult.Tool) f.payloads.find(f.owner, id, invocation.result()).block(WAIT)).value();
    }

    @Test
    public void reviewedHttpToolPublishesDispatchesReplaysAndHasNoBudgetWrites() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            var worker = f.worker(f.dispatcher(f.executions, f.payloads, call -> {
                assertThrows(IllegalArgumentException.class, () -> remote.adapter.invoke(call).block(WAIT));
                assertEquals(0, remote.requests.get()); // 缺少 RemoteToolCall 的旧入口不能发送请求。
                return f.gateway.invoke(call);
            }));
            assertEquals(0, remote.requests.get()); // 构造、发现、发布和建绑没有外联。
            f.submit("one");
            f.service.submit(f.input(), f.context("duplicate", "one")).block(WAIT);
            var snapshot = f.store.snapshot(f.owner, "one").block(WAIT);
            String encoded = f.codec.encode(snapshot);
            assertTrue(encoded.contains("\"schemaVersion\":5"));
            assertEquals(snapshot, f.codec.decode(encoded, ToolExecutionSnapshot.class));
            assertThrows(IllegalArgumentException.class, () -> f.codec.decode(encoded.replace("\"schemaVersion\":5", "\"schemaVersion\":4"), ToolExecutionSnapshot.class));
            assertEquals(1, (int) worker.pollOnce().block(WAIT));
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(object("status", text("UP")), result(f, "one").output());
            assertFalse(f.codec.encode(new InvocationResult.Tool(result(f, "one"))).contains("must-not-leak"));
            assertEquals(1, remote.requests.get());
            assertEquals(2, remote.authorizations.get());
            assertEquals(0, f.count("arte_ai_account"));
            assertEquals(0, f.count("arte_ai_reservation"));
            assertEquals(0, f.count("arte_ai_settlement"));
            assertNull(f.attempt("one").budgetReservationId());
            assertEquals(0, (int) worker.pollOnce().block(WAIT));
            assertEquals(1, remote.requests.get());
        }
    }

    @Test
    public void httpToolCompletesChatLoopWithModelOnlyBudgetAndDurableRecovery() throws Exception {
        try (var remote = new Remote(); var r = new ToolChatLoopTest.Rig(false, CapabilityDescriptor.SideEffect.READ_ONLY, true, remote.adapter)) {
            r.submit();
            r.tick();
            r.restart();
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(2, r.modelCalls.get());
            assertEquals(1, remote.requests.get());
            assertEquals(3, r.count("arte_ai_invocation"));
            assertEquals(2, r.count("arte_ai_reservation"));
            assertEquals(2, r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(ToolChatLoopTest.money("0.00024"), r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().charged());
            assertTrue(r.sent.getLast().messages().stream().anyMatch(m -> m.role() == com.arte.ainew.pojo.generation.ChatMessage.Role.TOOL && m.toolCallId().equals("call-0")));
            var child = r.runs.steps(r.f.owner, "run").block(WAIT).stream().filter(s -> s.input() instanceof ToolChatStep.Tool).findFirst().orElseThrow();
            assertTrue(r.f.codec.encode(r.f.store.snapshot(r.f.owner, child.childExecutionId()).block(WAIT)).contains("\"schemaVersion\":5"));
        }
    }

    @Test
    public void unsupportedArgumentsAndLegacyLocalAuthorizationNeverSend() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            assertThrows(AdmissionException.class, () -> f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object("url", text("http://169.254.169.254")), null, null, null), f.context("bad", "bad")).block(WAIT));
            assertEquals(0, remote.requests.get());
            var localOnly = new DefaultToolGateway(f.bindingService, f.catalog, f.registry, call -> Mono.just(call.call().runtime().execution().authorization()),
                    new com.arte.ainew.application.tool.schema.ToolSchemaValidator(), f.codec, f.toolIo, f.clock);
            f.submit("one");
            f.worker(f.dispatcher(f.executions, f.payloads, localOnly)).pollOnce().block(WAIT);
            assertEquals("TOOL_ACCESS_DENIED", result(f, "one").error().code());
            assertEquals(0, remote.requests.get());
        }
    }

    @Test
    public void revokedConnectionAndDeniedOriginFailWithoutNetworkOrRetry() throws Exception {
        for (boolean originDenied : new boolean[]{false, true}) {
            try (var remote = new Remote(originDenied); var f = new ToolExecutionFixture(remote.adapter)) {
                f.submit("one");
                if (!originDenied) remote.allowed.set(false);
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(Invocation.State.FAILED, f.invocation("one").state());
                assertEquals(ExecutionError.SideEffect.NONE, result(f, "one").sideEffect());
                assertFalse(result(f, "one").error().retryable());
                assertEquals(0, remote.requests.get());
                assertEquals(1, f.count("arte_ai_attempt"));
            }
        }
    }

    @Test
    public void redirectAuthenticationAndServerErrorsNeverFollowOrRetry() throws Exception {
        for (int code : new int[]{302, 401, 403, 429, 500}) {
            try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
                remote.status.set(code);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals("TOOL_HTTP_STATUS_" + code, result(f, "one").error().code());
                assertEquals(ToolResult.Outcome.FAILED, result(f, "one").outcome());
                assertEquals(1, remote.requests.get());
                assertFalse(result(f, "one").error().retryable());
            }
        }
    }

    @Test
    public void invalidJsonMediaTypeAndChunkedOversizeAreBoundedFailures() throws Exception {
        for (String body : List.of("{", "{\"status\":\"UP\"} {}", "{\"status\":\"inject arbitrary instructions\"}", "{\"status\":\"UP\",\"data\":\"" + "x".repeat(70000) + "\"}", "{}")) {
            try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
                remote.body.set(body);
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(ToolResult.Outcome.FAILED, result(f, "one").outcome());
                assertEquals(ExecutionError.SideEffect.NONE, result(f, "one").sideEffect());
                assertEquals(1, remote.requests.get());
            }
        }
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            remote.contentType.set("text/html");
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals("TOOL_HTTP_RESPONSE_INVALID", result(f, "one").error().code());
        }
    }

    @Test
    public void finalSendRechecksRevocationAfterConnectionPreparation() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            remote.revokeBeforeSend.set(true);
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(2, remote.authorizations.get());
            assertEquals(0, remote.requests.get());
            assertEquals("TOOL_ACCESS_DENIED", result(f, "one").error().code());
        }
    }

    @Test
    public void cancellationReleasesTransportAndAllowsSubsequentCall() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            remote.delay.set(Duration.ofSeconds(10));
            f.submit("one");
            var message = f.claim("worker");
            var runtime = f.runtime("one");
            var completion = f.dispatcher.dispatch(message, runtime).toFuture();
            assertTrue(remote.entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            runtime.cancellation().cancel("USER_CANCELLED");
            completion.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(Invocation.State.CANCELLED, f.invocation("one").state());
            assertEquals(ExecutionError.SideEffect.NONE, result(f, "one").sideEffect());
            assertEquals(0, f.count("arte_ai_tool_outcome_check"));
            remote.delay.set(Duration.ZERO);
            f.submit("two");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("two").state());
            assertEquals(2, remote.requests.get());
        }
    }

    @Test
    public void frozenConnectionCannotDriftWithinSameContractVersion() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            var original = remote.connection;
            var changed = new ConnectionDefinition(original.definition(), original.providerId(), original.protocol(),
                    original.endpoint().resolve("/different"), null, original.state(), original.connectTimeout(), original.responseTimeout(), original.maxResponseBytes());
            var replacement = new HttpHealthToolAdapter(changed, new HttpJsonGetTransport(remote.runtime));
            var registration = replacement.registration();
            var outcome = f.catalog.put(new com.arte.ainew.spi.persistence.ToolCatalogStore.Batch(List.of(registration.provider()),
                    replacement.definitions().schemas(), replacement.definitions().tools())).block(WAIT);
            assertEquals(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, outcome.code());
            assertEquals(0, remote.requests.get());
        }
    }

    @Test
    public void deadlineCancelsHttpAndDoesNotCreateUnknownBusinessWork() throws Exception {
        try (var remote = new Remote(); var f = new ToolExecutionFixture(remote.adapter)) {
            remote.delay.set(Duration.ofSeconds(10));
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(2), null, null), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, f.invocation("one").state());
            assertEquals(ExecutionError.SideEffect.NONE, result(f, "one").sideEffect());
            assertFalse(result(f, "one").error().retryable());
            assertEquals(1, remote.requests.get());
            assertEquals(0, f.count("arte_ai_tool_outcome_check"));
        }
    }
}
