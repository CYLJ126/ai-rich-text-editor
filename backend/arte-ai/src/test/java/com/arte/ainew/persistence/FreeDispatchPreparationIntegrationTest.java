package com.arte.ainew.persistence;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode.FREE;
import static org.junit.Assert.*;

/**
 * 无预算账户的隔离数据库验证 FREE 原子派发；直接使用内部存储，不提前开放 TOOL 受理或执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 13:42 ✾
 */
public class FreeDispatchPreparationIntegrationTest {
    private static final ExecutionOwner OWNER = new ExecutionOwner("tenant", "workspace", "subject");
    private static final Duration WAIT = Duration.ofSeconds(10);
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private MybatisExecutionPersistence first;
    private MybatisExecutionPersistence second;
    private Invocation invocation;
    private OutboxMessage claim;

    @Before
    public void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        scheduler = Schedulers.newBoundedElastic(4, 128, "free-dispatch-test");
        first = new MybatisExecutionPersistence(source, new JacksonExecutionRecordCodec(), scheduler);
        second = new MybatisExecutionPersistence(source, new JacksonExecutionRecordCodec(), scheduler);
        invocation = accept("tool", null);
        claim = claim();
    }

    @After
    public void close() {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
    }

    private Invocation accept(String id, String budgetRef) {
        return accept(id, budgetRef, new ToolInvocation("call-" + id, new DefinitionRef("tool", "local.count", "v1"),
                new StructuredValue.ObjectValue(Map.of())));
    }

    private Invocation accept(String id, String budgetRef, CapabilityInput input) {
        var at = Instant.now();
        var authorization = new ExecutionAuthorization(new ExecutionPrincipal(OWNER.subjectId(), "alice", ExecutionPrincipal.Kind.USER),
                OWNER.tenantId(), OWNER.workspaceId(), Set.of("ai:invoke"), "grant");
        var context = new ExecutionContext(id, "trace", authorization, at.plusSeconds(90), null, budgetRef, "release", "key-" + id);
        var request = new InvocationRequest<>(new DefinitionRef("capability", "tool", "v1"),
                new DefinitionRef("binding", "tool-binding", "v1"), input.kind(), input,
                new ExecutionOptions(context.deadline(), 3, 100_000, 0, 0), context);
        var candidate = new Invocation(request, "a".repeat(64), null, input instanceof GenerationRequest ? "snapshot-" + id : null, null,
                Invocation.State.ACCEPTED, 0, null, null, null, at, at);
        var result = first.accept(new ExecutionCommands.Accept(candidate, null)).block(WAIT);
        assertTrue(result.successful());
        return result.value();
    }

    private OutboxMessage claim() {
        return first.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofMinutes(2), 1).block(WAIT).getFirst();
    }

    private DispatchPreparationStore.Command command() {
        return new DispatchPreparationStore.Command(new ExecutionCommands.Version(OWNER, claim.invocationId(), invocation.version()),
                claim, "attempt-" + claim.invocationId(), Duration.ofSeconds(30), new DispatchPreparationStore.Free());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertNoBudgetWrites() {
        assertEquals(0, count("arte_ai_account"));
        assertEquals(0, count("arte_ai_reservation"));
        assertEquals(0, count("arte_ai_settlement"));
    }

    private void assertRolledBack() {
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(1, count("arte_ai_event"));
        var saved = second.find(OWNER, invocation.request().context().executionId()).block(WAIT);
        assertEquals(Invocation.State.ACCEPTED, saved.state());
        assertEquals(0, saved.version());
        assertNull(saved.activeAttemptId());
        assertEquals(0, jdbc.queryForObject("SELECT next_attempt FROM arte_ai_invocation", Integer.class).intValue());
        // 受理时的 ACCEPTED 事件通知及 DISPATCH 工作项均保留，事务内 STARTED 通知已回滚。
        assertEquals(2, count("arte_ai_outbox"));
        assertTrue(second.validateClaim(claim).block(WAIT).successful());
        assertNoBudgetWrites();
    }

    @Test
    public void freeDispatchIsDurableWithoutAnyBudgetAccountAndRejectsReplay() {
        var cmd = command();
        var result = first.prepareDispatch(cmd).block(WAIT);
        assertTrue(result.successful());
        var prepared = result.value();
        assertTrue(prepared.ready());
        assertEquals(FREE, prepared.billingMode());
        assertEquals(Invocation.State.RUNNING, prepared.invocation().state());
        assertEquals(1, prepared.invocation().version());
        assertEquals(Attempt.State.RUNNING, prepared.attempt().state());
        assertEquals(Attempt.Dispatch.MAY_HAVE_EXECUTED, prepared.attempt().dispatch());
        assertEquals(1, prepared.attempt().version());
        assertEquals(1, prepared.attempt().attemptNumber());
        assertTrue(prepared.attempt().fencingToken() > 0);
        assertNotNull(prepared.attempt().remoteRequestId());
        assertNull(prepared.attempt().budgetReservationId());
        assertEquals(prepared.invocation(), second.find(OWNER, claim.invocationId()).block(WAIT));
        assertEquals(prepared.attempt(), second.findAttempt(OWNER, claim.invocationId(), cmd.attemptId()).block(WAIT));
        assertEquals(VERSION_CONFLICT, second.prepareDispatch(cmd).block(WAIT).code());
        assertEquals(1, count("arte_ai_attempt"));
        assertNoBudgetWrites();
    }

    @Test
    public void competingInstancesCannotGrantTwoFreeSends() throws Exception {
        var cmd = command();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> first.prepareDispatch(cmd).block(WAIT));
            var two = pool.submit(() -> second.prepareDispatch(cmd).block(WAIT));
            var results = List.of(one.get(), two.get());
            assertEquals(1, results.stream().filter(StoreOutcome::successful).count());
            assertEquals(1, results.stream().filter(r -> r.code() == VERSION_CONFLICT).count());
        }
        assertEquals(1, count("arte_ai_attempt"));
        assertNoBudgetWrites();
    }

    @Test
    public void staleClaimCannotCreateAttempt() {
        var stale = new OutboxMessage(claim.messageId(), claim.invocationId(), claim.owner(), claim.kind(), claim.eventSequence(),
                claim.workerId(), claim.fencingToken() + 1, claim.leaseExpiresAt());
        var cmd = command();
        var result = first.prepareDispatch(new DispatchPreparationStore.Command(cmd.invocation(), stale,
                cmd.attemptId(), cmd.attemptLease(), cmd.billingPlan())).block(WAIT);
        assertEquals(LEASE_LOST, result.code());
        assertRolledBack();
    }

    @Test
    public void cancelledInvocationCannotPrepareFreeDispatch() {
        first.requestControl(OWNER, new ExecutionControlRequest("cancel", claim.invocationId(), "cancel",
                ControlReceipt.Command.CANCEL)).block(WAIT);
        var before = second.find(OWNER, claim.invocationId()).block(WAIT);
        assertFalse(first.prepareDispatch(command()).block(WAIT).successful());
        assertEquals(before, second.find(OWNER, claim.invocationId()).block(WAIT));
        assertEquals(0, count("arte_ai_attempt"));
        assertNoBudgetWrites();
    }

    @Test
    public void expiredClaimCannotCreateAttempt() {
        advanceClock(180, false);
        assertEquals(LEASE_LOST, first.prepareDispatch(command()).block(WAIT).code());
        assertRolledBack();
    }

    @Test
    public void claimExpiringDuringPreparationRollsBackEverything() {
        advanceClock(180, true);
        assertEquals(LEASE_LOST, first.prepareDispatch(command()).block(WAIT).code());
        assertRolledBack();
    }

    @Test
    public void attemptExpiringDuringPreparationRollsBackEverything() {
        advanceClock(31, true);
        assertEquals(LEASE_LOST, first.prepareDispatch(command()).block(WAIT).code());
        assertRolledBack();
    }

    @Test
    public void executionDeadlineExpiringDuringPreparationRollsBackEverything() {
        // 保持领取与 Attempt 有效，单独触发 Invocation 的总期限检查。
        var cmd = command();
        advanceClock(91, true);
        var longerAttempt = new DispatchPreparationStore.Command(cmd.invocation(), claim, cmd.attemptId(),
                Duration.ofMinutes(2), cmd.billingPlan());
        assertEquals(INVALID_STATE, first.prepareDispatch(longerAttempt).block(WAIT).code());
        assertRolledBack();
    }

    @Test
    public void dispatchWriteFailureRollsBackAttemptCountersAndEventsAndCanRetry() {
        jdbc.execute("ALTER TABLE arte_ai_attempt ADD CONSTRAINT reject_dispatch CHECK (workspace_snapshot NOT LIKE '%MAY_HAVE_EXECUTED%')");
        assertThrows(RuntimeException.class, () -> first.prepareDispatch(command()).block(WAIT));
        assertRolledBack();
        jdbc.execute("ALTER TABLE arte_ai_attempt DROP CONSTRAINT reject_dispatch");
        var prepared = first.prepareDispatch(command()).block(WAIT).value();
        assertTrue(prepared.ready());
        assertEquals(1, prepared.attempt().attemptNumber());
        assertEquals(1, prepared.attempt().fencingToken());
        assertNoBudgetWrites();
    }

    @Test
    public void freePlanCannotBypassABudgetBoundTool() {
        // 默认调用已领取，因此只会领取本次新增调用。
        invocation = accept("paid-tool", "budget-required");
        claim = claim();
        assertEquals(INVALID_STATE, first.prepareDispatch(command()).block(WAIT).code());
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(Invocation.State.ACCEPTED, second.find(OWNER, claim.invocationId()).block(WAIT).state());
        assertNoBudgetWrites();
    }

    @Test
    public void modelWithoutBudgetCannotUseFreePlanEither() {
        var input = new GenerationRequest(List.of(new ChatMessage("message", ChatMessage.Role.USER,
                List.of(new ChatMessage.Text("test")), List.of(), null)),
                new GenerationOptions(100, null, null, List.of()), List.of(), new GenerationRequest.TextOutput());
        invocation = accept("model", null, input);
        claim = claim();
        assertEquals(INVALID_STATE, first.prepareDispatch(command()).block(WAIT).code());
        assertEquals(0, count("arte_ai_attempt"));
        assertNoBudgetWrites();
    }

    @Test
    public void chargeStillRequiresBudgetInsteadOfFallingBackToFree() {
        var cmd = command();
        var charge = new DispatchPreparationStore.Charge(new Money(BigDecimal.ONE, Currency.getInstance("CNY")),
                new DefinitionRef("rate", "tool-rate", "v1"), Duration.ofMinutes(5));
        assertEquals(NOT_FOUND, first.prepareDispatch(new DispatchPreparationStore.Command(cmd.invocation(), claim,
                cmd.attemptId(), cmd.attemptLease(), charge)).block(WAIT).code());
        assertRolledBack();
    }

    @Test
    public void freeResultCannotMasqueradeAsChargedOrInsufficientBudget() {
        var prepared = first.prepareDispatch(command()).block(WAIT).value();
        assertThrows(IllegalArgumentException.class,
                () -> new DispatchPreparationStore.PreparedDispatch(prepared.invocation(), prepared.attempt(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new DispatchPreparationStore.PreparedDispatch(prepared.invocation(), prepared.attempt(), false, FREE));
        assertThrows(IllegalStateException.class, () -> command().amount());
        assertThrows(IllegalStateException.class, () -> command().rate());
        assertThrows(IllegalStateException.class, () -> command().retention());
    }

    private void advanceClock(int seconds, boolean afterAttempt) {
        var base = Instant.now();
        var actual = (SystemMapper) ReflectionTestUtils.getField(first, "system");
        var clock = new SystemMapper() {
            public Timestamp databaseTime() {
                boolean created = jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_attempt", Integer.class) > 0;
                return Timestamp.from(!afterAttempt || created ? base.plusSeconds(seconds) : base);
            }

            public int ensureLock(String key) {
                return actual.ensureLock(key);
            }

            public String lock(String key) {
                return actual.lock(key);
            }
        };
        ReflectionTestUtils.setField(first, "system", clock);
    }
}
