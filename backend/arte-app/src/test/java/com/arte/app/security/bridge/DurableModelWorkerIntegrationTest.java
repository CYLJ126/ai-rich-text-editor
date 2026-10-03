package com.arte.app.security.bridge;

import com.arte.ai.api.control.BindingManager;
import com.arte.ai.api.control.CapabilityCatalog;
import com.arte.ai.api.control.ConnectionManager;
import com.arte.ai.api.execution.BudgetService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.app.ainew.CompatibleChatProviderAdapter;
import com.arte.app.ainew.DurableModelWorker;
import com.arte.app.ainew.ExistingModelAccessPolicy;
import com.arte.app.ainew.JdbcModelWorkQueue;
import com.arte.app.execution.support.JdbcAuditSink;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.spi.observability.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.arte.app.security.bridge.SecurityBridgeFixture.TENANT;
import static com.arte.app.security.bridge.SecurityBridgeFixture.WORKSPACE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真正的队列、执行账本、身份与事务；供应商只用本地替身，不访问模型网络。
 */
class DurableModelWorkerIntegrationTest {
    NewChatIntegrationTest f;
    JdbcModelWorkQueue queue;
    DurableModelWorker worker;

    @BeforeEach
    void setup() throws Exception {
        f = new NewChatIntegrationTest();
        f.setup();
        f.clock.now = Instant.now();
        f.chatSetup();
        ScriptUtils.executeSqlScript(f.schemaConnection, MySqlTestScripts.h2Resource(
                Files.readString(Path.of("scripts", "arte-ai-new-work-ddl-mysql.sql"))));
        queue = queue(2, 2, 100);
        assertTrue(queue.acquire());
        wire(queue);
    }

    JdbcModelWorkQueue queue(int threads, int queued, int rate) {
        return new JdbcModelWorkQueue(f.jdbc, f.manager, f.executions, TENANT, threads, queued, rate, Duration.ofSeconds(30));
    }

