package com.arte.ainew.persistence;

import com.arte.ainew.common.execution.*;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.embedding.EmbeddingRequest;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationEvent;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.pojo.execution.ExecutionCommands.*;
import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static org.junit.Assert.*;

/** 两个独立适配器共享数据库，用真实事务验证竞争、故障回滚与跨实例恢复；不访问旧 AI／供应商。 */
public class MybatisExecutionPersistenceTest {
    private static final ExecutionOwner OWNER = new ExecutionOwner("tenant", "workspace", "subject");
    private static final String DIGEST = "a".repeat(64);
    private static final DefinitionRef RATE = new DefinitionRef("rate", "rate", "v1");
    private static final Currency CNY = Currency.getInstance("CNY");
    private JdbcDataSource dataSource;
    private Scheduler scheduler;
    private MybatisExecutionPersistence first;
    private MybatisExecutionPersistence second;
    private JacksonExecutionRecordCodec codec;
    private JdbcTemplate jdbc;

    @Before public void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        codec = new JacksonExecutionRecordCodec();
        scheduler = Schedulers.newBoundedElastic(12, 256, "ainew-mybatis-test");
        new ResourceDatabasePopulator(new ClassPathResource("ainew/persistence/schema-mysql.sql")).execute(dataSource);
        first = new MybatisExecutionPersistence(dataSource, codec, scheduler);
        second = new MybatisExecutionPersistence(dataSource, codec, scheduler);
        jdbc = new JdbcTemplate(dataSource);
    }
    @After public void tearDown() {
        jdbc.execute("DROP ALL OBJECTS"); scheduler.dispose();
    }
    private Money money(String value) { return new Money(new BigDecimal(value), CNY); }
    private Invocation candidate(String id, String key, String budget, Invocation.ConversationLink link) {
        var at = Instant.now();
        var authorization = new ExecutionAuthorization(new ExecutionPrincipal(OWNER.subjectId(), "alice", ExecutionPrincipal.Kind.USER),
                OWNER.tenantId(), OWNER.workspaceId(), Set.of("ai:invoke"), "grant");
        var context = new ExecutionContext(id, "trace", authorization, at.plusSeconds(300), null, budget, "release", key);
        var input = new EmbeddingRequest(List.of(new EmbeddingRequest.Input("input", "hello")));
        var request = new InvocationRequest<>(new DefinitionRef("capability", "embedding", "v1"),
                new DefinitionRef("binding", "binding", "v1"), input.kind(), input,
                new ExecutionOptions(context.deadline(), 3, 100_000, 0, 0), context);
        return new Invocation(request, DIGEST, link, null, null, Invocation.State.ACCEPTED, 0, null, null, null, at, at);
    }
    private Invocation accept(String id, String budget) {
        return first.accept(new Accept(candidate(id, "key-" + id, budget, null), null)).block().value();
    }
    private Attempt create(String id) {
        return first.createAttempt(new CreateAttempt(new Version(OWNER, id, 0), "attempt-" + id, "worker", Duration.ofSeconds(30))).block().value();
    }
    private Guard guard(String id) {
        return Guard.from(OWNER, second.find(OWNER, id).block(), second.findAttempt(OWNER, id, "attempt-" + id).block());
    }
    private Attempt dispatch(String id) { return first.markDispatch(new Dispatch(guard(id), "remote-" + id)).block().value(); }
    private void account(String limit) {
        first.createAccount(new BudgetCommands.Account("budget", OWNER, money(limit), money("0"), money("0"), RATE, 0)).block();
    }
    private BudgetCommands.Reserve reserveCommand(String id, String amount) {
        return new BudgetCommands.Reserve(guard(id), "reservation-" + id, money(amount), RATE, Duration.ofHours(1));
    }
    private ExecutionPayload.OutputBatch batch(String text) {
        return new ExecutionPayload.OutputBatch(List.of(new GenerationEvent.TextDelta(text)));
    }
    private Complete success(String id, String key) {
        return new Complete(guard(id), key, new ExecutionPayload.Terminal(Invocation.State.SUCCEEDED,
                new ResultRef("result-" + id, "embedding-result", 1, DIGEST, false), null), Usage.unknown(), null);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test public void nonBlockingSchedulerIsRejectedBeforeStartingDatabaseWork() {
        var invalid = new MybatisExecutionPersistence(dataSource, codec, Schedulers.parallel());
        assertThrows(IllegalStateException.class, () -> invalid.accept(
                new Accept(candidate("wrong-scheduler", "wrong-scheduler", null, null), null)).block());
        assertEquals(0, count("ainew_invocation"));
        assertEquals(0, count("ainew_lock"));
    }

    @Test public void simultaneousAcceptanceReturnsOneDurableIdentityAndDispatch() {
        var results = Flux.range(0, 24).flatMap(i -> (i % 2 == 0 ? first : second).accept(
                new Accept(candidate("inv-" + i, "same-key", null, null), null)), 12).collectList().block();
        assertEquals(1, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(23, results.stream().filter(r -> r.code() == REPLAYED).count());
        assertEquals(1, results.stream().map(r -> id(r.value())).distinct().count());
        assertEquals(1, count("ainew_invocation")); assertEquals(1, count("ainew_acceptance"));
        assertEquals(1, count("ainew_event")); assertEquals(2, count("ainew_outbox"));
        var other = candidate("other", "same-key", null, null);
        other = new Invocation(other.request(), "b".repeat(64), null, null, null, other.state(), 0, null, null, null,
                other.acceptedAt(), other.updatedAt());
        assertEquals(IDEMPOTENCY_CONFLICT, second.accept(new Accept(other, null)).block().code());
    }

    @Test public void acceptanceRollsBackInvocationTurnAndOutboxTogether() {
        var at = Instant.now();
        first.createConversation(new Conversation("conversation", OWNER, "chat", 0, null, List.of(), Conversation.State.ACTIVE, at, at)).block();
        var invocation = candidate("inv", "key", null, new Invocation.ConversationLink("conversation", 0, "turn"));
        var turn = new Turn("turn", "conversation", 1, null, null,
                new ChatMessage("message", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null),
                List.of("inv"), null, 0, at, at);
        var failing = faulty(value -> value instanceof ExecutionEvent<?>);
        assertThrows(IllegalStateException.class, () -> failing.accept(new Accept(invocation, turn)).block());
        assertEquals(0, count("ainew_invocation")); assertEquals(0, count("ainew_turn"));
        assertEquals(0, count("ainew_acceptance")); assertEquals(0, count("ainew_outbox"));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT version_no FROM ainew_conversation", Long.class));
        assertEquals(APPLIED, first.accept(new Accept(invocation, turn)).block().code());
        var busy = candidate("busy", "other-key", null, new Invocation.ConversationLink("conversation", 1, "other-turn"));
        var busyTurn = new Turn("other-turn", "conversation", 2, null, null, turn.userMessage(), List.of("busy"), null, 0, at, at);
        assertEquals(CONVERSATION_BUSY, second.accept(new Accept(busy, busyTurn)).block().code());
        assertEquals(1, count("ainew_invocation"));
    }

    @Test public void coldPublisherWritesOnlyOnSubscriptionAndDoesNotBlockCallerThread() {
        var publisher = first.accept(new Accept(candidate("inv", "key", null, null), null));
        assertEquals(0, count("ainew_invocation"));
        var thread = publisher.map(ignored -> Thread.currentThread().getName()).block();
        assertTrue(thread.startsWith("ainew-mybatis-test"));
        assertEquals(REPLAYED, publisher.block().code());
    }

    @Test public void wrongOwnerCannotReadOrMutateAnExecution() {
        accept("inv", null); create("inv");
        var foreign = new ExecutionOwner("tenant", "workspace", "intruder");
        assertNull(second.find(foreign, "inv").block());
        assertNull(second.findAttempt(foreign, "inv", "attempt-inv").block());
        assertEquals(OWNER_MISMATCH, second.createAttempt(new CreateAttempt(new Version(foreign, "inv", 1), "other", "worker",
                Duration.ofSeconds(30))).block().code());
        assertEquals(OWNER_MISMATCH, second.replay(foreign, new ExecutionEvent.Cursor("inv", 0), 10).block().code());
    }

    @Test public void concurrentCasUpdatesHaveExactlyOneWinner() {
        accept("inv", null); create("inv"); var guard = guard("inv");
        var results = Flux.merge(first.renewLease(guard, Duration.ofSeconds(20)), second.renewLease(guard, Duration.ofSeconds(20)))
                .collectList().block();
        assertEquals(1, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(1, results.stream().filter(r -> r.code() == VERSION_CONFLICT).count());
        assertEquals(INVALID_STATE, second.createAttempt(new CreateAttempt(new Version(OWNER, "inv", 1), "duplicate", "worker",
                Duration.ofSeconds(30))).block().code());
        assertEquals(1, count("ainew_attempt"));
    }

    @Test public void expiredWorkerIsFencedAndUndispatchedAttemptCanBeRecovered() throws Exception {
        accept("inv", null);
        var attempt = first.createAttempt(new CreateAttempt(new Version(OWNER, "inv", 0), "attempt-inv", "old-worker", Duration.ofSeconds(1)))
                .block().value();
        var old = guard("inv"); Thread.sleep(1100);
        assertEquals(LEASE_LOST, first.markDispatch(new Dispatch(old, "remote")).block().code());
        var claimed = second.acquireLease(new AcquireLease(old.invocation(), attempt.attemptId(), attempt.version(), "new-worker",
                Duration.ofSeconds(30), LeasePurpose.EXECUTE)).block();
        assertEquals(APPLIED, claimed.code()); assertTrue(claimed.value().fencingToken() > attempt.fencingToken());
        assertEquals(VERSION_CONFLICT, first.markDispatch(new Dispatch(old, "remote")).block().code());
        assertEquals(APPLIED, second.markDispatch(new Dispatch(guard("inv"), "remote")).block().code());
    }

    @Test public void possibleDispatchRequiresReconciliationAndCannotBeResent() throws Exception {
        accept("inv", null);
        first.createAttempt(new CreateAttempt(new Version(OWNER, "inv", 0), "attempt-inv", "worker", Duration.ofSeconds(1))).block();
        dispatch("inv");
        assertEquals(Attempt.Dispatch.MAY_HAVE_EXECUTED, second.findAttempt(OWNER, "inv", "attempt-inv").block().dispatch());
        var error = new ExecutionError("UNKNOWN", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace");
        assertEquals(APPLIED, first.commitCompletion(new Complete(guard("inv"), "unknown", new ExecutionPayload.Terminal(
                Invocation.State.UNKNOWN, null, error), Usage.unknown(), null)).block().code());
        Thread.sleep(1100); var guard = guard("inv");
        assertEquals(RECONCILIATION_REQUIRED, second.acquireLease(new AcquireLease(guard.invocation(), guard.attemptId(),
                guard.expectedAttemptVersion(), "new-worker", Duration.ofSeconds(30), LeasePurpose.EXECUTE)).block().code());
        assertEquals(APPLIED, second.acquireLease(new AcquireLease(guard.invocation(), guard.attemptId(), guard.expectedAttemptVersion(),
                "reconciler", Duration.ofSeconds(30), LeasePurpose.RECONCILE)).block().code());
        assertEquals(INVALID_STATE, second.markDispatch(new Dispatch(guard("inv"), "resend")).block().code());
        var completion = success("inv", "reconciled");
        assertEquals(INVALID_STATE, first.commitCompletion(completion).block().code());
        assertEquals(APPLIED, second.commitCompletion(new Complete(completion.guard(), completion.completionKey(), completion.terminal(),
                completion.usage(), "provider-query-proof")).block().code());
        assertEquals(Invocation.State.SUCCEEDED, first.find(OWNER, "inv").block().state());
        assertEquals("provider-query-proof", jdbc.queryForObject("SELECT evidence_ref FROM ainew_operation WHERE evidence_ref IS NOT NULL", String.class));
        assertEquals(1, count("ainew_attempt"));
    }

    @Test public void onlyProvenSafeFailureMayCreateTheNextUniqueAttempt() {
        accept("inv", null); create("inv"); dispatch("inv");
        var unsafe = new ExecutionError("FAIL", ExecutionError.Phase.INVOCATION, true,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.KNOWN, "trace");
        assertEquals(RECONCILIATION_REQUIRED, first.updateConditionally(new FailAttempt(guard("inv"), Attempt.State.FAILED, unsafe,
                Usage.unknown())).block().code());
        var safe = new ExecutionError("FAIL", ExecutionError.Phase.INVOCATION, true,
                ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace");
        var old = guard("inv");
        assertEquals(APPLIED, first.updateConditionally(new FailAttempt(old, Attempt.State.FAILED, safe, Usage.unknown())).block().code());
        var next = second.createAttempt(new CreateAttempt(new Version(OWNER, "inv", 1), "next", "worker-2", Duration.ofSeconds(30)))
                .block().value();
        assertEquals(2, next.attemptNumber()); assertTrue(next.fencingToken() > old.fencingToken());
        assertEquals(VERSION_CONFLICT, first.renewLease(old, Duration.ofSeconds(30)).block().code());
        var stale = new Guard(new Version(OWNER, "inv", 2), old.attemptId(), old.expectedAttemptVersion(), old.workerId(), old.fencingToken());
        assertEquals(LEASE_LOST, first.renewLease(stale, Duration.ofSeconds(30)).block().code());
    }

    @Test public void concurrentAppendAssignsDurableSequencesAndDeduplicatesWholeBatches() {
        accept("inv", null); create("inv"); dispatch("inv"); var guard = guard("inv");
        var results = Flux.range(0, 24).flatMap(i -> (i % 2 == 0 ? first : second).appendBatch(
                new Append(guard, "batch-" + i, List.of(batch("output-" + i)))), 12).collectList().block();
        assertTrue(results.stream().allMatch(StoreOutcome::successful));
        var page = first.replay(OWNER, new ExecutionEvent.Cursor("inv", 0), 256).block().value();
        assertEquals(26, page.events().size());
        for (int i = 0; i < 26; i++) { assertEquals(i + 1, page.events().get(i).sequence()); }
        var duplicate = new Append(guard, "batch-1", List.of(batch("output-1")));
        assertEquals(REPLAYED, second.appendBatch(duplicate).block().code());
        assertEquals(IDEMPOTENCY_CONFLICT, second.appendBatch(new Append(guard, "batch-1", List.of(batch("different")))).block().code());
        assertEquals(26, count("ainew_event"));
        assertEquals(0, first.replay(OWNER, page.next(), 256).block().value().events().size());
    }

    @Test public void failedAppendAndCompletionLeaveNoPartialStateOrPublication() {
        accept("inv", null); create("inv"); dispatch("inv");
        // 第二个 INSERT 被数据库拒绝，验证第一个事件、Outbox 及计数器随事务一起回滚。
        jdbc.execute("ALTER TABLE ainew_event ADD CONSTRAINT injected_append_fault CHECK(sequence_no<>4)");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> first.appendBatch(new Append(guard("inv"), "batch", List.of(batch("x"), batch("y")))).block());
        jdbc.execute("ALTER TABLE ainew_event DROP CONSTRAINT injected_append_fault");
        assertEquals(2, count("ainew_event")); assertEquals(0, count("ainew_operation"));
        var failCompletion = faulty(value -> value instanceof ExecutionEvent<?> event && event.kind() == ExecutionEvent.Kind.TERMINAL);
        var completion = success("inv", "finish");
        assertThrows(IllegalStateException.class, () -> failCompletion.commitCompletion(completion).block());
        assertEquals(Invocation.State.RUNNING, second.find(OWNER, "inv").block().state());
        assertEquals(Attempt.State.RUNNING, second.findAttempt(OWNER, "inv", "attempt-inv").block().state());
        assertEquals(2, count("ainew_event")); assertEquals(3, count("ainew_outbox"));
        assertEquals(APPLIED, first.commitCompletion(completion).block().code());
        assertEquals(REPLAYED, second.commitCompletion(completion).block().code());
        assertEquals(3, count("ainew_event")); assertEquals(4, count("ainew_outbox"));
        var changed = new Complete(completion.guard(), "finish", new ExecutionPayload.Terminal(Invocation.State.CANCELLED, null, null),
                Usage.unknown(), null);
        assertEquals(IDEMPOTENCY_CONFLICT, first.commitCompletion(changed).block().code());
        assertEquals(INVALID_STATE, first.commitCompletion(success("inv", "other-key")).block().code());
    }

    @Test public void outboxSurvivesPublisherCrashAndRejectsStaleAcknowledge() throws Exception {
        accept("inv", null);
        var messages = first.claim(OutboxMessage.Kind.DISPATCH, "publisher-1", Duration.ofSeconds(1), 1).block();
        assertEquals(1, messages.size());
        assertTrue(second.claim(OutboxMessage.Kind.DISPATCH, "publisher-2", Duration.ofSeconds(1), 1).block().isEmpty());
        Thread.sleep(1100);
        var reclaimed = second.claim(OutboxMessage.Kind.DISPATCH, "publisher-2", Duration.ofSeconds(30), 1).block().getFirst();
        assertEquals(messages.getFirst().messageId(), reclaimed.messageId());
        assertTrue(reclaimed.fencingToken() > messages.getFirst().fencingToken());
        assertEquals(LEASE_LOST, first.acknowledge(messages.getFirst()).block().code());
        assertEquals(APPLIED, second.acknowledge(reclaimed).block().code());
        assertEquals(REPLAYED, second.acknowledge(reclaimed).block().code());
        assertTrue(first.claim(OutboxMessage.Kind.DISPATCH, "publisher-3", Duration.ofSeconds(30), 1).block().isEmpty());
    }

    @Test public void pruningRejectsExpiredCursorsAndCannotDropUnpublishedEvents() {
        accept("inv", null); create("inv"); dispatch("inv"); first.commitCompletion(success("inv", "finish")).block();
        assertEquals(INVALID_STATE, first.discardThrough(OWNER, "inv", 2).block().code());
        var events = second.claim(OutboxMessage.Kind.EVENT, "publisher", Duration.ofSeconds(30), 10).block();
        for (var event : events) { assertEquals(APPLIED, second.acknowledge(event).block().code()); }
        assertEquals(APPLIED, first.discardThrough(OWNER, "inv", 2).block().code());
        assertEquals(CURSOR_EXPIRED, second.replay(OWNER, new ExecutionEvent.Cursor("inv", 1), 10).block().code());
        var page = second.replay(OWNER, new ExecutionEvent.Cursor("inv", 2), 10).block().value();
        assertEquals(1, page.events().size()); assertEquals(3, page.next().afterSequence());
        assertNotNull(second.find(OWNER, "inv").block().result());
    }

    @Test public void competingReservationsCannotOverspendTheSharedBudget() {
        account("100");
        for (int i = 0; i < 24; i++) { accept("inv-" + i, "budget"); create("inv-" + i); }
        var commands = new ArrayList<BudgetCommands.Reserve>();
        for (int i = 0; i < 24; i++) { commands.add(reserveCommand("inv-" + i, "10")); }
        var results = Flux.fromIterable(commands).index().flatMap(pair -> (pair.getT1() % 2 == 0 ? first : second).reserve(pair.getT2()), 12)
                .collectList().block();
        assertEquals(10, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(14, results.stream().filter(r -> r.code() == INSUFFICIENT_BUDGET).count());
        assertEquals(money("100"), first.account(OWNER, "budget").block().held());
        assertEquals(10, count("ainew_reservation"));
        var winner = results.stream().filter(StoreOutcome::successful).findFirst().orElseThrow().value();
        var replay = commands.stream().filter(c -> c.reservationId().equals(winner.reservationId())).findFirst().orElseThrow();
        assertEquals(REPLAYED, second.reserve(replay).block().code());
        assertEquals(money("100"), second.account(OWNER, "budget").block().held());
    }

    @Test public void rateCurrencyAndDispatchBudgetAreCheckedAtTheTransactionBoundary() {
        account("100"); accept("inv", "budget"); create("inv");
        assertEquals(INSUFFICIENT_BUDGET, first.markDispatch(new Dispatch(guard("inv"), null)).block().code());
        var wrongCurrency = new BudgetCommands.Reserve(guard("inv"), "reservation", new Money(BigDecimal.TEN, Currency.getInstance("USD")),
                RATE, Duration.ofHours(1));
        assertEquals(CURRENCY_MISMATCH, first.reserve(wrongCurrency).block().code());
        var wrongRate = new BudgetCommands.Reserve(guard("inv"), "reservation", money("10"), new DefinitionRef("rate", "rate", "v2"), Duration.ofHours(1));
        assertEquals(RATE_MISMATCH, first.reserve(wrongRate).block().code());
        var command = reserveCommand("inv", "10");
        assertEquals(APPLIED, first.reserve(command).block().code());
        assertEquals(IDEMPOTENCY_CONFLICT, first.reserve(new BudgetCommands.Reserve(command.guard(), command.reservationId(), money("11"),
                RATE, command.retention())).block().code());
        assertNotNull(dispatch("inv"));
    }

    @Test public void unknownCostKeepsFundsAndFinalSettlementIsExactlyOnce() {
        account("100"); accept("inv", "budget"); create("inv");
        var reserved = first.reserve(reserveCommand("inv", "80")).block().value(); dispatch("inv");
        var pending = new BudgetSettlement("unknown", reserved.reservationId(), BudgetSettlement.State.PENDING_RECONCILIATION,
                Usage.unknown(), null, Instant.now());
        var pendingCommand = new BudgetCommands.Settle(OWNER, 0, pending, BudgetCommands.Evidence.UNKNOWN_COST, null);
        assertEquals(APPLIED, second.settle(pendingCommand).block().code());
        assertEquals(REPLAYED, first.settle(pendingCommand).block().code());
        assertEquals(money("80"), first.account(OWNER, "budget").block().held());
        var changedPending = new BudgetSettlement("unknown", reserved.reservationId(), BudgetSettlement.State.PENDING_RECONCILIATION,
                Usage.unknown(), money("1"), pending.recordedAt());
        assertEquals(IDEMPOTENCY_CONFLICT, first.settle(new BudgetCommands.Settle(OWNER, 1, changedPending,
                BudgetCommands.Evidence.UNKNOWN_COST, null)).block().code());
        var release = new BudgetSettlement("release", reserved.reservationId(), BudgetSettlement.State.RELEASED, Usage.unknown(), money("0"), Instant.now());
        assertEquals(RECONCILIATION_REQUIRED, first.settle(new BudgetCommands.Settle(OWNER, 1, release,
                BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED, "proof")).block().code());
        var settled = new BudgetSettlement("bill", reserved.reservationId(), BudgetSettlement.State.SETTLED,
                new Usage(Usage.Basis.PROVIDER_REPORTED, 1L, 1L, 2L), money("120"), Instant.now());
        var command = new BudgetCommands.Settle(OWNER, 1, settled, BudgetCommands.Evidence.PROVIDER_BILL, "bill-ref");
        var results = Flux.merge(first.settle(command), second.settle(command)).collectList().block();
        assertEquals(1, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(1, results.stream().filter(r -> r.code() == REPLAYED).count());
        var balance = first.account(OWNER, "budget").block();
        assertEquals(BudgetReservation.State.SETTLED, second.reservation(OWNER, reserved.reservationId()).block().state());
        assertNull(first.reservation(new ExecutionOwner("tenant", "workspace", "other"), reserved.reservationId()).block());
        assertEquals("bill-ref", jdbc.queryForObject("SELECT evidence_ref FROM ainew_settlement WHERE evidence_kind='PROVIDER_BILL'", String.class));
        assertEquals(money("0"), balance.held()); assertEquals(money("120"), balance.charged());
        assertEquals(0, balance.available().compareTo(new BigDecimal("-20")));
        accept("next", "budget"); create("next");
        assertEquals(INSUFFICIENT_BUDGET, first.reserve(reserveCommand("next", "1")).block().code());
        var duplicateFinal = new BudgetSettlement("different-key", reserved.reservationId(), BudgetSettlement.State.SETTLED,
                settled.usage(), settled.charge(), Instant.now());
        assertEquals(INVALID_STATE, first.settle(new BudgetCommands.Settle(OWNER, 2, duplicateFinal,
                BudgetCommands.Evidence.PROVIDER_BILL, "bill-ref")).block().code());
        assertEquals(money("120"), second.account(OWNER, "budget").block().charged());
    }

    @Test public void reservationAndSettlementFaultsRollbackTheEntireLedger() {
        account("100"); accept("inv", "budget"); create("inv");
        var failReserve = faulty(value -> value instanceof Attempt a && a.budgetReservationId() != null);
        assertThrows(IllegalStateException.class, () -> failReserve.reserve(reserveCommand("inv", "10")).block());
        assertEquals(0, count("ainew_reservation")); assertEquals(money("0"), first.account(OWNER, "budget").block().held());
        assertNull(first.findAttempt(OWNER, "inv", "attempt-inv").block().budgetReservationId());
        var reserved = first.reserve(reserveCommand("inv", "10")).block().value();
        var settlement = new BudgetSettlement("release", reserved.reservationId(), BudgetSettlement.State.RELEASED,
                Usage.unknown(), money("0"), Instant.now());
        var command = new BudgetCommands.Settle(OWNER, 0, settlement, BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED, "proof");
        var failSettle = faulty(value -> value instanceof BudgetReservation r && r.state() == BudgetReservation.State.RELEASED);
        assertThrows(IllegalStateException.class, () -> failSettle.settle(command).block());
        assertEquals(money("10"), first.account(OWNER, "budget").block().held()); assertEquals(0, count("ainew_settlement"));
        assertEquals(APPLIED, first.settle(command).block().code()); assertEquals(money("0"), first.account(OWNER, "budget").block().held());
        assertEquals(INSUFFICIENT_BUDGET, first.markDispatch(new Dispatch(guard("inv"), null)).block().code());
    }

    @Test public void snapshotCodecRoundTripsWhitelistedInputsAndRejectsForeignTypesAndVersions() {
        var invocation = candidate("inv", "key", null, null);
        assertEquals(invocation, codec.decode(codec.encode(invocation), Invocation.class));
        var generation = new GenerationRequest(List.of(new ChatMessage("message", ChatMessage.Role.USER,
                List.of(new ChatMessage.Text("hello")), List.of(), null)),
                new GenerationOptions(128, null, null, List.of()), List.of(), new GenerationRequest.TextOutput());
        var request = new InvocationRequest<>(invocation.request().capability(), invocation.request().binding(), generation.kind(), generation,
                invocation.request().options(), invocation.request().context());
        var generated = new Invocation(request, DIGEST, null, "snapshot", null, invocation.state(), 0, null, null, null,
                invocation.acceptedAt(), invocation.updatedAt());
        assertEquals(generated, codec.decode(codec.encode(generated), Invocation.class));
        var arguments = new StructuredValue.ObjectValue(Map.of("nested", new StructuredValue.ArrayValue(List.of(
                new StructuredValue.NumberValue(new BigDecimal("1.20")), StructuredValue.NullValue.INSTANCE))));
        var tool = new ToolInvocation("call", new DefinitionRef("tool", "tool", "v1"), arguments);
        var toolRequest = new InvocationRequest<>(request.capability(), request.binding(), tool.kind(), tool, request.options(), request.context());
        var toolInvocation = new Invocation(toolRequest, DIGEST, null, null, null, invocation.state(), 0, null, null, null,
                invocation.acceptedAt(), invocation.updatedAt());
        assertEquals(toolInvocation, codec.decode(codec.encode(toolInvocation), Invocation.class));
        var json = codec.encode(invocation);
        assertTrue(json.contains("\"acceptedAt\":\""));
        var accountSnapshot = new BudgetCommands.Account("budget", OWNER, money("2.50"), money("0"), money("0"), RATE, 0);
        var accountJson = codec.encode(accountSnapshot);
        assertTrue(accountJson.contains("\"amount\":\"2.5\""));
        assertEquals(accountSnapshot, codec.decode(accountJson, BudgetCommands.Account.class));
        assertThrows(RuntimeException.class, () -> codec.decode(json.replace("\"embedding\"", "\"java.lang.Runtime\""), Invocation.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":99"), Invocation.class));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(json, Attempt.class));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(new Object()));
    }


    @Test public void cancellationBeforeDispatchReleasesConversationGateAndRegenerationPreservesOriginalTurn() {
        var at = Instant.now();
        first.createConversation(new Conversation("conversation", OWNER, "chat", 0, null, List.of(), Conversation.State.ACTIVE, at, at)).block();
        var invocation = candidate("original", "key-original", null, new Invocation.ConversationLink("conversation", 0, "turn"));
        var turn = new Turn("turn", "conversation", 1, null, null,
                new ChatMessage("message", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null),
                List.of("original"), null, 0, at, at);
        assertEquals(APPLIED, first.accept(new Accept(invocation, turn)).block().code());
        assertEquals(1, second.findConversation(OWNER, "conversation").block().version());
        var completion = new CompleteBeforeAttempt(new Version(OWNER, "original", 0), "cancel",
                new ExecutionPayload.Terminal(Invocation.State.CANCELLED, null, null));
        assertEquals(APPLIED, first.commitCompletion(completion).block().code());
        assertEquals(REPLAYED, second.commitCompletion(completion).block().code());
        assertEquals(0, count("ainew_attempt"));
        var another = candidate("replacement", "key-replacement", null, new Invocation.ConversationLink("conversation", 1, "turn"));
        var replacement = new Invocation(another.request(), another.requestDigest(), another.conversation(), null, "original", another.state(),
                0, null, null, null, another.acceptedAt(), another.updatedAt());
        var regenerated = new Turn(turn.turnId(), turn.conversationId(), turn.sequence(), turn.parentTurnId(), turn.supersedesTurnId(),
                turn.userMessage(), List.of("original", "replacement"), null, 1, turn.createdAt(), Instant.now());
        assertEquals(APPLIED, second.accept(new Accept(replacement, regenerated)).block().code());
        assertEquals(2, first.findConversation(OWNER, "conversation").block().version());
        assertEquals(regenerated, first.findTurn(OWNER, "conversation", "turn").block());
        assertEquals(Invocation.State.CANCELLED, second.find(OWNER, "original").block().state());
        assertNull(first.findTurn(new ExecutionOwner("tenant", "workspace", "other"), "conversation", "turn").block());
    }

    @Test public void outputBudgetRejectsNewDataWithoutAllocatingSequencesOrPublishing() {
        accept("inv", null); create("inv"); dispatch("inv");
        assertEquals(APPLIED, first.appendBatch(new Append(guard("inv"), "one", List.of(batch("x".repeat(60_000))))).block().code());
        assertEquals(INVALID_STATE, second.appendBatch(new Append(guard("inv"), "two", List.of(batch("y".repeat(60_000))))).block().code());
        assertEquals(3, count("ainew_event")); assertEquals(4, count("ainew_outbox"));
    }

    @Test public void reservationExpiryStopsDispatchWithoutAutomaticallyFreeingFunds() throws Exception {
        account("100"); accept("inv", "budget"); create("inv");
        var command = new BudgetCommands.Reserve(guard("inv"), "reservation", money("10"), RATE, Duration.ofSeconds(1));
        assertEquals(APPLIED, first.reserve(command).block().code());
        Thread.sleep(1100);
        assertEquals(INSUFFICIENT_BUDGET, second.markDispatch(new Dispatch(guard("inv"), "remote")).block().code());
        assertEquals(money("10"), first.account(OWNER, "budget").block().held());
        assertEquals(REPLAYED, second.reserve(command).block().code());
    }

    @Test public void noResultIsPersistedIfCompletionUsesALostLease() throws Exception {
        accept("inv", null);
        first.createAttempt(new CreateAttempt(new Version(OWNER, "inv", 0), "attempt-inv", "worker", Duration.ofSeconds(1))).block();
        dispatch("inv"); var completion = success("inv", "finish");
        Thread.sleep(1100);
        assertEquals(LEASE_LOST, second.commitCompletion(completion).block().code());
        assertEquals(Invocation.State.RUNNING, first.find(OWNER, "inv").block().state());
        assertEquals(2, count("ainew_event")); assertEquals(0, count("ainew_operation"));
    }

    private MybatisExecutionPersistence faulty(java.util.function.Predicate<Object> when) {
        var armed = new AtomicBoolean(true);
        var faultyCodec = new ExecutionRecordCodec() {
            @Override public String encode(Object value) {
                if (when.test(value) && armed.getAndSet(false)) { throw new IllegalStateException("injected persistence failure"); }
                return codec.encode(value);
            }
            @Override public <T> T decode(String json, Class<T> type) { return codec.decode(json, type); }
        };
        return new MybatisExecutionPersistence(dataSource, faultyCodec, scheduler);
    }
}
