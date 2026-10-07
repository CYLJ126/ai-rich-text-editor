package com.arte.ainew.admission;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.entry.DefaultChatService;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.application.gateway.DefaultModelGateway;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.GenerationJson;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import com.arte.core.enums.ResultCodeEnum;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * H2 MySQL 模式＋真实本地 HTTP／SSE 的完整闭环，不访问当前数据库或真实模型，不读取实际凭据。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public class DispatchIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String USAGE = "{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}";

    private static String chunk(String text, String finish, String usage) {
        return "data: {\"id\":\"response\",\"model\":\"deepseek-actual\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                + text + "\"},\"finish_reason\":" + (finish == null ? "null" : "\"" + finish + "\"")
                + "}],\"usage\":" + usage + "}\n\n";
    }

    private static final class Rig implements AutoCloseable {
        final JdbcDataSource dataSource = new JdbcDataSource();
        final reactor.core.scheduler.Scheduler db = Schedulers.newBoundedElastic(8, 256, "dispatch-db-test");
        final reactor.core.scheduler.Scheduler timer = Schedulers.newSingle("dispatch-timer-test");
        final java.util.concurrent.ExecutorService httpThreads = Executors.newFixedThreadPool(4);
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();
        final List<String> requestBodies = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicBoolean failSettlement = new AtomicBoolean();
        volatile String body = chunk("你好", "stop", USAGE) + "data: [DONE]\n\n";
        volatile String tail = "";
        volatile long pause;
        final JdbcTemplate jdbc;
        final AdmissionFixture fixture;
        final HttpConnectionRuntime transport;
        final DefaultModelGateway<?, ?, ?> gateway;
        final AdmissionAuthorization authorization;
        final NewAiExecutionProperties settings = new NewAiExecutionProperties(true, false, 4, Duration.ofMillis(100),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofDays(1));
        final DefaultInvocationCoordinator coordinator;
        final DefaultChatService chat;
        final DefaultExecutionControl control;
        final DefaultExecutionEventService reading;
        final InvocationDispatchWorker worker;

        Rig() throws Exception {
            this(false);
        }

        Rig(boolean insufficientBudget) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(httpThreads);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    if (pause > 0) {
                        Thread.sleep(pause);
                    }
                    output.write(tail.getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (java.io.IOException cancelled) { /* 测试中的客户端取消可关闭服务端连接。 */ } finally {
                    exchange.close();
                }
            });
            server.start();
            var origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var base = AdmissionFixture.properties();
            var old = base.connections().getFirst();
            var connection = new ConnectionDefinition(old.definition(), "deepseek", ChatCompletionsSseProtocolAdapter.DEFINITION,
                    URI.create(origin + "/v1"), old.credential(), old.state(), old.connectTimeout(), old.responseTimeout(), 65536);
            var binding = base.bindings().getFirst();
            var properties = new NewAiProperties(true, base.dataSourceBean(), base.releaseRef(), base.persistence(), base.limits(),
                    base.grants(), base.capabilities(), List.of(new ResolvedBinding(binding.definition(), binding.capability(),
                    binding.connection(), "deepseek-chat", binding.contextWindowTokens(), binding.rate())), List.of(connection),
                    base.rates(), insufficientBudget ? base.budgets().stream().map(budget ->
                    new NewAiProperties.Budget(budget.budgetRef(), budget.owner(), AdmissionFixture.money("0.000001"), budget.rate())).toList()
                    : base.budgets());
            dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
            jdbc = new JdbcTemplate(dataSource);
            fixture = new AdmissionFixture(dataSource, db, new com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec(), properties);
            var clock = Clock.systemUTC();
            authorization = new AdmissionAuthorization(new FixedExecutionAuthorizationResolver(properties), properties, clock);
            var generation = new NewAiGenerationProperties(true, List.of(origin), List.of(), 2, 4, 4, Duration.ofSeconds(2), 65536, 65536);
            ConnectionCredentialResolver credentials = (secret, context) -> Mono.just(new ConnectionCredentialResolver.BearerCredential("local-test-token"));
            transport = new HttpConnectionRuntime(fixture.catalog::resolveConnection, authorization, credentials, generation, clock);
            gateway = new DefaultModelGateway<>(fixture.catalog, fixture.catalog, fixture.catalog::resolveConnection, transport,
                    new DeepSeekGenerationProviderAdapter(properties.capabilities(), GenerationJson.mapper(65536)),
                    new ChatCompletionsSseProtocolAdapter<>(GenerationJson.mapper(65536), generation), clock);
            coordinator = coordinator(budgetFault());
            chat = new DefaultChatService(authorization, fixture.conversations, fixture.catalog, fixture.contexts, coordinator, clock);
            control = new DefaultExecutionControl(authorization, fixture.executions, coordinator);
            reading = new DefaultExecutionEventService(authorization, fixture.executions, fixture.executions, fixture.payloads);
            worker = new InvocationDispatchWorker(fixture.executions, fixture.executions, coordinator, settings, timer);
            fixture.initializeBudget("alice");
        }

        DefaultInvocationCoordinator coordinator(BudgetService budgets) {
            var dispatcher = new GenerationDispatcher(authorization, fixture.catalog, fixture.executions, fixture.executions,
                    fixture.executions, fixture.payloads, fixture.payloads, budgets, gateway, fixture.properties, settings, Clock.systemUTC());
            return new DefaultInvocationCoordinator(fixture.coordinator, dispatcher);
        }

        BudgetService budgetFault() {
            return new BudgetService() {
                public Mono<StoreOutcome<BudgetReservation>> reserve(BudgetCommands.Reserve value) {
                    return fixture.executions.reserve(value);
                }

                public Mono<StoreOutcome<BudgetReservation>> settle(BudgetCommands.Settle value) {
                    return failSettlement.compareAndSet(true, false) ? Mono.error(new IllegalStateException("injected settlement failure"))
                            : fixture.executions.settle(value);
                }

                public Mono<BudgetCommands.Account> account(ExecutionOwner owner, String ref) {
                    return fixture.executions.account(owner, ref);
                }

                public Mono<BudgetReservation> reservation(ExecutionOwner owner, String ref) {
                    return fixture.executions.reservation(owner, ref);
                }
            };
        }

        Conversation conversation() {
            return fixture.conversations.create("test", null, List.of(), fixture.context("alice", "create")).block(WAIT);
        }

        AcceptedExecution submit(Conversation conversation, String key) {
            var context = fixture.context("alice", key);
            return chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "hello", context), context).block(WAIT);
        }

        Invocation invocation(AcceptedExecution value) {
            return fixture.executions.find(AdmissionFixture.owner("alice-id"), value.executionId()).block(WAIT);
        }

        Attempt attempt(Invocation value) {
            return fixture.executions.findAttempt(AdmissionFixture.owner("alice-id"),
                    value.request().context().executionId(), value.activeAttemptId()).block(WAIT);
        }

        BudgetCommands.Account account() {
            return fixture.executions.account(AdmissionFixture.owner("alice-id"), "alice-budget").block(WAIT);
        }

        void expireOutbox() {
            jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
        }

        void expireAttempt(Attempt value) throws Exception {
            var expired = new Attempt(value.attemptId(), value.invocationId(), value.attemptNumber(), value.workerId(), value.fencingToken(),
                    value.createdAt().plusMillis(1), value.version(), value.state(), value.dispatch(), value.remoteRequestId(),
                    value.budgetReservationId(), value.usage(), value.error(), value.createdAt(), value.updatedAt());
            jdbc.update("UPDATE arte_ai_attempt SET snapshot=? WHERE id_key=?", fixture.codec.encode(expired), CanonicalJson.key(value.attemptId()));
            Thread.sleep(5);
        }

        @Override
        public void close() {
            worker.stop();
            transport.close();
            server.stop(0);
            httpThreads.shutdownNow();
            jdbc.execute("DROP ALL OBJECTS");
            timer.dispose();
            db.dispose();
        }
    }

    @Test
    public void nextRoundUsesOwnedCommittedHistoryAndReplayKeepsOriginalContext() throws Exception {
        try (var rig = new Rig()) {
            var conversation = rig.conversation();
            var first = rig.submit(conversation, "first");
            rig.worker.pollOnce().block(WAIT);
            var current = rig.fixture.context("alice", "second");
            var second = rig.chat.submit(rig.fixture.chatRequest(conversation.conversationId(), 1, "second question", current), current).block(WAIT);
            rig.worker.pollOnce().block(WAIT);
            var payload = GenerationJson.mapper(65536).readTree(rig.requestBodies.get(1)).get("messages");
            assertEquals(3, payload.size());
            assertEquals("user", payload.get(0).get("role").asText());
            assertEquals("hello", payload.get(0).get("content").asText());
            assertEquals("assistant", payload.get(1).get("role").asText());
            assertEquals("你好", payload.get(1).get("content").asText());
            assertEquals("second question", payload.get(2).get("content").asText());
            var snapshot = rig.fixture.contexts.find(rig.invocation(second).contextSnapshotId(), rig.fixture.context("alice", "read")).block(WAIT);
            assertEquals(List.of(rig.invocation(first).conversation().turnId()), snapshot.history().turnIds());
            var turn = rig.fixture.conversations.turn(conversation.conversationId(), rig.invocation(second).conversation().turnId(), current).block(WAIT);
            assertEquals("second question", ((ChatMessage.Text) turn.userMessage().content().getFirst()).text());
            var next = rig.fixture.context("alice", "third");
            rig.chat.submit(rig.fixture.chatRequest(conversation.conversationId(), 2, "third question", next), next).block(WAIT);
            rig.worker.pollOnce().block(WAIT);
            var replayContext = rig.fixture.context("alice", "second");
            assertEquals(second, rig.chat.submit(rig.fixture.chatRequest(conversation.conversationId(), 1, "second question", replayContext), replayContext).block(WAIT));
            var conflict = assertThrows(AdmissionException.class, () -> rig.chat.submit(
                    rig.fixture.chatRequest(conversation.conversationId(), 1, "changed question", replayContext), replayContext).block(WAIT));
            assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, conflict.getResultCode());
            assertEquals(3, rig.requests.get());
            assertEquals(3, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Long.class).longValue());
        }
    }

    @Test
    public void unknownOutcomeKeepsConversationBusyWithoutResubmission() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", null, "null");
            var conversation = rig.conversation();
            var first = rig.submit(conversation, "first");
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, rig.invocation(first).state());
            var current = rig.fixture.context("alice", "second");
            var error = assertThrows(AdmissionException.class, () -> rig.chat.submit(
                    rig.fixture.chatRequest(conversation.conversationId(), 1, "next", current), current).block(WAIT));
            assertEquals(ResultCodeEnum.AI_CONVERSATION_BUSY, error.getResultCode());
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Long.class).longValue());
        }
    }

    @Test
    public void knownFailedPartialOutputIsExcludedFromNextRoundContext() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", "length", USAGE) + "data: [DONE]\n\n";
            var conversation = rig.conversation();
            var first = rig.submit(conversation, "first");
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, rig.invocation(first).state());
            rig.body = chunk("complete", "stop", USAGE) + "data: [DONE]\n\n";
            var current = rig.fixture.context("alice", "second");
            var second = rig.chat.submit(rig.fixture.chatRequest(conversation.conversationId(), 1, "next", current), current).block(WAIT);
            rig.worker.pollOnce().block(WAIT);
            var snapshot = rig.fixture.contexts.find(rig.invocation(second).contextSnapshotId(), rig.fixture.context("alice", "read")).block(WAIT);
            assertNull(snapshot.history());
            assertEquals(1, snapshot.messages().size());
            assertEquals("next", ((ChatMessage.Text) snapshot.messages().getFirst().content().getFirst()).text());
            assertEquals(2, rig.requests.get());
        }
    }

    @Test
    public void historyIsBoundedToTenRecentTurnsAndCapacityOverflowIsExplicit() throws Exception {
        try (var rig = new Rig()) {
            var conversation = rig.conversation();
            AcceptedExecution latest = null;
            for (int i = 0; i < 12; i++) {
                var current = rig.fixture.context("alice", "round-" + i);
                latest = rig.chat.submit(rig.fixture.chatRequest(conversation.conversationId(), i, "question-" + i, current), current).block(WAIT);
                rig.worker.pollOnce().block(WAIT);
            }
            var snapshot = rig.fixture.contexts.find(rig.invocation(latest).contextSnapshotId(), rig.fixture.context("alice", "read")).block(WAIT);
            assertEquals(10, snapshot.history().turnIds().size());
            assertEquals(21, snapshot.messages().size());
            assertEquals("question-1", ((ChatMessage.Text) snapshot.messages().getFirst().content().getFirst()).text());
            var current = rig.fixture.context("alice", "small-capacity");
            var original = rig.fixture.chatRequest(conversation.conversationId(), 12, "next", current);
            var selection = new com.arte.ainew.pojo.context.ContextRequest(original.context().messages(), null, List.of(), List.of(), null,
                    new com.arte.ainew.pojo.context.ContextBudget(1024, 128, 256, 0));
            var limited = new com.arte.ainew.pojo.entry.EntryRequests.Chat(original.conversationId(), original.expectedVersion(), null, null,
                    selection, original.capability(), original.binding(), original.generationOptions(), original.options());
            var error = assertThrows(AdmissionException.class, () -> rig.chat.submit(limited, current).block(WAIT));
            assertEquals(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED, error.getResultCode());
            assertEquals(12, rig.requests.get());
            assertEquals(12, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_turn", Long.class).longValue());
        }
    }

    @Test
    public void chatWorkerAndQueriesProduceDurableResultAndReportedCharge() throws Exception {
        try (var rig = new Rig()) {
            var accepted = rig.submit(rig.conversation(), "submit");
            assertEquals(Invocation.State.ACCEPTED, rig.invocation(accepted).state());
            assertEquals(1, rig.worker.pollOnce().block(WAIT).intValue());
            var completed = rig.control.status(accepted.executionId(), rig.fixture.context("alice", "status")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, completed.state());
            var result = (InvocationResult.Generation) rig.reading.result(accepted.executionId(), rig.fixture.context("alice", "result")).block(WAIT);
            assertEquals("你好", ((ChatMessage.Text) result.value().outputs().getFirst().content().getFirst()).text());
            assertEquals(Usage.Basis.PROVIDER_REPORTED, result.value().usage().basis());
            assertEquals(0, rig.account().held().amount().signum());
            assertEquals(0, rig.account().charged().amount().compareTo(new BigDecimal("0.000007")));
            assertEquals(1, rig.requests.get());
            assertEquals(0, rig.worker.pollOnce().block(WAIT).intValue());
            var page = rig.reading.replay(new ExecutionEvent.Cursor(accepted.executionId(), 0), 256,
                    rig.fixture.context("alice", "events")).block(WAIT).value();
            assertEquals(ExecutionEvent.Kind.BUDGET_CHANGED, page.events().getLast().kind());
            assertTrue(page.events().stream().anyMatch(event -> event.kind() == ExecutionEvent.Kind.TERMINAL));
            assertTrue(page.events().stream().anyMatch(event -> event.kind() == ExecutionEvent.Kind.OUTPUT));
            for (int i = 0; i < page.events().size(); i++) {
                assertEquals(i + 1, page.events().get(i).sequence());
            }
        }
    }

    @Test
    public void repeatedSubmissionAndRedeliveryPreserveIdentityAndCallModelOnce() throws Exception {
        try (var rig = new Rig()) {
            var conversation = rig.conversation();
            var first = rig.submit(conversation, "same-key");
            var repeated = rig.submit(conversation, "same-key");
            assertEquals(first, repeated);
            var message = rig.fixture.executions.claim(OutboxMessage.Kind.DISPATCH, "manual", Duration.ofSeconds(3), 1).block(WAIT).getFirst();
            rig.coordinator.dispatch(message, ExecutionRuntimeContext.start(rig.invocation(first).request().context())).block(WAIT);
            rig.expireOutbox();
            rig.worker.pollOnce().block(WAIT);
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Long.class).longValue());
            assertEquals(first, rig.submit(conversation, "same-key"));
            var stale = assertThrows(AdmissionException.class, () -> rig.coordinator.dispatch(message,
                    ExecutionRuntimeContext.start(rig.invocation(first).request().context())).block(WAIT));
            assertEquals(ResultCodeEnum.AI_LEASE_LOST, stale.getResultCode());
        }
    }

    @Test
    public void interruptedProviderKeepsPartialResultUnknownOutcomeAndHeldBudget() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", null, "null");
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            var invocation = rig.invocation(accepted);
            assertEquals(Invocation.State.UNKNOWN, invocation.state());
            assertEquals(ExecutionError.Certainty.UNKNOWN, invocation.error().certainty());
            assertTrue(invocation.result().partial());
            var result = (InvocationResult.Generation) rig.reading.result(accepted.executionId(), rig.fixture.context("alice", "result")).block(WAIT);
            assertFalse(result.value().complete());
            assertEquals("partial", ((ChatMessage.Text) result.value().outputs().getFirst().content().getFirst()).text());
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, rig.fixture.executions.reservation(
                    AdmissionFixture.owner("alice-id"), rig.attempt(invocation).budgetReservationId()).block(WAIT).state());
            assertTrue(rig.account().held().amount().signum() > 0);
            assertEquals(0, rig.account().charged().amount().signum());
            assertEquals(1, rig.requests.get());
        }
    }

    @Test
    public void successfulOutputWithoutUsageDoesNotInventAFreeCall() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("answer", "stop", "null") + "data: [DONE]\n\n";
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, rig.invocation(accepted).state());
            assertTrue(rig.account().held().amount().signum() > 0);
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, rig.fixture.executions.reservation(
                    AdmissionFixture.owner("alice-id"), rig.attempt(rig.invocation(accepted)).budgetReservationId()).block(WAIT).state());
        }
    }

    @Test
    public void knownTruncatedResponseFailsWithPartialBytesAndKnownCharge() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", "length", USAGE) + "data: [DONE]\n\n";
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            var invocation = rig.invocation(accepted);
            assertEquals(Invocation.State.FAILED, invocation.state());
            assertEquals("MODEL_OUTPUT_INCOMPLETE", invocation.error().code());
            assertTrue(invocation.result().partial());
            assertEquals(0, rig.account().held().amount().signum());
            assertEquals(0, rig.account().charged().amount().compareTo(new BigDecimal("0.000007")));
        }
    }

    @Test
    public void settlementFailureIsRecoveredFromTerminalWithoutAnotherModelRequest() throws Exception {
        try (var rig = new Rig()) {
            rig.failSettlement.set(true);
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, rig.invocation(accepted).state());
            assertTrue(rig.account().held().amount().signum() > 0);
            assertEquals(0, rig.jdbc.queryForObject("SELECT delivered FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
            rig.expireOutbox();
            rig.worker.pollOnce().block(WAIT);
            assertEquals(0, rig.account().held().amount().signum());
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement", Long.class).longValue());
        }
    }

    @Test
    public void queriesReauthorizeAndRejectCrossOwnerOrMissingReadScope() throws Exception {
        try (var rig = new Rig()) {
            var accepted = rig.submit(rig.conversation(), "submit");
            var unavailable = assertThrows(AdmissionException.class, () -> rig.reading.result(accepted.executionId(),
                    rig.fixture.context("alice", "early")).block(WAIT));
            assertEquals(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE, unavailable.getResultCode());
            rig.worker.pollOnce().block(WAIT);
            var denied = assertThrows(AdmissionException.class, () -> rig.reading.result(accepted.executionId(),
                    rig.fixture.context("bob", "other")).block(WAIT));
            assertEquals(ResultCodeEnum.AI_NOT_FOUND, denied.getResultCode());
            assertThrows(AccessDeniedException.class, () -> rig.control.status(accepted.executionId(),
                    rig.fixture.context("alice", "scope", Set.of(AdmissionAuthorization.INVOKE))).block(WAIT));
            assertEquals(1, rig.requests.get());
        }
    }

    private void expiredRecovery(boolean dispatched) throws Exception {
        try (var rig = new Rig()) {
            var accepted = rig.submit(rig.conversation(), "submit");
            var initial = rig.invocation(accepted);
            var owner = AdmissionFixture.owner("alice-id");
            var created = rig.fixture.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                    new ExecutionCommands.Version(owner, accepted.executionId(), initial.version()), "abandoned", "old-worker",
                    Duration.ofSeconds(1))).block(WAIT).value();
            var running = rig.invocation(accepted);
            rig.fixture.executions.reserve(new BudgetCommands.Reserve(ExecutionCommands.Guard.from(owner, running, created),
                    "abandoned-reservation", AdmissionFixture.money("1"), AdmissionFixture.RATE, Duration.ofDays(1))).block(WAIT);
            var attempt = rig.attempt(running);
            if (dispatched) {
                attempt = rig.fixture.executions.markDispatch(new ExecutionCommands.Dispatch(
                        ExecutionCommands.Guard.from(owner, running, attempt), "original-remote-operation")).block(WAIT).value();
            }
            rig.expireAttempt(attempt);
            rig.worker.pollOnce().block(WAIT);
            assertEquals(dispatched ? Invocation.State.UNKNOWN : Invocation.State.INTERRUPTED, rig.invocation(accepted).state());
            assertEquals(0, rig.requests.get());
            assertEquals(dispatched ? BudgetReservation.State.PENDING_RECONCILIATION : BudgetReservation.State.RELEASED,
                    rig.fixture.executions.reservation(owner, "abandoned-reservation").block(WAIT).state());
            assertEquals(dispatched ? 1 : 0, rig.account().held().amount().signum());
            assertTrue(rig.attempt(rig.invocation(accepted)).fencingToken() > attempt.fencingToken());
        }
    }

    @Test
    public void expiredUndispatchedAttemptIsStoppedAndReleased() throws Exception {
        expiredRecovery(false);
    }

    @Test
    public void expiredPossiblySentAttemptIsUnknownAndNeverResent() throws Exception {
        expiredRecovery(true);
    }

    @Test
    public void leaseHeartbeatsCoverSlowModelAndWorkerAcknowledgesAfterCompletion() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", null, "null");
            rig.tail = chunk("done", "stop", USAGE) + "data: [DONE]\n\n";
            rig.pause = 4200;
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, rig.invocation(accepted).state());
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.jdbc.queryForObject("SELECT delivered FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
            assertTrue(rig.attempt(rig.invocation(accepted)).version() >= 4);
        }
    }

    @Test
    public void twoWorkerInstancesClaimTheSameDatabaseWithoutDuplicateModelCalls() throws Exception {
        try (var rig = new Rig()) {
            rig.body = chunk("partial", null, "null");
            rig.tail = chunk("done", "stop", USAGE) + "data: [DONE]\n\n";
            rig.pause = 200;
            var accepted = rig.submit(rig.conversation(), "submit");
            var secondStore = new com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence(rig.dataSource, rig.fixture.codec, rig.db);
            var secondPayloads = new com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence(rig.dataSource, rig.fixture.codec, rig.db);
            var secondDispatcher = new GenerationDispatcher(rig.authorization, rig.fixture.catalog, secondStore, secondStore,
                    secondStore, secondPayloads, secondPayloads, secondStore, rig.gateway, rig.fixture.properties, rig.settings, Clock.systemUTC());
            var secondCoordinator = new DefaultInvocationCoordinator(rig.fixture.coordinator, secondDispatcher);
            var secondWorker = new InvocationDispatchWorker(secondStore, secondStore, secondCoordinator, rig.settings, rig.timer);
            var counts = Mono.zip(rig.worker.pollOnce(), secondWorker.pollOnce()).block(WAIT);
            assertEquals(1, counts.getT1() + counts.getT2());
            assertEquals(Invocation.State.SUCCEEDED, rig.invocation(accepted).state());
            assertEquals(1, rig.requests.get());
            assertEquals(1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Long.class).longValue());
            assertEquals(1, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_settlement", Long.class).longValue());
        }
    }

    @Test
    public void automaticWorkerLoopCompletesAndCanBeStopped() throws Exception {
        try (var rig = new Rig()) {
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.start();
            assertTrue(rig.worker.isRunning());
            Flux.interval(Duration.ofMillis(50)).onBackpressureLatest()
                    .concatMap(ignored -> Mono.fromCallable(() -> rig.jdbc.queryForObject(
                            "SELECT delivered FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class)).subscribeOn(rig.db))
                    .filter(delivered -> delivered == 1).next().block(WAIT);
            rig.worker.stop();
            assertFalse(rig.worker.isRunning());
            assertEquals(Invocation.State.SUCCEEDED, rig.invocation(accepted).state());
            assertEquals(1, rig.requests.get());
        }
    }

    @Test
    public void insufficientBudgetFailsBeforeModelDispatch() throws Exception {
        try (var rig = new Rig(true)) {
            var accepted = rig.submit(rig.conversation(), "submit");
            rig.worker.pollOnce().block(WAIT);
            var failed = rig.invocation(accepted);
            assertEquals(Invocation.State.FAILED, failed.state());
            assertEquals("INSUFFICIENT_BUDGET", failed.error().code());
            assertEquals(Attempt.Dispatch.NOT_STARTED, rig.attempt(failed).dispatch());
            assertEquals(0, rig.requests.get());
            assertEquals(0, rig.account().held().amount().signum());
            assertEquals(0, rig.account().charged().amount().signum());
        }
    }

    @Test
    public void acceptedExecutionThatExpiresInQueueEndsWithoutCreatingAnAttempt() throws Exception {
        try (var rig = new Rig()) {
            var conversation = rig.conversation();
            var context = rig.fixture.context("alice", "short-timeout");
            var original = rig.fixture.chatRequest(conversation.conversationId(), 0, "hello", context);
            var shortOptions = new ExecutionOptions(java.time.Instant.now().plusSeconds(1), 1, 4096, 0, 0, Duration.ofSeconds(1));
            var request = new com.arte.ainew.pojo.entry.EntryRequests.Chat(original.conversationId(), original.expectedVersion(),
                    null, null, original.context(), original.capability(), original.binding(), original.generationOptions(), shortOptions);
            var accepted = rig.chat.submit(request, context).block(WAIT);
            Thread.sleep(1100);
            rig.worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, rig.invocation(accepted).state());
            assertNull(rig.invocation(accepted).activeAttemptId());
            assertEquals(0, rig.requests.get());
            assertEquals(0, rig.account().held().amount().signum());
            assertEquals(1, rig.jdbc.queryForObject("SELECT delivered FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
        }
    }
}
