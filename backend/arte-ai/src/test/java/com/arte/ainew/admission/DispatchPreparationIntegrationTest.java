package com.arte.ainew.admission;

import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.scheduler.Schedulers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * 隔离真实数据库验证原子准备、重复领取、预算不足及派发标记失败后的整体回滚。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 18:00 ✾
 */
public class DispatchPreparationIntegrationTest {
    private final Duration wait = Duration.ofSeconds(10);
    private JdbcTemplate jdbc;
    private reactor.core.scheduler.Scheduler scheduler;
    private AdmissionFixture fixture;
    private AdmissionFixture other;
    private Invocation invocation;
    private OutboxMessage claim;

    @Before
    public void setup() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        scheduler = Schedulers.newBoundedElastic(4, 128, "dispatch-preparation-test");
        fixture = new AdmissionFixture(source, scheduler);
        other = new AdmissionFixture(source, scheduler);
        fixture.initializeBudget("alice");
        var conversation = fixture.conversations.create("atomic", null, List.of(), fixture.context("alice", "conversation")).block(wait);
        var context = fixture.context("alice", "message");
        var accepted = fixture.chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "test", context), context).block(wait);
        invocation = fixture.executions.find(AdmissionFixture.owner("alice-id"), accepted.executionId()).block(wait);
        claim = fixture.executions.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofMinutes(2), 1).block(wait).getFirst();
    }

    @After
    public void close() {
        scheduler.dispose();
        jdbc.execute("DROP ALL OBJECTS");
    }

    private DispatchPreparationStore.Command command(String amount) {
        return new DispatchPreparationStore.Command(new ExecutionCommands.Version(claim.owner(), claim.invocationId(), invocation.version()),
                claim, "attempt", Duration.ofSeconds(30), AdmissionFixture.money(amount), AdmissionFixture.RATE, Duration.ofMinutes(5));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertRolledBack() {
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(0, count("arte_ai_reservation"));
        assertEquals(1, count("arte_ai_event"));
        var saved = fixture.executions.find(claim.owner(), claim.invocationId()).block(wait);
        assertEquals(Invocation.State.ACCEPTED, saved.state());
        assertEquals(0, saved.version());
        assertEquals(0, jdbc.queryForObject("SELECT next_attempt FROM arte_ai_invocation", Integer.class).intValue());
        assertEquals(AdmissionFixture.money("0"), fixture.executions.account(claim.owner(), "alice-budget").block(wait).held());
    }

    @Test
    public void preparesOnceAndRejectsReplayRatherThanGrantingAnotherSend() {
        var cmd = command("1");
        var result = fixture.executions.prepareDispatch(cmd).block(wait);
        assertTrue(result.successful());
        assertTrue(result.value().ready());
        assertEquals(Attempt.Dispatch.MAY_HAVE_EXECUTED, result.value().attempt().dispatch());
        assertEquals(2, result.value().attempt().version());
        assertEquals(1, result.value().invocation().version());
        assertEquals(1, count("arte_ai_reservation"));
        assertEquals(AdmissionFixture.money("1"), fixture.executions.account(claim.owner(), "alice-budget").block(wait).held());
        assertEquals(StoreOutcome.Code.VERSION_CONFLICT, other.executions.prepareDispatch(cmd).block(wait).code());
        assertEquals(1, count("arte_ai_attempt"));
    }

    @Test
    public void competingInstancesCannotReserveOrPrepareTheSameInvocationTwice() throws Exception {
        var cmd = command("1");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> fixture.executions.prepareDispatch(cmd).block(wait));
            var second = pool.submit(() -> other.executions.prepareDispatch(cmd).block(wait));
            var results = List.of(first.get(), second.get());
            assertEquals(1, results.stream().filter(StoreOutcome::successful).count());
            assertEquals(1, results.stream().filter(r -> r.code() == StoreOutcome.Code.VERSION_CONFLICT).count());
        }
        assertEquals(1, count("arte_ai_attempt"));
        assertEquals(1, count("arte_ai_reservation"));
    }

    @Test
    public void differentInvocationsSharingOneBudgetCannotOverReserve() throws Exception {
        var conversation = fixture.conversations.create("second", null, List.of(), fixture.context("alice", "second-conversation")).block(wait);
        var context = fixture.context("alice", "second-message");
        var accepted = fixture.chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "second", context), context).block(wait);
        var secondClaim = fixture.executions.claim(OutboxMessage.Kind.DISPATCH, "other-worker", Duration.ofMinutes(2), 1).block(wait).getFirst();
        var secondCommand = new DispatchPreparationStore.Command(new ExecutionCommands.Version(secondClaim.owner(), accepted.executionId(), 0),
                secondClaim, "second-attempt", Duration.ofSeconds(30), AdmissionFixture.money("60"), AdmissionFixture.RATE, Duration.ofMinutes(5));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> fixture.executions.prepareDispatch(command("60")).block(wait));
            var second = pool.submit(() -> other.executions.prepareDispatch(secondCommand).block(wait));
            var results = List.of(first.get(), second.get());
            assertTrue(results.stream().allMatch(StoreOutcome::successful));
            assertEquals(1, results.stream().filter(r -> r.value().ready()).count());
        }
        assertEquals(AdmissionFixture.money("60"), fixture.executions.account(claim.owner(), "alice-budget").block(wait).held());
        assertEquals(1, count("arte_ai_reservation"));
    }

    @Test
    public void insufficientBudgetKeepsAnUndispatchedAttemptWithoutHoldingFunds() {
        var result = fixture.executions.prepareDispatch(command("101")).block(wait);
        assertTrue(result.successful());
        assertFalse(result.value().ready());
        assertEquals(Attempt.Dispatch.NOT_STARTED, result.value().attempt().dispatch());
        assertNull(result.value().attempt().budgetReservationId());
        assertEquals(0, count("arte_ai_reservation"));
        assertEquals(AdmissionFixture.money("0"), fixture.executions.account(claim.owner(), "alice-budget").block(wait).held());
    }

    @Test
    public void staleClaimAndCancelledInvocationCannotCreateAttempts() {
        var stale = new OutboxMessage(claim.messageId(), claim.invocationId(), claim.owner(), claim.kind(), claim.eventSequence(),
                claim.workerId(), claim.fencingToken() + 1, claim.leaseExpiresAt());
        var cmd = command("1");
        assertEquals(StoreOutcome.Code.LEASE_LOST, fixture.executions.prepareDispatch(new DispatchPreparationStore.Command(
                cmd.invocation(), stale, cmd.attemptId(), cmd.attemptLease(), cmd.amount(), cmd.rate(), cmd.retention())).block(wait).code());
        assertRolledBack();
        fixture.executions.requestControl(claim.owner(), new ExecutionControlRequest("cancel", claim.invocationId(), "cancel",
                ControlReceipt.Command.CANCEL)).block(wait);
        assertFalse(fixture.executions.prepareDispatch(cmd).block(wait).successful());
        assertEquals(0, count("arte_ai_attempt"));
    }

    @Test
    public void failureWritingDispatchRollsBackAttemptBudgetCountersAndEvents() {
        jdbc.execute("ALTER TABLE arte_ai_attempt ADD CONSTRAINT reject_dispatch CHECK (workspace_snapshot NOT LIKE '%MAY_HAVE_EXECUTED%')");
        assertThrows(RuntimeException.class, () -> fixture.executions.prepareDispatch(command("1")).block(wait));
        assertRolledBack();
        jdbc.execute("ALTER TABLE arte_ai_attempt DROP CONSTRAINT reject_dispatch");
        assertTrue(fixture.executions.prepareDispatch(command("1")).block(wait).value().ready());
    }

    @Test
    public void claimExpiringAfterReservationRollsBackTheWholePreparation() {
        advanceClockAfterReservation(180);
        assertEquals(StoreOutcome.Code.LEASE_LOST, fixture.executions.prepareDispatch(command("1")).block(wait).code());
        assertRolledBack();
    }

    @Test
    public void attemptExpiringBeforeDispatchRollsBackTheWholePreparation() {
        advanceClockAfterReservation(31);
        assertEquals(StoreOutcome.Code.LEASE_LOST, fixture.executions.prepareDispatch(command("1")).block(wait).code());
        assertRolledBack();
    }

    @Test
    public void reservationExpiringBeforeDispatchRollsBackTheWholePreparation() {
        advanceClockAfterReservation(2);
        var original = command("1");
        var shortReservation = new DispatchPreparationStore.Command(original.invocation(), original.claim(), original.attemptId(),
                original.attemptLease(), original.amount(), original.rate(), Duration.ofSeconds(1));
        assertEquals(StoreOutcome.Code.INSUFFICIENT_BUDGET, fixture.executions.prepareDispatch(shortReservation).block(wait).code());
        assertRolledBack();
    }

    private void advanceClockAfterReservation(int seconds) {
        var base = Instant.now();
        var actual = (SystemMapper) ReflectionTestUtils.getField(fixture.executions, "system");
        var clock = new SystemMapper() {
            public Timestamp databaseTime() {
                // 同一真实事务内已经写入预留后才推进时钟，测试不依赖辅助方法调用次数。
                boolean reserved = jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class) > 0;
                return Timestamp.from(reserved ? base.plusSeconds(seconds) : base);
            }

            public int ensureLock(String key) {
                return actual.ensureLock(key);
            }

            public String lock(String key) {
                return actual.lock(key);
            }
        };
        ReflectionTestUtils.setField(fixture.executions, "system", clock);
    }
}
