package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.gateway.DefaultModelGateway;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.infrastructure.http.*;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekWire;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.spi.adapter.ConnectionRuntime;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 真正走本地 HTTP／Spring SSE codec／池化连接；不访问数据库或真实供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public class GenerationGatewayTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private record Reply(int status, String contentType, String body, String tail, long pauseMillis) {
        static Reply sse(String body) {
            return new Reply(200, "text/event-stream", body, "", 0);
        }
    }

    private static String chunk(String content, String finish, String usage) {
        var json = GenerationJson.mapper(65536);
        return "data: {\"id\":\"chat-1\",\"model\":\"deepseek-actual\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":"
                + json.writeValueAsString(content) + "},\"finish_reason\":" + json.writeValueAsString(finish) + "}],\"usage\":" + usage + "}\n\n";
    }

    private static final String KNOWN_USAGE = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";
    private static final String DONE = "data: [DONE]\n\n";

    private static String success() {
        return chunk("你好", "stop", KNOWN_USAGE) + DONE;
    }

    private static final class Rig implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2);
        final AtomicReference<Reply> reply = new AtomicReference<>(Reply.sse(success()));
        final AtomicReference<String> requestBody = new AtomicReference<>();
        final AtomicReference<String> requestAuth = new AtomicReference<>();
        final AtomicReference<String> requestPath = new AtomicReference<>();
        final AtomicReference<String> token = new AtomicReference<>("test-token-one");
        final AtomicInteger requests = new AtomicInteger(), credentialReads = new AtomicInteger(), releases = new AtomicInteger();
        final NewAiProperties properties;
        final NewAiGenerationProperties generation;
        final FixedControlCatalog catalog;
        final ExecutionContextFactory factory;
        final HttpConnectionRuntime runtime;
        final ConnectionDefinition connection;
        final DefaultModelGateway<DeepSeekWire.Request, SseFrame, HttpConnectionRuntime.Handle> gateway;

        Rig() throws Exception {
            this(65536, 65536);
        }

        Rig(int responseLimit, int frameLimit) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                requestPath.set(exchange.getRequestURI().getPath());
                requestAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                var response = reply.get();
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
                if (response.status() == 302) {
                    exchange.getResponseHeaders().set("Location", "/redirect-target");
                }
                exchange.sendResponseHeaders(response.status(), 0);
                try (var out = exchange.getResponseBody()) {
                    out.write(response.body().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    if (response.pauseMillis() > 0) {
                        Thread.sleep(response.pauseMillis());
                    }
                    out.write(response.tail().getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException cancelled) { /* 客户端取消时服务端连接关闭是预期行为。 */ } finally {
                    exchange.close();
                }
            });
            server.start();
            var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var base = AdmissionFixture.properties();
            var old = base.connections().getFirst();
            connection = new ConnectionDefinition(old.definition(), "deepseek", ChatCompletionsSseProtocolAdapter.DEFINITION,
                    URI.create(origin + "/v1"), old.credential(), old.state(), old.connectTimeout(), old.responseTimeout(), responseLimit);
            var binding = base.bindings().getFirst();
            properties = new NewAiProperties(true, base.dataSourceBean(), base.releaseRef(), base.persistence(), base.limits(),
                    base.grants(), base.capabilities(), List.of(new ResolvedBinding(binding.definition(), binding.capability(), binding.connection(),
                    "deepseek-chat", binding.contextWindowTokens(), binding.rate())), List.of(connection), base.rates(), base.budgets());
            generation = new NewAiGenerationProperties(true, List.of(origin), List.of(), 2, 1, 1, Duration.ofSeconds(2), frameLimit, 65536);
            var clock = Clock.systemUTC();
            var resolver = new FixedExecutionAuthorizationResolver(properties);
            var authorization = new AdmissionAuthorization(resolver, properties, clock);
            factory = new ExecutionContextFactory(resolver, clock);
            catalog = new FixedControlCatalog(properties, authorization, clock);
            ConnectionCredentialResolver credentials = (secret, context) -> Mono.fromSupplier(() -> {
                credentialReads.incrementAndGet();
                if (token.get() == null) {
                    throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
                }
                return new ConnectionCredentialResolver.BearerCredential(token.get());
            });
            runtime = new HttpConnectionRuntime(catalog::resolveConnection, authorization, credentials, generation, clock);
            ConnectionRuntime<HttpConnectionRuntime.Handle> tracking = new ConnectionRuntime<>() {
                public Mono<Lease<HttpConnectionRuntime.Handle>> acquire(ConnectionDefinition definition, ExecutionRuntimeContext context) {
                    return runtime.acquire(definition, context);
                }

                public Mono<Void> release(Lease<HttpConnectionRuntime.Handle> lease) {
                    return runtime.release(lease).doOnSuccess(ignored -> releases.incrementAndGet());
                }

                public Mono<Void> invalidate(com.arte.ainew.common.reference.DefinitionRef ref, com.arte.ainew.common.execution.ExecutionContext context) {
                    return runtime.invalidate(ref, context);
                }
            };
            gateway = new DefaultModelGateway<>(catalog, catalog, catalog::resolveConnection, tracking,
                    new DeepSeekGenerationProviderAdapter(properties.capabilities(), GenerationJson.mapper(frameLimit)),
                    new ChatCompletionsSseProtocolAdapter<>(GenerationJson.mapper(frameLimit), generation), clock);
        }

        GatewayCall<GenerationRequest> call() {
            return call(Duration.ofSeconds(8), 4096);
        }

        GatewayCall<GenerationRequest> call(Duration duration, int maxOutputBytes) {
            var context = factory.create(UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()),
                    new ExecutionContextRequest("tenant", "workspace", AdmissionFixture.REGULAR, Duration.ofSeconds(8), null,
                            "alice-budget", "release-v1", "generation-test")).block(WAIT);
            if (!duration.equals(Duration.ofSeconds(8))) {
                context = new com.arte.ainew.common.execution.ExecutionContext(context.executionId(), context.traceId(), context.authorization(),
                        Clock.systemUTC().instant().plus(duration), null, context.budgetRef(), context.releaseRef(), context.idempotencyKey());
            }
            var input = new GenerationRequest(List.of(new ChatMessage("user-message", ChatMessage.Role.USER,
                    List.of(new ChatMessage.Text("你好")), List.of(), null)),
                    new GenerationOptions(128, new BigDecimal("0.7"), new BigDecimal("0.9"), List.of("END")), List.of(), new GenerationRequest.TextOutput());
            var request = new InvocationRequest<>(AdmissionFixture.CAP, AdmissionFixture.BINDING,
                    com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION, input,
                    new ExecutionOptions(context.deadline(), 1, maxOutputBytes, 0, 0, Duration.ofSeconds(8)), context);
            var now = Clock.systemUTC().instant();
            // Gateway 层测试的结构化发送事实；生产环境必须由第 5 步先耐久提交这些事实。
            var attempt = new Attempt("attempt-1", context.executionId(), 1, "worker-test", 1, now.plusSeconds(20), 1,
                    Attempt.State.RUNNING, Attempt.Dispatch.MAY_HAVE_EXECUTED, "remote-request-1", "reservation-1", Usage.unknown(), null, now, now);
            return new GatewayCall<>(request, properties.bindings().getFirst(), attempt, ExecutionRuntimeContext.start(context));
        }

        List<GenerationSignal> run() {
            return gateway.generate(call()).collectList().block(WAIT);
        }

        @Override
        public void close() {
            runtime.close();
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private static GenerationSignal.Failure failed(List<GenerationSignal> signals, String code) {
        assertEquals(1, signals.stream().filter(signal -> !(signal instanceof GenerationSignal.Delta)).count());
        var failure = (GenerationSignal.Failure) signals.getLast();
        assertEquals(code, failure.error().code());
        return failure;
    }

    private static ModelResult result(List<GenerationSignal> signals) {
        assertEquals(1, signals.stream().filter(signal -> !(signal instanceof GenerationSignal.Delta)).count());
        return ((GenerationSignal.Result) signals.getLast()).result();
    }

    private static String text(ModelResult result) {
        return ((ChatMessage.Text) result.outputs().getFirst().content().getFirst()).text();
    }

    private static void awaitReleases(Rig rig, int expected) throws InterruptedException {
        var limit = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (rig.releases.get() < expected && System.nanoTime() < limit) {
            Thread.sleep(10);
        }
        assertEquals(expected, rig.releases.get());
    }

    @Test
    public void providerTimingIncludesSentHeadersAndFirstFrameWithoutCredentialsOrText() throws Exception {
        var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(com.arte.ainew.application.support.InvocationTiming.class);
        var previous = logger.getLevel();
        var additive = logger.isAdditive();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("timing-test", null, null, false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(org.apache.logging.log4j.Level.INFO);
        logger.setAdditive(false);
        try (var rig = new Rig()) {
            var call = rig.call();
            rig.gateway.generate(call).collectList().block(WAIT);
            // [DONE] 经 takeUntil 结束正常模型响应，下游停止消费会取消协议正文流。
            for (var stage : List.of("PROVIDER_REQUEST_SENT", "PROVIDER_HEADERS", "PROVIDER_FIRST_DATA_FRAME", "PROVIDER_STREAM_END_CANCEL")) {
                assertTrue(messages.toString(), messages.stream().anyMatch(message -> message.contains("stage=" + stage)
                        && message.contains("invocationId=" + call.runtime().execution().executionId())
                        && message.contains("attemptId=" + call.attempt().attemptId())));
            }
            assertFalse(messages.toString().contains("test-token"));
            assertFalse(messages.toString().contains("你好"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            logger.setAdditive(additive);
            appender.stop();
        }
    }

    @Test
    public void coldSingleRequestPreservesWhitespaceAndMapsActualModelAndFinalUsage() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(": heartbeat\n\n" + chunk("你", null, "null") + chunk(" \n", null, "null")
                    + chunk("好", "stop", "null")
                    + "data: {\"id\":\"chat-1\",\"model\":\"deepseek-actual\",\n"
                    + "data: \"choices\":[],\"usage\":" + KNOWN_USAGE + "}\n\n" + DONE));
            var stream = rig.gateway.generate(rig.call());
            assertEquals(0, rig.requests.get());
            assertEquals(0, rig.credentialReads.get());
            var signals = stream.collectList().block(WAIT);
            var model = result(signals);
            assertTrue(model.complete());
            assertEquals("你 \n好", text(model));
            assertEquals("deepseek-actual", model.model().modelId());
            assertEquals(new Usage(Usage.Basis.PROVIDER_REPORTED, 3L, 2L, 5L), model.usage());
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.releases.get());
            assertEquals("/v1/chat/completions", rig.requestPath.get());
            assertEquals("Bearer test-token-one", rig.requestAuth.get());
            var request = GenerationJson.mapper(65536).readTree(rig.requestBody.get());
            assertEquals("deepseek-chat", request.get("model").asString());
            assertTrue(request.get("stream").asBoolean());
            assertTrue(request.get("stream_options").get("include_usage").asBoolean());
            assertEquals("disabled", request.get("thinking").get("type").asString());
            assertEquals(128, request.get("max_tokens").asInt());
            assertEquals("END", request.get("stop").get(0).asString());
            assertEquals("user", request.get("messages").get(0).get("role").asString());
            // 同一池下一次发送重新解析凭据；没有请求级凭据缓存。
            rig.token.set("test-token-two");
            result(rig.run());
            assertEquals("Bearer test-token-two", rig.requestAuth.get());
            assertEquals(2, rig.credentialReads.get());
        }
    }

    @Test
    public void eofAfterFinishIsFailureAndRetainsPartialContentAndReportedUsage() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("partial", "stop", KNOWN_USAGE)));
            var failure = failed(rig.run(), "PROVIDER_STREAM_INTERRUPTED");
            assertEquals(ExecutionError.Certainty.UNKNOWN, failure.error().certainty());
            assertFalse(failure.partialResult().complete());
            assertEquals("partial", text(failure.partialResult()));
            assertEquals(5L, failure.usage().totalTokens().longValue());
            assertEquals(1, rig.releases.get());
        }
    }

    @Test
    public void missingUsageRemainsUnknownAndLengthIsAnIncompleteResult() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("truncated", "length", "null") + DONE));
            var result = result(rig.run());
            assertFalse(result.complete());
            assertEquals(ModelResult.FinishReason.LENGTH, result.finishReason());
            assertEquals(Usage.unknown(), result.usage());
        }
    }

    @Test
    public void doneWithoutFinishIsFailure() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("partial", null, "null") + DONE));
            assertEquals("partial", text(failed(rig.run(), "INVALID_PROVIDER_COMPLETION").partialResult()));
        }
    }

    @Test
    public void invalidJsonAndChangingIdentityDoNotDiscardPreviousText() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("partial", null, "null") + "data: {broken secret response\n\n"));
            assertEquals("partial", text(failed(rig.run(), "INVALID_PROVIDER_RESPONSE").partialResult()));
            rig.reply.set(Reply.sse(chunk("partial", null, "null") + chunk("bad", "stop", "null").replace("chat-1", "chat-2") + DONE));
            failed(rig.run(), "PROVIDER_RESPONSE_IDENTITY_CHANGED");
        }
    }

    @Test
    public void outputAndWireAndFrameLimitsTerminateWithoutRetry() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("a", null, "null") + chunk("你好", "stop", "null") + DONE));
            var failure = failed(rig.gateway.generate(rig.call(Duration.ofSeconds(8), 4)).collectList().block(WAIT), "OUTPUT_LIMIT_EXCEEDED");
            assertEquals("a", text(failure.partialResult()));
            assertEquals(1, rig.requests.get());
        }
        try (var rig = new Rig(256, 65536)) {
            rig.reply.set(Reply.sse(": " + "x".repeat(300) + "\n\n" + success()));
            failed(rig.run(), "RESPONSE_LIMIT_EXCEEDED");
            assertEquals(1, rig.requests.get());
        }
        try (var rig = new Rig(65536, 1024)) {
            rig.reply.set(Reply.sse(chunk("x".repeat(2048), "stop", "null") + DONE));
            failed(rig.run(), "SSE_FRAME_LIMIT_EXCEEDED");
            assertEquals(1, rig.requests.get());
        }
    }

    @Test
    public void rejectionAndServerFailureAreClassifiedWithoutBodyLeakOrRetry() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(new Reply(401, "application/json", "secret-provider-error", "", 0));
            var rejected = failed(rig.run(), "PROVIDER_HTTP_401");
            assertEquals(ExecutionError.Certainty.KNOWN, rejected.error().certainty());
            assertEquals(ExecutionError.SideEffect.NONE, rejected.error().sideEffect());
            assertEquals(Usage.unknown(), rejected.usage());
            assertFalse(rejected.toString().contains("secret-provider-error"));
            rig.reply.set(new Reply(500, "application/json", "secret-provider-error", "", 0));
            var unknown = failed(rig.run(), "PROVIDER_HTTP_500");
            assertEquals(ExecutionError.Certainty.UNKNOWN, unknown.error().certainty());
            assertTrue(unknown.error().retryable());
            assertEquals(2, rig.requests.get());
            assertEquals(2, rig.releases.get());
        }
    }

    @Test
    public void redirectsAreNotFollowedAndJsonCannotMasqueradeAsSse() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(new Reply(302, "text/plain", "redirect", "", 0));
            failed(rig.run(), "PROVIDER_HTTP_302");
            assertEquals(1, rig.requests.get());
            assertEquals("/v1/chat/completions", rig.requestPath.get());
            rig.reply.set(new Reply(200, "application/json", "{}", "", 0));
            failed(rig.run(), "INVALID_PROVIDER_CONTENT_TYPE");
        }
    }

    @Test
    public void cancellationAndAbsoluteDeadlinePreserveReceivedContentAndReleaseLease() throws Exception {
        try (var rig = new Rig()) {
            result(rig.run()); // 预热本地 HTTP；期限断言不依赖首次类加载耗时。
            rig.reply.set(new Reply(200, "text/event-stream", chunk("partial", null, "null"), success(), 1200));
            var cancelled = rig.call();
            var signals = rig.gateway.generate(cancelled).doOnNext(signal -> {
                if (signal instanceof GenerationSignal.Delta(GenerationEvent event) && event instanceof GenerationEvent.TextDelta) {
                    cancelled.runtime().cancellation().cancel("local cancellation");
                }
            }).collectList().block(WAIT);
            assertNotNull(signals);
            assertEquals("partial", text(failed(signals, "INVOCATION_CANCELLED").partialResult()));
            awaitReleases(rig, 2);
        }
        try (var rig = new Rig()) {
            result(rig.run());
            rig.reply.set(new Reply(200, "text/event-stream", chunk("partial", null, "null"), success(), 1200));
            var timed = rig.gateway.generate(rig.call(Duration.ofMillis(500), 4096)).collectList().block(WAIT);
            assertNotNull(timed);
            assertEquals("partial", text(failed(timed, "INVOCATION_TIMED_OUT").partialResult()));
            awaitReleases(rig, 2);
        }
    }

    @Test
    public void downstreamCancellationReleasesLeaseWithoutSettingDurableCancel() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(new Reply(200, "text/event-stream", chunk("partial", null, "null"), success(), 1000));
            var call = rig.call();
            rig.gateway.generate(call).take(1).collectList().block(WAIT);
            awaitReleases(rig, 1);
            assertFalse(call.runtime().cancellation().isCancelled());
            result(rig.run()); // 一个活动连接上限，取消后下一次借用仍可完成。
        }
    }

    @Test
    public void unavailableCredentialsPreventHttpRequestAndReleaseLease() throws Exception {
        try (var rig = new Rig()) {
            rig.token.set(null);
            var signals = rig.run();
            var failure = (GenerationSignal.Failure) signals.getLast();
            assertEquals("CREDENTIAL_NOT_AVAILABLE", failure.error().code());
            assertEquals(ExecutionError.SideEffect.NONE, failure.error().sideEffect());
            assertEquals(0, rig.requests.get());
            assertEquals(1, rig.releases.get());
        }
    }

    @Test
    public void runtimeReleaseIsIdempotentAndInvalidationRevokesExistingHandles() throws Exception {
        try (var rig = new Rig()) {
            var call = rig.call();
            var lease = rig.runtime.acquire(rig.connection, call.runtime()).block(WAIT);
            rig.runtime.invalidate(rig.connection.definition(), call.runtime().execution()).block(WAIT);
            assertThrows(GenerationException.class, lease::handle);
            rig.runtime.release(lease).block(WAIT);
            rig.runtime.release(lease).block(WAIT);
            result(rig.run());
        }
    }

    @Test
    public void invalidUsageAndUnexpectedThinkingAreRejectedRatherThanSilentlyOmitted() throws Exception {
        try (var rig = new Rig()) {
            rig.reply.set(Reply.sse(chunk("partial", "stop", "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":6}") + DONE));
            failed(rig.run(), "INVALID_PROVIDER_USAGE");
            rig.reply.set(Reply.sse(chunk("partial", null, "null")
                    + chunk("ignored", "stop", "null").replace("\"content\":", "\"reasoning_content\":\"unexpected\",\"content\":") + DONE));
            assertEquals("partial", text(failed(rig.run(), "UNSUPPORTED_PROVIDER_OUTPUT").partialResult()));
        }
    }

    @Test
    public void leaseCapacityIsBoundedAndAReleasedHandleCannotBeReused() throws Exception {
        try (var rig = new Rig()) {
            var call = rig.call();
            var first = rig.runtime.acquire(rig.connection, call.runtime()).block(WAIT);
            var second = rig.runtime.acquire(rig.connection, call.runtime()).block(WAIT);
            assertThrows(GenerationException.class, () -> rig.runtime.acquire(rig.connection, call.runtime()).block(WAIT));
            rig.runtime.release(first).block(WAIT);
            assertThrows(GenerationException.class, first::handle);
            rig.runtime.release(second).block(WAIT);
            result(rig.run());
        }
    }

    @Test
    public void rejectedExecutionOptionsFailBeforeNetworkAndDoNotImplyRemoteExecution() throws Exception {
        try (var rig = new Rig()) {
            var valid = rig.call();
            var request = new InvocationRequest<>(valid.request().capability(), valid.request().binding(), valid.request().kind(),
                    valid.request().input(), new ExecutionOptions(valid.request().options().deadline(), 2, 4096, 1, 1, Duration.ofSeconds(8)),
                    valid.request().context());
            var call = new GatewayCall<>(request, valid.binding(), valid.attempt(), valid.runtime());
            var failure = failed(rig.gateway.generate(call).collectList().block(WAIT), "GENERATION_PREFLIGHT_REJECTED");
            assertEquals(ExecutionError.SideEffect.NONE, failure.error().sideEffect());
            assertEquals(0, rig.requests.get());
            assertEquals(0, rig.credentialReads.get());
        }
    }

    @Test
    public void aSingleLeaseCannotTransmitASecondHttpRequest() throws Exception {
        try (var rig = new Rig()) {
            var call = rig.call();
            var provider = new DeepSeekGenerationProviderAdapter(rig.properties.capabilities(), GenerationJson.mapper(65536));
            var protocol = new ChatCompletionsSseProtocolAdapter<DeepSeekWire.Request>(GenerationJson.mapper(65536), rig.generation);
            var lease = rig.runtime.acquire(rig.connection, call.runtime()).block(WAIT);
            try {
                protocol.exchange(provider.mapRequest(call), lease, call).collectList().block(WAIT);
                assertThrows(GenerationException.class, () -> protocol.exchange(provider.mapRequest(call), lease, call).collectList().block(WAIT));
                assertEquals(1, rig.requests.get());
            } finally {
                rig.runtime.release(lease).block(WAIT);
            }
        }
    }
}
