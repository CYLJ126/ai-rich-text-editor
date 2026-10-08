package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.budget.ManualReconciliationService;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.budget.*;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static org.junit.Assert.*;

/**
 * 真实事务、持久化输出及双实例竞争；无供应商请求。
 */
public class RegenerationReconciliationIntegrationTest {
    private final ExecutionOwner owner = AdmissionFixture.owner("alice-id");
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture first, second;

    @Before
    public void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        scheduler = Schedulers.newBoundedElastic(12, 256, "regen-reconcile-test");
        jdbc = new JdbcTemplate(dataSource);
        first = new AdmissionFixture(dataSource, scheduler);
        second = new AdmissionFixture(dataSource, scheduler);
        first.initializeBudget("alice");
    }

    @After
    public void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
        scheduler.dispose();
    }

    private Conversation create() {
        return first.conversations.create("test", null, List.of(), first.context("alice", UUID.randomUUID().toString())).block();
    }

    private Invocation find(String id) {
        return first.executions.find(owner, id).block();
    }

    private Conversation conversation(String id) {
        return first.conversations.find(id, first.context("alice", "read")).block();
    }

    private String submit(String conversationId, String text) {
        var context = first.context("alice", UUID.randomUUID().toString());
        return first.chat.submit(first.chatRequest(conversationId, conversation(conversationId).version(), text, context), context).block().executionId();
    }

    private ExecutionCommands.Guard guard(String id) {
        var invocation = find(id);
        return ExecutionCommands.Guard.from(owner, invocation, first.executions.findAttempt(owner, id, invocation.activeAttemptId()).block());
    }

    private BudgetReservation start(String id) {
        var invocation = find(id);
        var attempt = first.executions.createAttempt(new ExecutionCommands.CreateAttempt(new ExecutionCommands.Version(owner, id, invocation.version()),
                "attempt-" + id, "worker", Duration.ofMinutes(1))).block().value();
        var reserved = first.executions.reserve(new BudgetCommands.Reserve(guard(id), "reservation-" + id,
                AdmissionFixture.money("1"), AdmissionFixture.RATE, Duration.ofDays(1))).block().value();
        assertNotNull(reserved);
        assertTrue(first.executions.markDispatch(new ExecutionCommands.Dispatch(guard(id), "remote-" + id)).block().successful());
        return reserved;
    }

    private ResultRef complete(String id, String text, boolean unknown, boolean stopped) {
        if (stopped)
            assertTrue(first.executions.requestControl(owner, new ExecutionControlRequest("cancel-" + id, id, "cancel-key-" + id, ControlReceipt.Command.CANCEL)).block().successful());
        var output = new ChatMessage("output-" + id, ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text(text)), List.of(), null);
        var result = new InvocationResult.Generation(new ModelResult("model-" + id,
                new ModelResult.ModelIdentity("test-provider", "test-model", null), List.of(output),
                unknown ? ModelResult.FinishReason.OTHER : ModelResult.FinishReason.STOP, !unknown, null, Usage.unknown(), List.of()));
        var ref = first.payloads.put(owner, id, find(id).activeAttemptId(), "result", result).block().value();
        var error = unknown ? new ExecutionError(stopped ? "INVOCATION_CANCELLED" : "CONNECTION_LOST", ExecutionError.Phase.INVOCATION,
                false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace") : null;
        assertTrue(first.executions.commitCompletion(new ExecutionCommands.Complete(guard(id), "complete",
                new ExecutionPayload.Terminal(unknown ? Invocation.State.UNKNOWN : Invocation.State.SUCCEEDED, ref, error), Usage.unknown(), null)).block().successful());
        return ref;
    }

    private BudgetReservation pending(BudgetReservation original) {
        return first.executions.settle(new BudgetCommands.Settle(owner, original.version(),
                new BudgetSettlement("pending", original.reservationId(), BudgetSettlement.State.PENDING_RECONCILIATION,
                        Usage.unknown(), null, Instant.now()), BudgetCommands.Evidence.UNKNOWN_COST, null)).block().value();
    }

    private Reconciliation.Confirm command(String id, BudgetReservation reservation, String key, String charge) {
        var context = first.context("alice", key, Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN));
        return new Reconciliation.Confirm(owner, context.authorization().principal(), context.traceId(), key, "alice-budget", id,
                find(id).version(), reservation.reservationId(), reservation.version(), AdmissionFixture.money(charge), "bill-123", "Provider bill verified; execution ended", true);
    }

    private ManualReconciliationService service(AdmissionFixture fixture) {
        var auth = new AdmissionAuthorization(new FixedExecutionAuthorizationResolver(fixture.properties), fixture.properties, Clock.systemUTC());
        return new ManualReconciliationService(auth, new BudgetAccountQueryService(fixture.catalog, auth, fixture.executions), fixture.executions);
    }

    private com.arte.ainew.common.execution.AcceptedExecution regenerate(String id, long version, String key) {
        var context = second.context("alice", key);
        return second.chat.regenerate(new EntryRequests.Regenerate(id, version,
                second.chatRequest("unused", 0, "unused", context).options()), context).block();
    }

    private BudgetCommands.Account account() {
        return first.executions.account(owner, "alice-budget").block();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static void code(ResultCodeEnum code, Runnable action) {
        assertEquals(code, assertThrows(AdmissionException.class, action::run).getResultCode());
    }

    @Test
    public void stoppedPartialCostClosesAtomicallyWithoutReleasingANewerConversationGate() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "hello");
        var reservation = start(id);
        var oldGuard = guard(id);
        var ref = complete(id, "partial", true, true);
        reservation = pending(reservation);
        assertEquals(1, service(first).pending("alice-budget", 1, 10, first.context("alice", "read")).block().page().total());
        var next = submit(conversation.conversationId(), "new question");
        var confirm = command(id, reservation, "verify", "0.123456789012345678");
        var receipt = first.executions.confirmReconciliation(confirm).block();
        assertEquals(APPLIED, receipt.code());
        assertEquals(REPLAYED, second.executions.confirmReconciliation(confirm).block().code());
        assertEquals(receipt.value(), second.executions.reconciliationReceipt(owner, id, "verify").block());
        assertEquals(Invocation.State.CANCELLED, find(id).state());
        assertEquals(ref, find(id).result());
        assertEquals(ExecutionError.Certainty.KNOWN, find(id).error().certainty());
        assertEquals(AdmissionFixture.money("0"), account().held());
        assertEquals(confirm.charge(), account().charged());
        assertEquals(BudgetReservation.State.SETTLED, first.executions.reservation(owner, reservation.reservationId()).block().state());
        assertEquals(0, first.executions.pendingReconciliations(owner, "alice-budget", 1, 10).block().total());
        code(ResultCodeEnum.AI_CONVERSATION_BUSY, () -> submit(conversation.conversationId(), "must remain busy"));
        assertEquals(Invocation.State.ACCEPTED, find(next).state());
        assertFalse(first.executions.commitCompletion(new ExecutionCommands.Complete(oldGuard, "stale-worker",
                new ExecutionPayload.Terminal(Invocation.State.UNKNOWN, null, new ExecutionError("STALE", ExecutionError.Phase.INVOCATION,
                        false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace")), Usage.unknown(), null)).block().successful());
    }

    @Test
    public void ordinaryUnknownCanContinueOnlyAfterBillAndExecutionAreConfirmed() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var reservation = start(id);
        complete(id, "partial", true, false);
        reservation = pending(reservation);
        code(ResultCodeEnum.AI_CONVERSATION_BUSY, () -> submit(conversation.conversationId(), "blocked"));
        var confirm = command(id, reservation, "verify", "0");
        assertEquals(APPLIED, first.executions.confirmReconciliation(confirm).block().code());
        assertEquals(Invocation.State.FAILED, find(id).state());
        assertTrue(find(id).result().partial());
        assertEquals(AdmissionFixture.money("0"), account().charged());
        assertEquals(AdmissionFixture.money("0"), account().held());
        var next = submit(conversation.conversationId(), "can continue");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertEquals(1, snapshot.messages().size());
    }

    @Test
    public void finalChargeCannotBeOverwrittenOrAppliedTwiceAcrossInstances() {
        var id = submit(create().conversationId(), "question");
        var reservation = start(id);
        complete(id, "answer", false, false);
        reservation = pending(reservation);
        var confirm = command(id, reservation, "verify", "101.01");
        var results = Flux.range(0, 16).flatMap(i -> (i % 2 == 0 ? first : second).executions.confirmReconciliation(confirm), 8).collectList().block();
        assertEquals(1, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(15, results.stream().filter(r -> r.code() == REPLAYED).count());
        assertEquals(AdmissionFixture.money("101.01"), account().charged());
        assertTrue(account().available().signum() < 0);
        assertEquals(Invocation.State.SUCCEEDED, find(id).state());
        assertFalse(find(id).result().partial());
        var conflicting = command(id, reservation, "verify", "2");
        assertEquals(IDEMPOTENCY_CONFLICT, second.executions.confirmReconciliation(conflicting).block().code());
        assertEquals(INVALID_STATE, first.executions.confirmReconciliation(command(id,
                first.executions.reservation(owner, reservation.reservationId()).block(), "other-key", "2")).block().code());
        assertEquals(2, count("arte_ai_settlement")); // pending + one final bill
    }

    @Test
    public void privilegeOwnershipVersionsCurrencyAndActiveStateAreCheckedBeforeWriting() {
        var id = submit(create().conversationId(), "question");
        var reservation = start(id);
        var active = command(id, reservation, "active", "1");
        assertEquals(INVALID_STATE, first.executions.confirmReconciliation(active).block().code());
        complete(id, "partial", true, true);
        reservation = pending(reservation);
        var currentReservation = reservation;
        assertThrows(AccessDeniedException.class, () -> service(first).confirm("alice-budget", id, find(id).version(),
                currentReservation.reservationId(), currentReservation.version(), AdmissionFixture.money("1"), "bill", "note", true, first.context("alice", "no-admin")).block());
        code(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE, () -> service(second).confirm("alice-budget", id, find(id).version(),
                currentReservation.reservationId(), currentReservation.version(), AdmissionFixture.money("1"), "bill", "note", true,
                second.context("bob", "foreign", Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN))).block());
        var valid = command(id, reservation, "verify", "1");
        var stale = new Reconciliation.Confirm(valid.owner(), valid.reviewer(), valid.traceId(), "stale", valid.budgetRef(), id,
                valid.invocationVersion() - 1, valid.reservationId(), valid.reservationVersion(), valid.charge(), valid.evidenceRef(), valid.note(), true);
        assertEquals(VERSION_CONFLICT, first.executions.confirmReconciliation(stale).block().code());
        var currency = new Reconciliation.Confirm(valid.owner(), valid.reviewer(), valid.traceId(), "currency", valid.budgetRef(), id,
                valid.invocationVersion(), valid.reservationId(), valid.reservationVersion(), new Money(java.math.BigDecimal.ONE, Currency.getInstance("USD")), "bill", "note", true);
        assertEquals(CURRENCY_MISMATCH, second.executions.confirmReconciliation(currency).block().code());
        var wrongBudget = new Reconciliation.Confirm(valid.owner(), valid.reviewer(), valid.traceId(), "wrong-budget", "bob-budget", id,
                valid.invocationVersion(), valid.reservationId(), valid.reservationVersion(), valid.charge(), "bill", "note", true);
        assertEquals(OWNER_MISMATCH, second.executions.confirmReconciliation(wrongBudget).block().code());
        assertEquals(AdmissionFixture.money("1"), account().held());
        assertEquals(AdmissionFixture.money("0"), account().charged());
        assertEquals(Invocation.State.UNKNOWN, find(id).state());
    }

    @Test
    public void auditFailureRollsBackChargeReservationTerminalAndEvents() {
        var id = submit(create().conversationId(), "question");
        var reservation = start(id);
        complete(id, "partial", true, false);
        reservation = pending(reservation);
        var before = account();
        var invocation = find(id);
        long events = count("arte_ai_event"), outbox = count("arte_ai_outbox");
        var faultyCodec = new ExecutionRecordCodec() {
            public String encode(Object value) {
                if (value instanceof Reconciliation.Receipt) throw new IllegalStateException("Injected audit failure");
                return first.codec.encode(value);
            }

            public <T> T decode(String encoded, Class<T> type) {
                return first.codec.decode(encoded, type);
            }
        };
        var faulty = new MybatisExecutionPersistence(dataSource, faultyCodec, scheduler);
        var confirm = command(id, reservation, "verify", "0.5");
        assertThrows(IllegalStateException.class, () -> faulty.confirmReconciliation(confirm).block());
        assertEquals(before, account());
        assertEquals(invocation, find(id));
        assertEquals(reservation, first.executions.reservation(owner, reservation.reservationId()).block());
        assertEquals(events, count("arte_ai_event"));
        assertEquals(outbox, count("arte_ai_outbox"));
        assertNull(first.executions.reconciliationReceipt(owner, id, "verify").block());
    }

    @Test
    public void regenerationReusesOriginalInputPreservesCandidatesAndUsesNewFullAnswerInNextTurn() {
        var conversation = create();
        var firstId = submit(conversation.conversationId(), "first");
        start(firstId);
        complete(firstId, "first answer", false, false);
        var id = submit(conversation.conversationId(), "second");
        start(id);
        complete(id, "old answer", false, false);
        var original = find(id);
        long version = conversation(conversation.conversationId()).version();
        var regenerated = regenerate(id, version, "regenerate");
        var replacement = find(regenerated.executionId());
        assertNotEquals(id, regenerated.executionId());
        assertEquals(id, replacement.replacesInvocationId());
        assertEquals(original.request().input(), replacement.request().input());
        assertNotEquals(original.contextSnapshotId(), replacement.contextSnapshotId());
        assertEquals(original, find(id));
        assertEquals(2, count("arte_ai_turn"));
        assertEquals(3, count("arte_ai_invocation"));
        assertEquals(regenerated, regenerate(id, version, "regenerate"));
        var turn = first.executions.findTurn(owner, conversation.conversationId(), original.conversation().turnId()).block();
        assertEquals(List.of(id, regenerated.executionId()), turn.invocationIds());
        assertEquals(id, turn.selectedInvocationId());
        code(ResultCodeEnum.AI_CONVERSATION_BUSY, () -> submit(conversation.conversationId(), "blocked"));
        start(regenerated.executionId());
        complete(regenerated.executionId(), "new answer", false, false);
        assertEquals(regenerated.executionId(), first.executions.findTurn(owner, conversation.conversationId(), turn.turnId()).block().selectedInvocationId());
        var next = submit(conversation.conversationId(), "third");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertEquals(5, snapshot.messages().size());
        assertEquals("new answer", ((ChatMessage.Text) snapshot.messages().get(3).content().getFirst()).text());
        assertEquals(3, first.executions.findTurn(owner, conversation.conversationId(), find(next).conversation().turnId()).block().sequence());
        assertEquals(regenerated, regenerate(id, version, "regenerate")); // replay even after a later question
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> regenerate(id, version + 1, "regenerate"));
        // Finish latest question to isolate latest-turn restriction from the busy gate.
        start(next);
        complete(next, "third answer", false, false);
        code(ResultCodeEnum.AI_INVALID_STATE, () -> regenerate(id, conversation(conversation.conversationId()).version(), "old-turn"));
        var foreign = second.context("bob", "foreign");
        code(ResultCodeEnum.AI_NOT_FOUND, () -> second.chat.regenerate(new EntryRequests.Regenerate(id, version,
                second.chatRequest("unused", 0, "unused", foreign).options()), foreign).block());
    }

    @Test
    public void stoppingRegenerationKeepsPriorFullReplyAndItsChargesSeparate() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var originalReservation = start(id);
        complete(id, "old answer", false, false);
        var regenerated = regenerate(id, 1, "regenerate").executionId();
        var newReservation = start(regenerated);
        complete(regenerated, "new partial", true, true);
        newReservation = pending(newReservation);
        assertEquals(id, first.executions.findTurn(owner, conversation.conversationId(), find(id).conversation().turnId()).block().selectedInvocationId());
        var next = submit(conversation.conversationId(), "next");
        assertEquals("old answer", ((ChatMessage.Text) first.payloads.find(owner, find(next).contextSnapshotId()).block().messages().get(1).content().getFirst()).text());
        assertEquals(APPLIED, first.executions.confirmReconciliation(command(regenerated, newReservation, "new-bill", "0.2")).block().code());
        assertEquals(BudgetReservation.State.RESERVED, first.executions.reservation(owner, originalReservation.reservationId()).block().state());
        assertEquals(AdmissionFixture.money("1"), account().held());
        assertEquals(AdmissionFixture.money("0.2"), account().charged());
    }

    @Test
    public void regeneratedVersionsDoNotChangeTheLastTenTurnsOrSequence() {
        var conversation = create();
        String last = null;
        for (int i = 1; i <= 12; i++) {
            last = submit(conversation.conversationId(), "q" + i);
            start(last);
            complete(last, "a" + i, false, false);
        }
        for (int i = 0; i < 3; i++) {
            last = regenerate(last, conversation(conversation.conversationId()).version(), "regenerate-" + i).executionId();
            start(last);
            complete(last, "replacement" + i, false, false);
        }
        var next = submit(conversation.conversationId(), "q13");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertEquals(21, snapshot.messages().size());
        assertEquals(10, snapshot.history().turnIds().size());
        assertEquals("q3", ((ChatMessage.Text) snapshot.messages().getFirst().content().getFirst()).text());
        assertEquals("replacement2", ((ChatMessage.Text) snapshot.messages().get(19).content().getFirst()).text());
        assertEquals(13, first.executions.findTurn(owner, conversation.conversationId(), find(next).conversation().turnId()).block().sequence());
    }

    private org.springframework.test.web.servlet.MockMvc mvc(com.arte.ainew.config.NewAiProperties properties) {
        var clock = Clock.systemUTC();
        var resolver = new FixedExecutionAuthorizationResolver(properties);
        var auth = new AdmissionAuthorization(resolver, properties, clock);
        var accounts = new BudgetAccountQueryService(new com.arte.ainew.application.control.FixedControlCatalog(properties, auth, clock), auth, first.executions);
        var http = new com.arte.ainew.web.NewAiHttpContext(new com.arte.ainew.context.ExecutionContextFactory(resolver, clock), properties);
        var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new com.arte.ainew.web.controller.NewAiReconciliationController(new ManualReconciliationService(auth, accounts, first.executions), http, properties),
                        new com.arte.ainew.web.controller.NewAiChatController(first.chat, first.catalog, http, properties))
                .setControllerAdvice(new com.arte.ainew.web.ConversationExceptionHandler()).setValidator(validator).build();
    }

    private tools.jackson.databind.JsonNode http(org.springframework.test.web.servlet.MockMvc mvc, String path, Map<String, ?> body, String key, int status) throws Exception {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        var response = mvc.perform(request).andReturn();
        if (response.getRequest().isAsyncStarted()) {
            response.getAsyncResult(5000);
            response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(response)).andReturn();
        }
        assertEquals(response.getResponse().getContentAsString(), status, response.getResponse().getStatus());
        return json.readTree(response.getResponse().getContentAsString());
    }

    private void login(String name) {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(name, "unused", List.of()));
    }

    private Map<String, Object> billRequest(String id, BudgetReservation reservation) {
        var data = new LinkedHashMap<String, Object>();
        data.put("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"));
        data.put("budgetRef", "alice-budget");
        data.put("invocationId", id);
        data.put("invocationVersion", find(id).version());
        data.put("reservationId", reservation.reservationId());
        data.put("reservationVersion", reservation.version());
        data.put("actualCharge", "0.123456789012345678");
        data.put("currency", "CNY");
        data.put("evidenceRef", "bill-reference");
        data.put("note", "Bill checked and execution ended");
        data.put("executionEnded", true);
        return data;
    }

    @Test
    public void httpVerifiesAmountsEvidenceAuthorizationAndDurableReceipt() throws Exception {
        try {
            login("alice");
            var mvc = mvc(first.properties);
            var id = submit(create().conversationId(), "question");
            var reservation = start(id);
            complete(id, "partial", true, true);
            reservation = pending(reservation);
            var request = billRequest(id, reservation);
            var query = Map.of("scope", request.get("scope"), "budgetRef", "alice-budget", "current", 1, "size", 10);
            var listed = http(mvc, "/ai-new/budget/pendingReconciliations", query, null, 200).path("data");
            assertTrue(listed.path("canReconcile").asBoolean());
            assertEquals(1, listed.path("page").path("total").asLong());
            assertTrue(listed.path("page").path("records").get(0).path("reserved").path("amount").isString());
            http(mvc, "/ai-new/budget/confirmReconciliation", request, null, 400);
            for (String bad : List.of("-1", "1e-9", "0.1234567890123456789", "not-money")) {
                var invalid = new LinkedHashMap<>(request);
                invalid.put("actualCharge", bad);
                http(mvc, "/ai-new/budget/confirmReconciliation", invalid, "invalid", 400);
            }
            var notEnded = new LinkedHashMap<>(request);
            notEnded.put("executionEnded", false);
            http(mvc, "/ai-new/budget/confirmReconciliation", notEnded, "not-ended", 400);
            assertEquals(AdmissionFixture.money("1"), account().held());
            assertEquals(Invocation.State.UNKNOWN, find(id).state());
            var receipt = http(mvc, "/ai-new/budget/confirmReconciliation", request, "verify-http", 200).path("data");
            assertTrue(receipt.path("charge").path("amount").isString());
            assertEquals("0.123456789012345678", receipt.path("charge").path("amount").asString());
            assertEquals("alice", receipt.path("reviewer").path("subjectName").asString());
            assertEquals(receipt, http(mvc, "/ai-new/budget/confirmReconciliation", request, "verify-http", 200).path("data"));
            assertEquals(receipt, http(mvc, "/ai-new/budget/reconciliationReceipt", Map.of("scope", request.get("scope"),
                    "budgetRef", "alice-budget", "invocationId", id, "key", "verify-http"), null, 200).path("data"));
            http(mvc, "/ai-new/budget/reconciliationReceipt", Map.of("scope", request.get("scope"), "budgetRef", "alice-budget", "invocationId", "missing", "key", "verify-http"), null, 404);
            assertEquals(0, http(mvc, "/ai-new/budget/pendingReconciliations", query, null, 200).path("data").path("page").path("total").asLong());
            login("bob");
            http(mvc, "/ai-new/budget/confirmReconciliation", request, "foreign", 404);
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            http(mvc, "/ai-new/budget/pendingReconciliations", query, null, 401);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void httpReadOnlyGrantCanListButCannotConfirmAndRegenerationRequiresAKey() throws Exception {
        try {
            login("alice");
            var id = submit(create().conversationId(), "question");
            var reservation = start(id);
            complete(id, "partial", true, true);
            reservation = pending(reservation);
            var original = first.properties;
            var grants = original.grants().stream().map(grant -> {
                var scopes = new HashSet<>(grant.scopes());
                scopes.remove(AdmissionAuthorization.BUDGET_ADMIN);
                return new com.arte.ainew.config.NewAiProperties.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(), grant.workspaceId(),
                        grant.grantRef(), grant.enabled(), scopes, grant.bindingIds(), grant.budgetRefs());
            }).toList();
            var restricted = new com.arte.ainew.config.NewAiProperties(original.enabled(), original.dataSourceBean(), original.releaseRef(), original.persistence(), original.limits(),
                    grants, original.capabilities(), original.bindings(), original.connections(), original.rates(), original.budgets());
            var mvc = mvc(restricted);
            var request = billRequest(id, reservation);
            var query = Map.of("scope", request.get("scope"), "budgetRef", "alice-budget", "current", 1, "size", 10);
            assertFalse(http(mvc, "/ai-new/budget/pendingReconciliations", query, null, 200).path("data").path("canReconcile").asBoolean());
            http(mvc, "/ai-new/budget/confirmReconciliation", request, "forbidden", 403);
            var regen = Map.of("scope", request.get("scope"), "originalInvocationId", id, "expectedConversationVersion", 1, "timeoutSeconds", 30);
            http(mvc, "/ai-new/chat/regenerate", regen, null, 400);
            var accepted = http(mvc, "/ai-new/chat/regenerate", regen, "regenerate-http", 202).path("data");
            assertEquals("INVOCATION", accepted.path("kind").asString());
            assertNotEquals(id, accepted.path("executionId").asString());
            assertEquals(accepted, http(mvc, "/ai-new/chat/regenerate", regen, "regenerate-http", 202).path("data"));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void tinyBillAmountIsReturnedAsPlainDecimalString() throws Exception {
        try {
            login("alice");
            var id = submit(create().conversationId(), "question");
            var reservation = start(id);
            complete(id, "answer", false, false);
            reservation = pending(reservation);
            var request = billRequest(id, reservation);
            request.put("actualCharge", "0.000000000000000001");
            var receipt = http(mvc(first.properties), "/ai-new/budget/confirmReconciliation", request, "tiny-bill", 200).path("data");
            assertEquals("0.000000000000000001", receipt.path("charge").path("amount").asString());
            assertEquals(AdmissionFixture.money("0.000000000000000001"), account().charged());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    public void candidateLimitStillAllowsIdempotentReplay() {
        var conversation = create();
        var original = submit(conversation.conversationId(), "question");
        first.executions.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(new ExecutionCommands.Version(owner, original, 0), "cancel-original",
                new ExecutionPayload.Terminal(Invocation.State.CANCELLED, null, null))).block();
        AcceptedExecution last = null;
        long lastVersion = 0;
        for (int i = 1; i < com.arte.ainew.common.validation.ContractChecks.MAX_ITEMS; i++) {
            lastVersion = conversation(conversation.conversationId()).version();
            last = regenerate(original, lastVersion, "regenerate-" + i);
            assertTrue(first.executions.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(new ExecutionCommands.Version(owner, last.executionId(), 0),
                    "cancel", new ExecutionPayload.Terminal(Invocation.State.CANCELLED, null, null))).block().successful());
        }
        assertEquals(last, regenerate(original, lastVersion, "regenerate-255"));
        assertEquals(256, first.executions.findTurn(owner, conversation.conversationId(), find(original).conversation().turnId()).block().invocationIds().size());
        long currentVersion = conversation(conversation.conversationId()).version();
        assertThrows(IllegalArgumentException.class, () -> regenerate(original, currentVersion, "over-limit"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(256, count("arte_ai_invocation"));
    }

}