    void wire(JdbcModelWorkQueue next) {
        f.coordinator = new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(f.definitions),
                new ConnectionManager(f.definitions), new BindingManager(f.definitions)), new DefaultModelGateway(List.of(f.provider)),
                new ExistingModelAccessPolicy(f.repository, f.clock, "new-ai"), f.egress, f.admission, f.tasks,
                f.executions, f.executions, new BudgetService(new BudgetQuote(BigDecimal.ONE, "USD"), f.executions),
                new JdbcAuditSink(f.jdbc, f.manager, f.clock), f.clock, Telemetry.disabled(), next);
        f.wire(f.chatStore, 4096);
    }

    void start() {
        worker = new DurableModelWorker(queue, f.coordinator, f.tasks, f.admission, Telemetry.disabled(), 2,
                Duration.ofMillis(50), Duration.ofSeconds(1));
        worker.start();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (f.block != null) f.block.countDown();
        if (worker != null) worker.stop();
        f.chatCleanup();
        f.cleanup();
    }

    void expireOwner() {
        f.jdbc.update("UPDATE arte_ai_new_worker_lease SET lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)");
        f.jdbc.update("UPDATE arte_ai_new_work SET lease_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE owner_id IS NOT NULL");
    }

    BigDecimal reserved() {
        return f.jdbc.queryForObject("SELECT reserved_amount FROM arte_ai_new_budget", BigDecimal.class);
    }

    String status(String id) {
        return f.jdbc.queryForObject("SELECT status FROM arte_ai_new_execution WHERE execution_id=?", String.class, id);
    }

    int count(String table) {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition did not become true");
    }

    @Test
    void acceptedBodySurvivesRestartAndContinuesHistoryExactlyOnce() throws Exception {
        var conversation = f.create();
        var accepted = f.submit(conversation, "问题一", "one");
        assertEquals(ExecutionStatus.ACCEPTED, accepted.execution().status());
        assertEquals(0, f.calls.get());
        assertEquals(1, count("arte_ai_new_work"));
        queue.release();
        queue = queue(2, 2, 100);
        assertTrue(queue.acquire());
        wire(queue);
        start();
        var first = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, first.execution().status(), () -> String.valueOf(first.execution().error()));
        var replay = f.submit(conversation, "问题一", "one");
        assertEquals(first.execution().executionId(), replay.execution().executionId());
        var second = f.finished(conversation, f.submit(f.current(conversation), "问题二", "two").turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, second.execution().status());
        assertEquals(2, f.calls.get());
        assertTrue(f.requests.getLast().contains("answer-1"));
        assertEquals(2, f.service.history(f.http, TENANT, WORKSPACE, conversation.conversationId(), Long.MAX_VALUE, 10).size());
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
    }

    @Test
    void unDispatchedRunningAttemptRecoversAndOldLeaseCannotDispatch() throws Exception {
        var conversation = f.create();
        var accepted = f.submit(conversation, "恢复问题", "one");
        var old = queue.claim().orElseThrow();
        var fenced = queue.fenced(old);
        assertTrue(fenced.start(f.scope, old.execution().executionId()));
        var standby = queue(2, 2, 100);
        assertFalse(standby.acquire());
        expireOwner();
        assertTrue(standby.acquire());
        standby.recover();
        assertEquals("ACCEPTED", status(accepted.execution().executionId()));
        assertThrows(BaseException.class, () -> fenced.markDispatched(f.scope, old.execution().executionId()));
        assertThrows(BaseException.class, () -> fenced.finish(f.scope, old.execution().executionId(), ExecutionStatus.CANCELLED, null,
                com.arte.base.model.execution.ExecutionError.of(com.arte.base.model.error.CommonErrorCode.INTERRUPTED, "old-owner", false,
                        com.arte.base.model.execution.SideEffectStatus.NONE, com.arte.base.model.execution.ResultCertainty.CONFIRMED, null)));
        assertEquals("ACCEPTED", status(accepted.execution().executionId()));
        queue = standby;
        wire(queue);
        start();
        var done = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, done.execution().status(), () -> String.valueOf(done.execution().error()));
        assertEquals(old.execution().attemptId(), done.execution().attemptId());
        assertEquals(1, f.calls.get());
        assertEquals(1, count("arte_ai_new_execution"));
        assertEquals(5, count("arte_ai_new_event"));
    }

    @Test
    void dispatchedLostAttemptIsUnknownRetainsBudgetAndNeverReplays() throws Exception {
        var conversation = f.create();
        var accepted = f.submit(conversation, "不可重发", "one");
        var old = queue.claim().orElseThrow();
        var fenced = queue.fenced(old);
        assertTrue(fenced.start(f.scope, old.execution().executionId()));
        fenced.markDispatched(f.scope, old.execution().executionId());
        expireOwner();
        queue = queue(2, 2, 100);
        assertTrue(queue.acquire());
        queue.recover();
        assertEquals("OUTCOME_UNKNOWN", status(accepted.execution().executionId()));
        assertEquals("PENDING_RECONCILIATION", f.jdbc.queryForObject("SELECT budget_status FROM arte_ai_new_execution", String.class));
        assertEquals(0, reserved().compareTo(BigDecimal.ONE));
        assertFalse(fenced.start(f.scope, old.execution().executionId()));
        wire(queue);
        start();
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, f.finished(conversation, accepted.turn().turnId()).execution().status());
        assertFalse(queue.hasReady());
        assertEquals(0, f.calls.get());
    }

    @Test
    void durableCapacityRejectsNewWorkButAllowsIdempotentReplay() {
        queue.release();
        queue = queue(1, 1, 100);
        assertTrue(queue.acquire());
        wire(queue);
        var firstConversation = f.create();
        var first = f.submit(firstConversation, "first", "one");
        f.submit(f.create(), "second", "two");
        assertThrows(BaseException.class, () -> f.submit(f.create(), "third", "three"));
        assertEquals(first.execution().executionId(), f.submit(firstConversation, "first", "one").execution().executionId());
        assertEquals(2, count("arte_ai_new_execution"));
        assertEquals(2, count("arte_ai_new_work"));
        assertEquals(0, reserved().compareTo(BigDecimal.valueOf(2)));
        assertEquals(0, f.calls.get());
    }

    @Test
    void failedQueueInsertRollsBackExecutionEventsAndBudgetTogether() {
        f.jdbc.execute("ALTER TABLE arte_ai_new_work ADD CONSTRAINT reject_work CHECK (1=0)");
        var conversation = f.create();
        assertThrows(RuntimeException.class, () -> f.submit(conversation, "atomic", "one"));
        assertEquals(0, count("arte_ai_new_execution"));
        assertEquals(0, count("arte_ai_new_event"));
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
        f.jdbc.execute("ALTER TABLE arte_ai_new_work DROP CONSTRAINT reject_work");
        f.submit(conversation, "atomic", "one");
        assertEquals(1, count("arte_ai_new_work"));
        assertEquals(1, count("arte_ai_new_execution"));
    }

    @Test
    void queuedCancellationPersistsReleasesBudgetAndNeverCallsProvider() throws Exception {
        var conversation = f.create();
        var accepted = f.submit(conversation, "cancel", "one");
        assertEquals(CancellationStatus.CANCELLED, f.service.cancel(f.http, TENANT, WORKSPACE,
                conversation.conversationId(), accepted.turn().turnId()));
        assertTrue(f.jdbc.queryForObject("SELECT cancel_requested FROM arte_ai_new_work", Boolean.class));
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
        start();
        var cancelled = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.CANCELLED, cancelled.execution().status());
        assertFalse(cancelled.turn().occupiesConversationSlot());
        assertEquals(0, f.calls.get());
    }

    @Test
    void runningCancellationWaitsForRealExitAndKeepsUncertainCost() throws Exception {
        f.block = new CountDownLatch(1);
        var stopObserved = new CountDownLatch(1);
        f.provider = new CompatibleChatProviderAdapter((connection, body, checkpoint) -> {
            try (var stop = checkpoint.onStop(stopObserved::countDown)) {
                f.calls.incrementAndGet();
                assertTrue(f.block.await(3, TimeUnit.SECONDS));
                return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"answer\"}}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }, "test", BigDecimal.ZERO, BigDecimal.ZERO, new BudgetQuote(BigDecimal.ONE, "USD"), 16384, 10);
        wire(queue);
        var conversation = f.create();
        var accepted = f.submit(conversation, "cancel running", "one");
        start();
        await(() -> f.calls.get() == 1);
        assertEquals(CancellationStatus.CANCELLING, f.service.cancel(f.http, TENANT, WORKSPACE,
                conversation.conversationId(), accepted.turn().turnId()));
        assertEquals("RUNNING", status(accepted.execution().executionId()));
        // 确保消费者已经观察取消，再模拟供应商读取实际退出。
        assertTrue(stopObserved.await(2, TimeUnit.SECONDS));
        f.block.countDown();
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, f.finished(conversation, accepted.turn().turnId()).execution().status());
        assertEquals(0, reserved().compareTo(BigDecimal.ONE));
    }

    @Test
    void persistentRateWindowDoesNotResetWhenOwnerRestarts() {
        queue.release();
        queue = queue(2, 2, 1);
        assertTrue(queue.acquire());
        wire(queue);
        f.submit(f.create(), "first", "one");
        f.submit(f.create(), "second", "two");
        assertTrue(queue.claim().isPresent());
        assertTrue(queue.claim().isEmpty());
        queue.release();
        queue = queue(2, 2, 1);
        assertTrue(queue.acquire());
        queue.recover();
        assertTrue(queue.claim().isEmpty());
        f.jdbc.update("UPDATE arte_ai_new_worker_lease SET rate_until=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)");
        assertTrue(queue.claim().isPresent());
        assertEquals(1, f.jdbc.queryForObject("SELECT starts_count FROM arte_ai_new_worker_lease", Integer.class));
    }

    @Test
    void expiredQueuedWorkRefundsWithoutDispatch() {
        var accepted = f.submit(f.create(), "expired", "one");
        f.jdbc.update("UPDATE arte_ai_new_work SET deadline_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP)");
        queue.recover();
        assertEquals("TIMED_OUT", status(accepted.execution().executionId()));
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
        assertFalse(queue.hasReady());
        assertEquals(0, f.calls.get());
    }

    @Test
    void changedPersistedTextFailsFingerprintBeforeDispatch() throws Exception {
        var conversation = f.create();
        var accepted = f.submit(conversation, "original-text", "one");
        f.jdbc.update("UPDATE arte_ai_new_work SET work_json=REPLACE(work_json,'original-text','tampered-text')");
        start();
        assertEquals(ExecutionStatus.FAILED, f.finished(conversation, accepted.turn().turnId()).execution().status());
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
        assertEquals(0, f.calls.get());
    }

    @Test
    void malformedQueuedBodyCannotStarveFollowingWork() throws Exception {
        var invalid = f.submit(f.create(), "invalid", "one");
        var conversation = f.create();
        var valid = f.submit(conversation, "valid", "two");
        f.jdbc.update("UPDATE arte_ai_new_work SET work_json='{}' WHERE execution_id=?", invalid.execution().executionId());
        start();
        assertEquals(ExecutionStatus.SUCCEEDED, f.finished(conversation, valid.turn().turnId()).execution().status());
        assertEquals("FAILED", status(invalid.execution().executionId()));
        assertEquals(1, f.calls.get());
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
    }

    @Test
    void revokedPermissionAfterAcceptanceStopsRecoveredDispatch() throws Exception {
        var accepted = f.submit(f.create(), "permission", "one");
        f.jdbc.update("UPDATE arte_security_connection SET enabled=FALSE");
        start();
        await(() -> status(accepted.execution().executionId()).equals("FAILED"));
        assertEquals(0, reserved().compareTo(BigDecimal.ZERO));
        assertEquals(0, f.calls.get());
    }

    @Test
    void legacyExecutionsWithoutRecoverableBodyRefundOnlyWhenUnDispatched() {
        var unsent = f.submit(f.create(), "legacy-unsent", "one");
        var sent = f.submit(f.create(), "legacy-sent", "two");
        f.jdbc.update("DELETE FROM arte_ai_new_work");
        assertTrue(f.executions.start(f.scope, sent.execution().executionId()));
        f.executions.markDispatched(f.scope, sent.execution().executionId());
        queue.recover();
        assertEquals("INTERRUPTED", status(unsent.execution().executionId()));
        assertEquals("OUTCOME_UNKNOWN", status(sent.execution().executionId()));
        assertEquals(0, reserved().compareTo(BigDecimal.ONE));
        assertEquals(0, f.calls.get());
    }

    @Test
    void drainingFinishesActiveWorkAndLeavesQueuedWorkForNextOwner() throws Exception {
        f.block = new CountDownLatch(1);
        var firstConversation = f.create();
        var first = f.submit(firstConversation, "first", "one");
        var secondConversation = f.create();
        var second = f.submit(secondConversation, "second", "two");
        worker = new DurableModelWorker(queue, f.coordinator, f.tasks, f.admission, Telemetry.disabled(), 1,
                Duration.ofMillis(50), Duration.ofSeconds(3));
        worker.start();
        await(() -> f.calls.get() == 1);
        var stops = Executors.newSingleThreadExecutor();
        try {
            var stopped = stops.submit(() -> worker.stop());
            await(() -> Boolean.TRUE.equals(f.jdbc.queryForObject("SELECT draining FROM arte_ai_new_worker_lease", Boolean.class)));
            assertThrows(BaseException.class, () -> f.submit(f.create(), "during-drain", "three"));
            f.block.countDown();
            stopped.get(4, TimeUnit.SECONDS);
            assertEquals("SUCCEEDED", status(first.execution().executionId()));
            assertEquals("ACCEPTED", status(second.execution().executionId()));
            assertEquals(1, f.calls.get());
            f.tasks = new com.arte.base.execution.BoundedTaskExecutor(2, 2, f.clock);
            queue = queue(2, 2, 100);
            assertTrue(queue.acquire());
            wire(queue);
            start();
            assertEquals(ExecutionStatus.SUCCEEDED, f.finished(secondConversation, second.turn().turnId()).execution().status());
            assertEquals(2, f.calls.get());
        } finally {
            f.block.countDown();
            stops.shutdownNow();
        }
    }
}
