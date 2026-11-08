package com.arte.ainew.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.budget.ManualReconciliationService;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.budget.*;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.testing.TestAuthorizationResolver;
import com.arte.ainew.testing.TestAuthorizations;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.platform.ExecutionOwner;
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
        var auth = TestAuthorizations.create(new TestAuthorizationResolver(fixture.properties), fixture.properties, Clock.systemUTC());
        return new ManualReconciliationService(auth, new BudgetAccountQueryService(fixture.catalog.readAdapter(), auth, fixture.executions), fixture.executions);
    }

    private AcceptedExecution regenerate(String id, long version, String key) {
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
    public void ordinaryUnknownCanContinueBeforeBillAndOldBillCannotChangeAcceptedInput() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var reservation = start(id);
        complete(id, "partial", true, false);
        reservation = pending(reservation);
        var next = submit(conversation.conversationId(), "can continue before bill");
        var accepted = find(next);
        var snapshot = first.payloads.find(owner, accepted.contextSnapshotId()).block();
        assertNull(snapshot.history());
        assertEquals(1, snapshot.messages().size());
        assertEquals("can continue before bill", ((ChatMessage.Text) snapshot.messages().getFirst().content().getFirst()).text());
        assertEquals(Invocation.State.UNKNOWN, find(id).state());
        assertEquals(AdmissionFixture.money("1"), account().held());
        var turn = first.executions.findTurn(owner, conversation.conversationId(), find(id).conversation().turnId()).block();
        var confirm = command(id, reservation, "verify", "0");
        assertEquals(APPLIED, first.executions.confirmReconciliation(confirm).block().code());
        assertEquals(Invocation.State.FAILED, find(id).state());
        assertEquals(accepted, find(next));
        assertEquals(snapshot, first.payloads.find(owner, accepted.contextSnapshotId()).block());
        assertEquals(turn, first.executions.findTurn(owner, conversation.conversationId(), turn.turnId()).block());
        assertEquals(next, first.conversations.executionState(conversation.conversationId(), first.context("alice", "state")).block().activeExecution().executionId());
        assertEquals(AdmissionFixture.money("0"), account().held());
        assertEquals(AdmissionFixture.money("0"), account().charged());
    }

    @Test
    public void ordinaryUnknownCanRegenerateWithIndependentFundsAndOldBillCannotClearNewGate() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var originalReservation = start(id);
        complete(id, "partial", true, false);
        originalReservation = pending(originalReservation);
        var original = find(id);
        var regenerated = regenerate(id, 1, "regenerate-unknown");
        var replacement = find(regenerated.executionId());
        assertEquals(original.request().input(), replacement.request().input());
        assertEquals(original.request().binding(), replacement.request().binding());
        assertEquals(original.request().capability(), replacement.request().capability());
        assertEquals(original.request().context().budgetRef(), replacement.request().context().budgetRef());
        assertEquals(first.payloads.find(owner, original.contextSnapshotId()).block().messages(),
                first.payloads.find(owner, replacement.contextSnapshotId()).block().messages());
        assertNotEquals(original.contextSnapshotId(), replacement.contextSnapshotId());
        var replacementReservation = start(regenerated.executionId());
        assertNotEquals(originalReservation.reservationId(), replacementReservation.reservationId());
        assertEquals(AdmissionFixture.money("2"), account().held());
        assertEquals(original, find(id));
        var turn = first.executions.findTurn(owner, conversation.conversationId(), original.conversation().turnId()).block();
        assertEquals(List.of(id, regenerated.executionId()), turn.invocationIds());
        assertEquals(id, turn.selectedInvocationId());
        var confirm = command(id, originalReservation, "old-bill", "0.25");
        assertEquals(APPLIED, first.executions.confirmReconciliation(confirm).block().code());
        assertEquals(REPLAYED, second.executions.confirmReconciliation(confirm).block().code());
        assertEquals(Invocation.State.FAILED, find(id).state());
        assertEquals(Invocation.State.RUNNING, find(regenerated.executionId()).state());
        assertEquals(regenerated.executionId(), first.conversations.executionState(conversation.conversationId(), first.context("alice", "state")).block().activeExecution().executionId());
        assertEquals(turn, first.executions.findTurn(owner, conversation.conversationId(), turn.turnId()).block());
        assertEquals(replacementReservation, first.executions.reservation(owner, replacementReservation.reservationId()).block());
        assertEquals(AdmissionFixture.money("1"), account().held());
        assertEquals(AdmissionFixture.money("0.25"), account().charged());
        assertEquals(regenerated, regenerate(id, 1, "regenerate-unknown"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(2, count("arte_ai_invocation"));
        assertEquals(2, count("arte_ai_reservation"));
    }

    @Test
    public void repeatedUnknownRegenerationUsesOneAcceptanceAcrossInstances() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var reservation = start(id);
        complete(id, "partial", true, false);
        pending(reservation);
        var results = Flux.range(0, 8).flatMap(i -> {
            var fixture = i % 2 == 0 ? first : second;
            var context = fixture.context("alice", "same-regeneration");
            return fixture.chat.regenerate(new EntryRequests.Regenerate(id, 1,
                    fixture.chatRequest("unused", 0, "unused", context).options()), context);
        }, 8).collectList().block();
        assertEquals(1, results.stream().map(AcceptedExecution::executionId).distinct().count());
        assertEquals(2, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(1, count("arte_ai_reservation"));
        assertEquals(AdmissionFixture.money("1"), account().held());
        assertEquals(2, first.executions.findTurn(owner, conversation.conversationId(), find(id).conversation().turnId()).block().candidates().size());
    }

    private com.arte.ainew.pojo.context.ContextSnapshot history(String conversationId) {
        var context = first.context("alice", UUID.randomUUID().toString());
        var input = first.chatRequest(conversationId, conversation(conversationId).version(), "next", context).context();
        var selection = new com.arte.ainew.pojo.context.ContextRequest(input.messages(),
                new com.arte.ainew.pojo.context.ContextRequest.HistorySelection(conversationId, conversation(conversationId).version(), List.of(), 10),
                List.of(), List.of(), null, input.budget());
        return first.contexts.assemble(selection, first.catalog.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, context).block(), context).block();
    }

    private void replace(Invocation invocation) {
        jdbc.update("UPDATE arte_ai_invocation SET workspace_snapshot=? WHERE id_key=?", first.codec.encode(invocation),
                com.arte.ainew.serialization.CanonicalJson.key(invocation.request().context().executionId()));
    }

    @Test
    public void noOutputUnknownCanContinueWhileReservationIsStillReserved() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "failed question");
        var reservation = start(id);
        var error = new ExecutionError("HTTP_500", ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace");
        assertTrue(first.executions.commitCompletion(new ExecutionCommands.Complete(guard(id), "unknown-no-output",
                new ExecutionPayload.Terminal(Invocation.State.UNKNOWN, null, error), Usage.unknown(), null)).block().successful());
        var next = submit(conversation.conversationId(), "independent question");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertNull(snapshot.history());
        assertEquals(1, snapshot.messages().size());
        assertEquals(reservation, first.executions.reservation(owner, reservation.reservationId()).block());
        start(next);
        assertEquals(AdmissionFixture.money("2"), account().held());
        assertEquals(2, count("arte_ai_reservation"));
        assertEquals(Invocation.State.UNKNOWN, find(id).state());
    }

    @Test
    public void everyKnownFailureSkipsBothQuestionAndAnswer() {
        for (var state : List.of(Invocation.State.FAILED, Invocation.State.CANCELLED, Invocation.State.TIMED_OUT, Invocation.State.INTERRUPTED)) {
            var conversation = create();
            var id = submit(conversation.conversationId(), "failed question");
            var error = new ExecutionError("KNOWN_FAILURE", ExecutionError.Phase.INVOCATION, false,
                    ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace");
            assertTrue(first.executions.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(new ExecutionCommands.Version(owner, id, 0), "known",
                    new ExecutionPayload.Terminal(state, null, error))).block().successful());
            var next = submit(conversation.conversationId(), "independent");
            var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
            assertNull(snapshot.history());
            assertEquals(1, snapshot.messages().size());
        }
    }

    @Test
    public void failedRegenerationKeepsPriorFullAnswerAndBillDoesNotChangeItsContext() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "complete original answer", false, false);
        var regeneration = regenerate(id, 1, "regeneration").executionId();
        var reservation = start(regeneration);
        complete(regeneration, "failed replacement", true, false);
        reservation = pending(reservation);
        var source = find(id);
        var turn = first.executions.findTurn(owner, conversation.conversationId(), source.conversation().turnId()).block();
        assertEquals(id, turn.selectedInvocationId());
        var next = submit(conversation.conversationId(), "next");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertEquals(List.of(turn.turnId()), snapshot.history().turnIds());
        assertEquals(3, snapshot.messages().size());
        assertEquals("complete original answer", ((ChatMessage.Text) snapshot.messages().get(1).content().getFirst()).text());
        assertEquals(APPLIED, first.executions.confirmReconciliation(command(regeneration, reservation, "bill", "0.3")).block().code());
        assertEquals(snapshot, first.payloads.find(owner, find(next).contextSnapshotId()).block());
        assertEquals(turn, first.executions.findTurn(owner, conversation.conversationId(), turn.turnId()).block());
        assertEquals(source, find(id));
        assertEquals(next, first.conversations.executionState(conversation.conversationId(), first.context("alice", "state")).block().activeExecution().executionId());
        assertEquals(regeneration, regenerate(id, 1, "regeneration").executionId());
        code(ResultCodeEnum.AI_INVALID_STATE, () -> regenerate(id, conversation(conversation.conversationId()).version(), "old-turn"));
    }

    @Test
    public void failedRegenerationWithoutSuccessfulCandidateSkipsTheEntireTurn() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "unknown original", true, false);
        var regenerated = regenerate(id, 1, "regenerate").executionId();
        start(regenerated);
        complete(regenerated, "unknown replacement", true, false);
        var next = submit(conversation.conversationId(), "independent");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertNull(snapshot.history());
        assertEquals(1, snapshot.messages().size());
        assertEquals(2, count("arte_ai_turn"));
        assertEquals(3, count("arte_ai_invocation"));
    }

    @Test
    public void unknownRoundsDoNotBackfillEarlierTurnsBeyondTheRecentTen() {
        var conversation = create();
        for (int i = 0; i < 12; i++) {
            var id = submit(conversation.conversationId(), "question " + i);
            start(id);
            complete(id, "answer " + i, i >= 2, false);
        }
        var next = submit(conversation.conversationId(), "q13");
        var snapshot = first.payloads.find(owner, find(next).contextSnapshotId()).block();
        assertNull(snapshot.history());
        assertEquals(1, snapshot.messages().size());
        assertEquals(13, first.executions.findTurn(owner, conversation.conversationId(), find(next).conversation().turnId()).block().sequence());
    }

    @Test
    public void regenerationReceiptSurvivesDeletedSourceSnapshotAndStillRejectsChangedOperation() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "answer", false, false);
        var accepted = regenerate(id, 1, "same-key");
        first.executions.requestControl(owner, new ExecutionControlRequest("cancel", accepted.executionId(), "cancel", ControlReceipt.Command.CANCEL)).block();
        var source = find(id);
        jdbc.update("DELETE FROM arte_ai_context_snapshot WHERE id_key=?", com.arte.ainew.serialization.CanonicalJson.key(
                com.arte.ainew.serialization.CanonicalJson.key(owner.tenantId(), owner.workspaceId(), owner.subjectId()), source.contextSnapshotId()));
        assertEquals(accepted, regenerate(id, 1, "same-key"));
        code(ResultCodeEnum.AI_CONTEXT_NOT_FOUND, () -> regenerate(id, 2, "new-key"));
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> regenerate(id, 2, "same-key"));
        var context = first.context("alice", "same-key");
        var options = new ExecutionOptions(context.deadline(), 1, 4096, 0, 0, Duration.ofSeconds(30));
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> first.chat.regenerate(new EntryRequests.Regenerate(id, 1, options), context).block());
        assertEquals(2, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
    }

    @Test
    public void regenerationUsesExactModelAndReceiptDoesNotRequireReenabledModel() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "answer", false, false);
        var accepted = regenerate(id, 1, "same-key");
        first.executions.requestControl(owner, new ExecutionControlRequest("cancel", accepted.executionId(), "cancel", ControlReceipt.Command.CANCEL)).block();
        var old = first.properties;
        var capability = old.capabilities().getFirst();
        var disabledCapability = new com.arte.ainew.pojo.control.CapabilityDescriptor(capability.definition(), capability.kind(), capability.inputSchema(),
                capability.outputSchema(), capability.features(), capability.sideEffect(), com.arte.ainew.pojo.control.CapabilityDescriptor.Availability.DISABLED);
        var binding = old.bindings().getFirst();
        var disabledBinding = new com.arte.ainew.pojo.control.ResolvedBinding(binding.definition(), disabledCapability, binding.target(), binding.contextWindowTokens(), binding.rate());
        var disabled = new com.arte.ainew.testing.TestAiConfiguration(old.enabled(), old.dataSourceBean(), old.releaseRef(), old.persistence(), old.limits(),
                old.grants(), List.of(disabledCapability), List.of(disabledBinding), old.connections(), old.rates(), old.budgets());
        var disabledFixture = new AdmissionFixture(dataSource, scheduler, first.codec, disabled);
        var replay = disabledFixture.context("alice", "same-key");
        assertEquals(accepted, disabledFixture.chat.regenerate(new EntryRequests.Regenerate(id, 1,
                disabledFixture.chatRequest("unused", 0, "unused", replay).options()), replay).block());
        var fresh = disabledFixture.context("alice", "new-key");
        assertThrows(AdmissionException.class, () -> disabledFixture.chat.regenerate(new EntryRequests.Regenerate(id, 2,
                disabledFixture.chatRequest("unused", 0, "unused", fresh).options()), fresh).block());
        assertEquals(2, count("arte_ai_invocation"));
        assertNull(first.conversations.executionState(conversation.conversationId(), first.context("alice", "state")).block().activeExecution());
    }

    @Test
    public void snapshotMismatchDoesNotRebuildARegenerationFromDifferentHistory() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "partial", true, false);
        var snapshot = first.payloads.find(owner, find(id).contextSnapshotId()).block();
        var messages = List.of(new ChatMessage("changed", ChatMessage.Role.USER, List.of(new ChatMessage.Text("different question")), List.of(), null));
        var changed = new com.arte.ainew.pojo.context.ContextSnapshot(snapshot.snapshotId(), snapshot.history(), snapshot.modelBinding(), messages,
                snapshot.fragments(), snapshot.budget(), snapshot.inputTokens(), snapshot.estimatedTokens(), snapshot.tokenizerVersion(), snapshot.truncations(),
                com.arte.ainew.application.support.TextInputs.contentDigest(messages), snapshot.createdAt(), snapshot.expiresAt(), snapshot.assistant());
        var bytes = first.codec.encode(changed);
        jdbc.update("UPDATE arte_ai_context_snapshot SET workspace_snapshot=?,payload_digest=?", bytes, com.arte.ainew.serialization.CanonicalJson.sha256(bytes));
        code(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT, () -> regenerate(id, 1, "regenerate"));
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        assertNull(first.conversations.executionState(conversation.conversationId(), first.context("alice", "state")).block().activeExecution());
    }

    @Test
    public void missingInvocationWrongLinkAndCorruptSuccessAreNotSkippedAsOrdinaryFailures() {
        for (var problem : List.of("missing", "wrong-link", "wrong-owner", "missing-result", "corrupt-result")) {
            var conversation = create();
            var id = submit(conversation.conversationId(), "question");
            start(id);
            complete(id, "answer", false, false);
            var original = find(id);
            if (problem.equals("missing"))
                jdbc.update("DELETE FROM arte_ai_invocation WHERE id_key=?", com.arte.ainew.serialization.CanonicalJson.key(id));
            if (problem.equals("wrong-link")) replace(new Invocation(original.request(), original.requestDigest(),
                    new Invocation.ConversationLink("other-conversation", 0, original.conversation().turnId()), original.contextSnapshotId(), null,
                    original.state(), original.version(), original.activeAttemptId(), original.result(), original.error(), original.acceptedAt(), original.updatedAt()));
            if (problem.equals("wrong-owner")) {
                var context = original.request().context();
                var auth = context.authorization();
                var changedAuth = new com.arte.ainew.common.execution.ExecutionAuthorization(
                        new com.arte.ainew.common.execution.ExecutionPrincipal("bob-id", "bob", com.arte.ainew.common.execution.ExecutionPrincipal.Kind.USER),
                        auth.tenantId(), auth.workspaceId(), auth.scopes(), auth.grantRef());
                var changedContext = new com.arte.ainew.common.execution.ExecutionContext(context.executionId(), context.traceId(), changedAuth, context.deadline(),
                        null, context.budgetRef(), context.releaseRef(), context.idempotencyKey());
                var request = new InvocationRequest<>(original.request().capability(), original.request().binding(), original.request().kind(),
                        original.request().input(), original.request().options(), changedContext);
                replace(new Invocation(request, original.requestDigest(), original.conversation(), original.contextSnapshotId(), null,
                        original.state(), original.version(), original.activeAttemptId(), original.result(), original.error(), original.acceptedAt(), original.updatedAt()));
            }
            if (problem.equals("missing-result"))
                jdbc.update("DELETE FROM arte_ai_result WHERE id_key=?", original.result().resultId());
            if (problem.equals("corrupt-result"))
                jdbc.update("UPDATE arte_ai_result SET workspace_snapshot='{}' WHERE id_key=?", original.result().resultId());
            assertThrows(IllegalStateException.class, () -> history(conversation.conversationId()));
        }
    }

    @Test
    public void toolBearingStructuredAndChildUnknownsStillRejectHistoryAssembly() {
        for (var variant : List.of("tools", "tool-message", "structured", "child")) {
            var conversation = create();
            var id = submit(conversation.conversationId(), "question");
            start(id);
            complete(id, "partial", true, false);
            var original = find(id);
            var input = (com.arte.ainew.pojo.generation.GenerationRequest) original.request().input();
            var messages = new ArrayList<>(input.messages());
            var tools = variant.equals("tools") ? List.of(new com.arte.ainew.pojo.generation.GenerationRequest.ToolDefinition(
                    new com.arte.core.pojo.reference.DefinitionRef("tool", "read", "v1"), "read",
                    new com.arte.core.pojo.reference.DefinitionRef("schema", "args", "v1"), com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY)) : input.tools();
            if (variant.equals("tool-message"))
                messages.add(new ChatMessage("tool", ChatMessage.Role.TOOL, List.of(new ChatMessage.Text("done")), List.of(), "call"));
            com.arte.ainew.pojo.generation.GenerationRequest.OutputFormat format = variant.equals("structured")
                    ? new com.arte.ainew.pojo.generation.GenerationRequest.StructuredOutput(new com.arte.core.pojo.reference.DefinitionRef("schema", "output", "v1")) : input.outputFormat();
            var changedInput = new com.arte.ainew.pojo.generation.GenerationRequest(messages, input.options(), tools, format);
            var context = original.request().context();
            var changedContext = new com.arte.ainew.common.execution.ExecutionContext(context.executionId(), context.traceId(), context.authorization(), context.deadline(),
                    variant.equals("child") ? "run" : null, context.budgetRef(), context.releaseRef(), context.idempotencyKey());
            var request = new InvocationRequest<>(original.request().capability(), original.request().binding(), changedInput.kind(), changedInput,
                    new ExecutionOptions(context.deadline(), 1, 4096, 1, 1), changedContext);
            replace(new Invocation(request, original.requestDigest(), original.conversation(), original.contextSnapshotId(), null,
                    original.state(), original.version(), original.activeAttemptId(), original.result(), original.error(), original.acceptedAt(), original.updatedAt()));
            code(ResultCodeEnum.AI_CONVERSATION_BUSY, () -> history(conversation.conversationId()));
            code(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED, () -> regenerate(id, 1, "regenerate-" + variant));
        }
    }

    @Test
    public void incompleteSuccessReturnedByResultPortIsAnErrorInsteadOfAnEmptyHistory() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        start(id);
        complete(id, "answer", false, false);
        var auth = TestAuthorizations.create(new TestAuthorizationResolver(first.properties), first.properties, Clock.systemUTC());
        var incomplete = new InvocationResult.Generation(new ModelResult("incomplete", new ModelResult.ModelIdentity("provider", "model", null),
                List.of(new ChatMessage("partial", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("partial")), List.of(), null)),
                ModelResult.FinishReason.LENGTH, false, null, Usage.unknown(), List.of()));
        var results = new com.arte.ainew.spi.persistence.ExecutionResultStore() {
            public reactor.core.publisher.Mono<StoreOutcome<ResultRef>> put(ExecutionOwner o, String invocation, String attempt, String key, InvocationResult value) {
                throw new AssertionError("History cannot write results");
            }

            public reactor.core.publisher.Mono<InvocationResult> find(ExecutionOwner o, String invocation, ResultRef ref) {
                return reactor.core.publisher.Mono.just(incomplete);
            }
        };
        var loader = new com.arte.ainew.application.context.ChatHistoryLoader(first.conversations, auth, first.executions, first.payloads, results);
        assertThrows(IllegalStateException.class, () -> loader.load(new com.arte.ainew.pojo.context.ContextRequest.HistorySelection(
                conversation.conversationId(), 1, List.of(), 10), AdmissionFixture.CAP.id(), first.context("alice", "read-history")).block());
    }

    @Test
    public void regenerationReceiptRechecksReadPermissionAndDoesNotAcceptASendKey() {
        var conversation = create();
        var id = submit(conversation.conversationId(), "question");
        var sourceKey = find(id).request().context().idempotencyKey();
        start(id);
        complete(id, "answer", false, false);
        var accepted = regenerate(id, 1, "regenerate");
        var restricted = first.context("alice", "regenerate", Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION));
        assertThrows(AccessDeniedException.class, () -> first.chat.regenerate(new EntryRequests.Regenerate(id, 1,
                first.chatRequest("unused", 0, "unused", restricted).options()), restricted).block());
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> regenerate(id, 1, sourceKey));
        assertEquals(accepted, regenerate(id, 1, "regenerate"));
        assertEquals(2, count("arte_ai_invocation"));
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

    private org.springframework.test.web.servlet.MockMvc mvc(com.arte.ainew.testing.TestAiConfiguration properties) {
        var clock = Clock.systemUTC();
        var resolver = new TestAuthorizationResolver(properties);
        var auth = com.arte.ainew.testing.TestAuthorizations.create(resolver, properties, clock);
        var accounts = new BudgetAccountQueryService(new com.arte.ainew.testing.TestControlCatalog(properties, auth, clock).readAdapter(), auth, first.executions);
        var http = new com.arte.ainew.web.NewAiHttpContext(new com.arte.ainew.context.ExecutionContextFactory(resolver, clock), properties.deployment());
        var validator = new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        new com.arte.ainew.web.controller.NewAiReconciliationController(new ManualReconciliationService(auth, accounts, first.executions), http, properties.deployment()),
                        new com.arte.ainew.web.controller.NewAiChatController(first.chat, first.catalog, http, properties.deployment()))
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
    public void remoteReadOnlyGrantCanListButCannotConfirmAndRegenerationRequiresAKey() throws Exception {
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
                return new com.arte.ainew.pojo.control.ControlConfiguration.Grant(grant.subjectName(), grant.subjectId(), grant.principalKind(), grant.tenantId(), grant.workspaceId(),
                        grant.grantRef(), grant.enabled(), scopes, grant.bindingIds(), grant.budgetRefs());
            }).toList();
            var restricted = new com.arte.ainew.testing.TestAiConfiguration(original.enabled(), original.dataSourceBean(), original.releaseRef(), original.persistence(), original.limits(),
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
