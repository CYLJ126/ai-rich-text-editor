package com.arte.app.ainew;

import com.arte.ai.api.control.*;
import com.arte.ai.api.execution.*;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.budget.*;
import com.arte.ai.model.capability.*;
import com.arte.ai.model.definition.*;
import com.arte.ai.model.execution.*;
import com.arte.ai.model.generation.*;
import com.arte.ai.model.message.*;
import com.arte.ai.spi.adapter.ConnectionRuntime;
import com.arte.base.admission.LocalAdmissionController;
import com.arte.base.api.security.EgressPolicy;
import com.arte.base.exception.BaseException;
import com.arte.base.execution.BoundedTaskExecutor;
import com.arte.base.model.admission.*;
import com.arte.base.model.execution.*;
import com.arte.base.model.identity.*;
import com.arte.base.model.security.*;
import com.arte.base.spi.observability.AuditSink;
import com.arte.app.execution.support.JdbcAuditSink;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class MinimumModelCallTest {
    final ExecutionScope scope = new ExecutionScope("tenant", "workspace", new PrincipalRef("1", PrincipalType.USER));
    final BudgetQuote quote = new BudgetQuote(new BigDecimal("1"), "USD");
    JdbcDataSource datasource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    JdbcModelExecutionStore store;
    BoundedTaskExecutor tasks;
    LocalAdmissionController admission;
    ConfiguredModelDefinitions definitions;
    HttpServer server;
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean allowed = new AtomicBoolean(true);
    AtomicReference<String> requestBody = new AtomicReference<>();
    static final String RESPONSE = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";

    @BeforeEach
    void setup() throws Exception {
        datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        jdbc = new JdbcTemplate(datasource);
        manager = new DataSourceTransactionManager(datasource);
        for (String file : List.of("arte-ai-new-model-ddl-mysql.sql", "arte-execution-support-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", file)).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin", "");
            try (var connection = datasource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        jdbc.update("INSERT INTO arte_ai_new_budget(scope_key,amount_limit,currency,enabled) VALUES (?,10,'USD',TRUE)", ModelKeys.scope(scope));
        store = new JdbcModelExecutionStore(jdbc, manager, Clock.systemUTC());
        tasks = new BoundedTaskExecutor(1, 1, Clock.systemUTC());
        admission = new LocalAdmissionController(1, 0, Map.of(new AdmissionKey("tenant", "ai.interactive", null, null), new AdmissionLimits(1, 100, Duration.ofMinutes(1))), Clock.systemUTC());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        var capability = new CapabilityDefinition(new CapabilityDescriptor(new DefinitionRef("ai-capability", "default-model", "v1"), CapabilityKind.MODEL, null, null, Set.of("text"), SideEffectKind.EXTERNAL_EFFECT), DefinitionStatus.PUBLISHED);
        var connection = new ConnectionDefinition(new DefinitionRef("ai-connection", "default-model", "v1"), "compatible-chat", "chat-completions", URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions"), new SecretRef("TEST_KEY", null), DefinitionStatus.PUBLISHED);
        definitions = new ConfiguredModelDefinitions(capability, connection, new DefinitionRef("ai-binding", "default-model", "v1"), "tenant", "workspace");
    }

    @AfterEach
    void cleanup() {
        if (tasks != null) tasks.close();
        if (admission != null) admission.close();
        if (server != null) server.stop(0);
    }

    ConnectionRuntime runtime() {
        return new PinnedHttpConnectionRuntime(Set.of("localhost"), ref -> "test-key".toCharArray(), 65536, true);
    }

    InvocationCoordinator coordinator(ConnectionRuntime runtime, AuditSink audit) {
        var provider = new CompatibleChatProviderAdapter(runtime, "test-model", new BigDecimal("0.000001"), new BigDecimal("0.000002"), quote, 16384, 100);
        EgressPolicy egress = request -> new EgressDecision(request, PolicyDecision.allow(Map.of("test", "v1"), Instant.now(), Instant.now().plusSeconds(5)));
        return new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(definitions), new ConnectionManager(definitions), new BindingManager(definitions)),
                new DefaultModelGateway(List.of(provider)), (context, plan) -> {
            if (!allowed.get())
                throw com.arte.base.execution.ExecutionFailures.beforeStart(com.arte.base.model.error.CommonErrorCode.UNAUTHORIZED, context, "access");
        },
                egress, admission, tasks, store, store, new BudgetService(quote, store), audit, Clock.systemUTC());
    }

    InvocationCoordinator coordinator(ConnectionRuntime runtime) {
        return coordinator(runtime, new JdbcAuditSink(jdbc, manager, Clock.systemUTC()));
    }

    InvocationRequest<GenerationRequest> request(String text) {
        return new InvocationRequest<>(definitions.capabilityRef(), definitions.bindingRef(), new GenerationRequest(List.of(new Message(MessageRole.USER, List.of(new TextPart(text)))), new ModelOptions(0.5, 50), List.of(), null),
                new ExecutionOptions(Duration.ofSeconds(5), false), new ExecutionContext(scope, UUID.randomUUID().toString(), null, Instant.now().plusSeconds(30), null, Set.of(), null, null, null));
    }

    ModelExecution await(InvocationCoordinator coordinator, String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            var state = store.find(scope, id).orElseThrow();
            if (state.status() != ExecutionStatus.ACCEPTED && state.status() != ExecutionStatus.RUNNING) return state;
            Thread.sleep(5);
        }
        fail("execution did not complete");
        return null;
    }

    @Test
    void realProtocolCallPersistsResultEventsUsageAndSurvivesStoreReconstruction() throws Exception {
        var coordinator = coordinator(runtime());
        var accepted = coordinator.submitModel(request("question"), null, "key");
        var state = await(coordinator, accepted.executionId());
        assertEquals(ExecutionStatus.SUCCEEDED, state.status());
        assertEquals("hello", ((TextPart) state.result().output().getFirst()).text());
        assertEquals(new BigDecimal("0.00001400"), state.result().usage().reportedCost());
        assertEquals(BudgetStatus.SETTLED, store.reservation(scope, state.executionId()).status());
        assertEquals(3, store.read(scope, state.executionId(), -1, 100).size());
        assertEquals(1, store.read(scope, state.executionId(), 1, 100).size());
        assertEquals(1, calls.get());
        assertTrue(requestBody.get().contains("test-model"));
        assertFalse(requestBody.get().contains("test-key"));
        assertEquals(state, new JdbcModelExecutionStore(jdbc, manager, Clock.systemUTC()).find(scope, state.executionId()).orElseThrow());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_execution_audit", Integer.class));
    }

    @Test
    void duplicateDuringRunningReturnsOriginalWithoutAnotherCallOrBudgetReservation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var coordinator = coordinator((connection, body, checkpoint) -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return RESPONSE.getBytes(StandardCharsets.UTF_8);
        });
        var first = coordinator.submitModel(request("same"), null, "key");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            var second = coordinator.submitModel(request("same"), null, "key");
            assertEquals(first, second);
            assertThrows(BaseException.class, () -> coordinator.submitModel(request("different"), null, "key"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
            assertEquals(0, new BigDecimal("1").compareTo(jdbc.queryForObject("SELECT reserved_amount FROM arte_ai_new_budget", BigDecimal.class)));
        } finally {
            release.countDown();
        }
        assertEquals(ExecutionStatus.SUCCEEDED, await(coordinator, first.executionId()).status());
    }

    @Test
    void insufficientOrMissingBudgetAndRevokedAccessNeverDispatch() {
        var coordinator = coordinator(runtime());
        jdbc.update("UPDATE arte_ai_new_budget SET amount_limit=0.5");
        assertThrows(BaseException.class, () -> coordinator.submitModel(request("question"), null, "budget"));
        jdbc.update("DELETE FROM arte_ai_new_budget");
        assertThrows(BaseException.class, () -> coordinator.submitModel(request("question"), null, "missing"));
        allowed.set(false);
        assertThrows(BaseException.class, () -> coordinator.submitModel(request("question"), null, "revoked"));
        assertEquals(0, calls.get());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void auditFailureBeforeDispatchReleasesReservation() throws Exception {
        var coordinator = coordinator(runtime(), record -> {
            throw new IllegalStateException("unavailable");
        });
        var accepted = coordinator.submitModel(request("question"), null, "key");
        var state = await(coordinator, accepted.executionId());
        assertEquals(ExecutionStatus.FAILED, state.status());
        assertFalse(state.dispatched());
        assertEquals(SideEffectStatus.NONE, state.error().sideEffectStatus());
        assertEquals(BudgetStatus.RELEASED, store.reservation(scope, state.executionId()).status());
        assertEquals(0, calls.get());
    }

    @Test
    void transportFailureAfterDispatchIsUnknownAndKeepsBudget() throws Exception {
        var coordinator = coordinator((connection, body, checkpoint) -> {
            throw new java.io.IOException("secret remote details");
        });
        var accepted = coordinator.submitModel(request("question"), null, "key");
        var state = await(coordinator, accepted.executionId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, state.status());
        assertFalse(state.error().retryable());
        assertEquals(ResultCertainty.UNKNOWN, state.error().resultCertainty());
        assertEquals(BudgetStatus.PENDING_RECONCILIATION, store.reservation(scope, state.executionId()).status());
        assertFalse(ModelJson.error(state.error()).contains("secret remote details"));
        assertEquals(accepted, coordinator.submitModel(request("question"), null, "key"));
    }

    @Test
    void missingUsageRemainsUnknownInsteadOfFreeCall() throws Exception {
        var coordinator = coordinator((connection, body, checkpoint) -> "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8));
        var accepted = coordinator.submitModel(request("question"), null, "key");
        var state = await(coordinator, accepted.executionId());
        assertEquals(ExecutionStatus.SUCCEEDED, state.status());
        assertNull(state.result().usage().reportedCost());
        assertEquals(BudgetStatus.PENDING_RECONCILIATION, store.reservation(scope, state.executionId()).status());
    }

    @Test
    void runningCancellationHoldsAdmissionUntilProtocolWorkExits() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var coordinator = coordinator((connection, body, checkpoint) -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return RESPONSE.getBytes(StandardCharsets.UTF_8);
        });
        var first = coordinator.submitModel(request("first"), null, "key");
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            assertEquals(CancellationStatus.CANCELLING, coordinator.cancel(request("viewer").context(), first.executionId()));
            assertThrows(BaseException.class, () -> coordinator.submitModel(request("second"), null, "second"));
            assertEquals(ExecutionStatus.RUNNING, store.find(scope, first.executionId()).orElseThrow().status());
        } finally {
            release.countDown();
        }
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, await(coordinator, first.executionId()).status());
    }

    @Test
    void scopeIsolationAndReplayDoNotReexecuteProvider() throws Exception {
        var coordinator = coordinator(runtime());
        var accepted = coordinator.submitModel(request("question"), null, "key");
        await(coordinator, accepted.executionId());
        var foreign = new ExecutionScope("tenant", "other", scope.principal());
        assertTrue(store.find(foreign, accepted.executionId()).isEmpty());
        assertThrows(BaseException.class, () -> store.read(foreign, accepted.executionId(), -1, 10));
        assertEquals(3, coordinator.events(request("viewer").context(), accepted.executionId(), -1, 10).size());
        assertEquals(1, calls.get());
        allowed.set(false);
        assertThrows(BaseException.class, () -> coordinator.find(request("viewer").context(), accepted.executionId()));
    }

    @Test
    void outerRollbackCannotUndoReliableAcceptanceAndAtomicTerminalRollsBackOnEventFailure() {
        var req = request("question");
        var coordinator = coordinator(runtime());
        var prepared = coordinator.prepare(req);
        String id = UUID.randomUUID().toString();
        var submission = new ModelSubmission(id, UUID.randomUUID().toString(), prepared.plan(), req.context(), "key", InvocationCoordinator.fingerprint(prepared, req.options()));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            store.accept(submission, quote);
            tx.setRollbackOnly();
        });
        assertTrue(store.find(scope, id).isPresent());
        assertTrue(store.start(scope, id));
        store.markDispatched(scope, id);
        jdbc.update("INSERT INTO arte_ai_new_event VALUES (?, ?, 2, 'FAILED', CURRENT_TIMESTAMP, NULL, NULL)", id, submission.attemptId());
        assertThrows(RuntimeException.class, () -> store.finish(scope, id, ExecutionStatus.SUCCEEDED, new ModelResult(List.of(new TextPart("result")), List.of(), null, new Usage(1L, 1L, new BigDecimal("0.1"), "USD")), null));
        assertEquals(ExecutionStatus.RUNNING, store.find(scope, id).orElseThrow().status());
        assertEquals(BudgetStatus.RESERVED, store.reservation(scope, id).status());
        assertEquals(0, jdbc.queryForObject("SELECT spent_amount FROM arte_ai_new_budget", BigDecimal.class).signum());
    }

    @Test
    void simultaneousAcceptanceCannotOverspendOrReserveTheSameKeyTwice() throws Exception {
        var req = request("question");
        var prepared = coordinator(runtime()).prepare(req);
        jdbc.update("UPDATE arte_ai_new_budget SET amount_limit=1");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var results = new ArrayList<Future<ExecutionStoreResult>>();
            for (int i = 0; i < 2; i++)
                results.add(workers.submit(() -> {
                    barrier.await();
                    var acceptance = store.accept(new ModelSubmission(UUID.randomUUID().toString(), UUID.randomUUID().toString(), prepared.plan(), req.context(), "same", InvocationCoordinator.fingerprint(prepared, req.options())), quote);
                    return new ExecutionStoreResult(acceptance.execution().executionId(), acceptance.created());
                }));
            var first = results.get(0).get(3, TimeUnit.SECONDS);
            var second = results.get(1).get(3, TimeUnit.SECONDS);
            assertEquals(first.id, second.id);
            assertNotEquals(first.created, second.created);
            assertEquals(0, BigDecimal.ONE.compareTo(jdbc.queryForObject("SELECT reserved_amount FROM arte_ai_new_budget", BigDecimal.class)));
            assertThrows(BaseException.class, () -> store.accept(new ModelSubmission(UUID.randomUUID().toString(), UUID.randomUUID().toString(), prepared.plan(), req.context(), "other", InvocationCoordinator.fingerprint(prepared, req.options())), quote));
        }
    }

    private record ExecutionStoreResult(String id, boolean created) {
    }

    @Test
    void unsupportedInputsAndProductionLoopbackAreRejected() throws Exception {
        var coordinator = coordinator(runtime());
        var req = request("question");
        assertThrows(BaseException.class, () -> coordinator.prepare(new InvocationRequest<>(req.capabilityRef(), req.bindingRef(), req.input(), new ExecutionOptions(Duration.ofSeconds(5), true), req.context())));
        var invalid = new GenerationRequest(List.of(new Message(MessageRole.TOOL, List.of(new TextPart("tool")))), req.input().options(), List.of(), null);
        assertThrows(BaseException.class, () -> coordinator.prepare(new InvocationRequest<>(req.capabilityRef(), req.bindingRef(), invalid, req.options(), req.context())));
        var production = new PinnedHttpConnectionRuntime(Set.of("localhost"), ref -> "secret".toCharArray(), 1000);
        var prepared = coordinator(production).prepare(req);
        var task = tasks.submit(req.context(), prepared.operation()::invoke);
        assertThrows(ExecutionException.class, () -> task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
        assertEquals(0, calls.get());
    }

    @Test
    void abandonedAcceptedAndSentAttemptsRequireExplicitRecoveryWithoutResending() {
        var req = request("question");
        var coordinator = coordinator(runtime());
        var prepared = coordinator.prepare(req);
        for (boolean sent : List.of(false, true)) {
            String id = UUID.randomUUID().toString();
            store.accept(new ModelSubmission(id, UUID.randomUUID().toString(), prepared.plan(), req.context(), "recovery-" + sent, InvocationCoordinator.fingerprint(prepared, req.options())), quote);
            if (sent) {
                store.start(scope, id);
                store.markDispatched(scope, id);
            }
            store.interruptAbandoned(scope, id);
            assertEquals(sent ? ExecutionStatus.OUTCOME_UNKNOWN : ExecutionStatus.INTERRUPTED, store.find(scope, id).orElseThrow().status());
            assertEquals(sent ? BudgetStatus.PENDING_RECONCILIATION : BudgetStatus.RELEASED, store.reservation(scope, id).status());
            store.interruptAbandoned(scope, id);
        }
        assertEquals(0, calls.get());
    }

    @Test
    void responseRedirectAndOverlargeBodyNeverGetFollowedOrPublished() throws Exception {
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        var coordinator = coordinator(runtime());
        var first = coordinator.submitModel(request("question"), null, "key");
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, await(coordinator, first.executionId()).status());
        assertEquals(1, calls.get());
        server.removeContext("/v1/chat/completions");
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 70000);
            exchange.close();
        });
        // 等待执行资源完成清理，再申请新调用。
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        AcceptedExecution second = null;
        while (second == null && System.nanoTime() < end) {
            try {
                second = coordinator.submitModel(request("next"), null, "second");
            } catch (BaseException busy) {
                Thread.sleep(5);
            }
        }
        assertNotNull(second);
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, await(coordinator, second.executionId()).status());
        assertEquals(2, calls.get());
    }
}
