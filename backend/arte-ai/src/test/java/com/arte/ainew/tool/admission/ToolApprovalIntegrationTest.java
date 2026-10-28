package com.arte.ainew.tool.admission;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisToolAdmissionStore;
import com.arte.ainew.persistence.mybatis.MybatisToolApprovalStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static org.junit.Assert.*;

/**
 * 真实 JDBC／MyBatis／同库事务：固定摘要、授权、竞争与回滚；无业务库、模型或写工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public class ToolApprovalIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final ToolExecutionPolicy LIMITS = new ToolExecutionPolicy(Duration.ofMinutes(2), 3, Duration.ZERO,
            100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE);

    private static final class Rig implements AutoCloseable {
        final ToolAdmissionFixture f;
        final MybatisExecutionPersistence executions;
        final MybatisToolAdmissionStore admissions;
        final MybatisToolApprovalStore approvals;
        final ToolExecutionSnapshot snapshot;
        final ToolApproval.Plan plan;
        final AtomicInteger guards = new AtomicInteger();

        Rig() throws Exception {
            this(false, false, false);
        }

        Rig(boolean independent, boolean shortWindow, boolean classified) throws Exception {
            f = new ToolAdmissionFixture(LIMITS, new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY,
                    false, ToolRiskProfile.Idempotency.NONE, Set.of("resource:read"), Set.of()), LIMITS);
            var policy = new NewAiSchedulingProperties(classified, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    null, null, null, "a".repeat(64));
            executions = new MybatisExecutionPersistence(f.source, f.codec, f.io, f.wakeups::incrementAndGet,
                    f.wakeups::incrementAndGet, policy, classified);
            admissions = new MybatisToolAdmissionStore(f.source, f.codec, f.io, executions, f.bindings, f.catalog);
            approvals = new MybatisToolApprovalStore(f.source, f.codec, f.io, executions, f.bindings);
            var original = f.context("approval-execution", "approval-key");
            var context = new ExecutionContext(original.executionId(), original.traceId(), original.authorization(),
                    Instant.now().plusSeconds(120), null, null, original.releaseRef(), original.idempotencyKey());
            var preview = f.service.preview(f.input(), context).block(WAIT);
            var mode = independent ? ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL : ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION;
            var p = preview.policy();
            snapshot = new ToolExecutionSnapshot(preview.input(), preview.request(), preview.binding(),
                    new ToolExecutionPolicy(p.timeout(), p.maxAttempts(), p.retryBackoff(), p.maxInputBytes(), p.maxOutputBytes(), mode),
                    preview.schemas(), preview.receivedAt());
            plan = new ToolApproval.Plan(mode, independent ? Set.of("reviewer") : Set.of(),
                    shortWindow ? Instant.now().plusSeconds(2) : snapshot.request().options().deadline().minusSeconds(30), Duration.ofSeconds(30));
        }

        void authorize(ToolExecutionSnapshot value) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(f.source));
            guards.incrementAndGet();
            if (!f.jdbc.queryForObject("SELECT enabled FROM tool_test_permission WHERE subject_id='subject' FOR UPDATE", Boolean.class))
                throw new AccessDeniedException("Test current qualification revoked");
            assertEquals(f.owner, value.binding().owner());
        }

        ToolApproval waiting() {
            assertEquals(APPLIED, admissions.awaitApproval(snapshot, plan, this::authorize).block(WAIT).code());
            return approvals.find(f.owner, snapshot.request().context().executionId()).block(WAIT);
        }

        ToolApprovalCommand command(ToolApproval approval, ToolApprovalCommand.Decision decision, String key) {
            return new ToolApprovalCommand(f.owner, approval.invocationId(), approval.approvalId(), 0, approval.subjectDigest(), decision, key);
        }

        StoreOutcome<ToolApprovalReceipt> decide(ToolApprovalCommand command) {
            return decide(command, f.owner);
        }

        StoreOutcome<ToolApprovalReceipt> decide(ToolApprovalCommand command, ExecutionOwner actor) {
            return approvals.decide(command, actor, (approval, value) -> authorize(value)).block(WAIT);
        }

        Invocation invocation() {
            return executions.find(f.owner, snapshot.request().context().executionId()).block(WAIT);
        }

        int dispatches() {
            return f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class);
        }

        ToolApproval approval() {
            return approvals.find(f.owner, snapshot.request().context().executionId()).block(WAIT);
        }

        @Override
        public void close() throws Exception {
            f.close();
        }
    }

    @Test
    public void waitingIsDurableNonterminalAndHasNoExecutableResources() throws Exception {
        try (var r = new Rig(false, false, true)) {
            var approval = r.waiting();
            assertEquals(ToolApproval.State.PENDING, approval.state());
            assertEquals(Invocation.State.WAITING_APPROVAL, r.invocation().state());
            assertFalse(r.invocation().state().terminal());
            assertEquals(1, r.invocation().version());
            assertNull(r.invocation().activeAttemptId());
            assertEquals(0, r.dispatches());
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
            assertEquals(0, r.f.count("arte_ai_queue_capacity"));
            assertEquals(2, r.f.count("arte_ai_event"));
            assertEquals(2, r.f.wakeups.get());
            assertEquals(r.snapshot, r.f.codec.decode(r.f.codec.encode(r.snapshot), ToolExecutionSnapshot.class));
            assertEquals(approval, r.f.codec.decode(r.f.codec.encode(approval), ToolApproval.class));
            assertEquals(INVALID_STATE, r.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                    new ExecutionCommands.Version(r.f.owner, approval.invocationId(), 1), "forbidden-attempt", "worker", Duration.ofSeconds(5))).block(WAIT).code());
            assertEquals(0, r.f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void approvalQueuesOnceAndReplayReturnsOriginalReceiptWithoutChangingIdentityOrDeadline() throws Exception {
        try (var r = new Rig(false, false, true)) {
            var approval = r.waiting();
            var command = r.command(approval, ToolApprovalCommand.Decision.APPROVE, "approve");
            var first = r.decide(command);
            assertEquals(APPLIED, first.code());
            assertEquals(ToolApproval.State.APPROVED, first.value().approval().state());
            assertEquals(Invocation.State.QUEUED, first.value().invocation().state());
            assertEquals(2, first.value().invocation().version());
            assertEquals(r.snapshot.request(), first.value().invocation().request());
            assertEquals(1, r.dispatches());
            assertEquals(4, r.f.count("arte_ai_queue_capacity"));
            assertEquals(Long.valueOf(first.value().invocation().updatedAt().toEpochMilli()),
                    r.f.jdbc.queryForObject("SELECT queued_at FROM arte_ai_outbox WHERE kind='DISPATCH'", Long.class));
            int wakeups = r.f.wakeups.get(), events = r.f.count("arte_ai_event");
            assertEquals(REPLAYED, r.decide(command).code());
            assertEquals(first.value(), r.decide(command).value());
            assertEquals(events, r.f.count("arte_ai_event"));
            assertEquals(wakeups, r.f.wakeups.get());
            assertEquals(1, r.dispatches());
            assertEquals(IDEMPOTENCY_CONFLICT, r.decide(r.command(approval, ToolApprovalCommand.Decision.REJECT, "approve")).code());
            assertFalse(r.decide(r.command(approval, ToolApprovalCommand.Decision.REJECT, "other-key")).successful());
        }
    }

    @Test
    public void rejectCompletesWithoutAttemptResultBudgetOrDispatch() throws Exception {
        try (var r = new Rig()) {
            var approval = r.waiting();
            var result = r.decide(r.command(approval, ToolApprovalCommand.Decision.REJECT, "reject"));
            assertEquals(APPLIED, result.code());
            assertEquals(ToolApproval.State.REJECTED, r.approval().state());
            assertEquals(Invocation.State.FAILED, r.invocation().state());
            assertEquals("TOOL_APPROVAL_REJECTED", r.invocation().error().code());
            assertNull(r.invocation().activeAttemptId());
            assertNull(r.invocation().result());
            assertEquals(0, r.dispatches());
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void digestVersionAndCurrentQualificationAreRequiredIncludingReplay() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            assertEquals(VERSION_CONFLICT, r.decide(new ToolApprovalCommand(r.f.owner, a.invocationId(), a.approvalId(),
                    9, a.subjectDigest(), ToolApprovalCommand.Decision.APPROVE, "bad-version")).code());
            assertEquals(IDEMPOTENCY_CONFLICT, r.decide(new ToolApprovalCommand(r.f.owner, a.invocationId(), a.approvalId(),
                    0, "f".repeat(64), ToolApprovalCommand.Decision.APPROVE, "bad-digest")).code());
            var command = r.command(a, ToolApprovalCommand.Decision.APPROVE, "valid");
            r.f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> r.decide(command));
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            r.f.jdbc.update("UPDATE tool_test_permission SET enabled=true");
            assertEquals(APPLIED, r.decide(command).code());
            r.f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> r.decide(command));
            assertEquals(1, r.dispatches());
        }
    }

    @Test
    public void independentApprovalRequiresConfiguredOtherSubjectInSameScopeAndKeepsRequester() throws Exception {
        try (var r = new Rig(true, false, false)) {
            var a = r.waiting();
            var command = r.command(a, ToolApprovalCommand.Decision.APPROVE, "independent");
            assertThrows(AccessDeniedException.class, () -> r.decide(command));
            assertThrows(AccessDeniedException.class, () -> r.decide(command, new ExecutionOwner("tenant", "other-space", "reviewer")));
            assertThrows(AccessDeniedException.class, () -> r.decide(command, new ExecutionOwner("other-tenant", "workspace", "reviewer")));
            assertThrows(AccessDeniedException.class, () -> r.decide(command, new ExecutionOwner("tenant", "workspace", "unlisted")));
            var result = r.decide(command, new ExecutionOwner("tenant", "workspace", "reviewer"));
            assertEquals(APPLIED, result.code());
            assertEquals("reviewer", result.value().approval().decidedBy());
            assertEquals(r.f.owner, ExecutionOwners.from(result.value().invocation().request().context()));
        }
    }

    @Test
    public void requesterConfirmationRejectsAnotherSubject() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            assertThrows(AccessDeniedException.class, () -> r.decide(r.command(a, ToolApprovalCommand.Decision.REJECT, "wrong"),
                    new ExecutionOwner("tenant", "workspace", "reviewer")));
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
        }
    }

    @Test
    public void waitingReplayDoesNotResetExpiryAndImmediateAdmissionCannotBypassApproval() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            assertEquals(REPLAYED, r.admissions.awaitApproval(r.snapshot, r.plan, r::authorize).block(WAIT).code());
            assertEquals(a, r.approval());
            assertEquals(0, r.dispatches());
            assertThrows(IllegalArgumentException.class, () -> r.admissions.accept(r.snapshot, r::authorize).block(WAIT));
            var changed = new ToolApproval.Plan(r.plan.mode(), Set.of(), r.plan.expiresAt().minusSeconds(1), r.plan.executionMargin());
            assertEquals(IDEMPOTENCY_CONFLICT, r.admissions.awaitApproval(r.snapshot, changed, r::authorize).block(WAIT).code());
        }
    }

    @Test
    public void existingCancellationClosesPendingApprovalAtomicallyAndCannotBeApprovedLater() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            var request = new ExecutionControlRequest("cancel-command", a.invocationId(), "cancel-key", ControlReceipt.Command.CANCEL);
            assertTrue(r.executions.requestControl(r.f.owner, request, () -> r.authorize(r.snapshot)).block(WAIT).successful());
            assertEquals(Invocation.State.CANCELLED, r.invocation().state());
            assertEquals(ToolApproval.State.CANCELLED, r.approval().state());
            assertFalse(r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "late")).successful());
            assertEquals(0, r.dispatches());
            assertEquals(REPLAYED, r.executions.requestControl(r.f.owner, request, () -> r.authorize(r.snapshot)).block(WAIT).code());
        }
    }

    @Test
    public void expiryIsDurableIdempotentAndRejectsLateDecision() throws Exception {
        try (var r = new Rig(false, true, false)) {
            var a = r.waiting();
            assertEquals(Integer.valueOf(0), r.approvals.expire(64).block(WAIT));
            waitUntil(r.plan.expiresAt());
            assertEquals(INVALID_STATE, r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "late")).code());
            assertEquals(Integer.valueOf(1), r.approvals.expire(64).block(WAIT));
            assertEquals(ToolApproval.State.EXPIRED, r.approval().state());
            assertEquals(Invocation.State.TIMED_OUT, r.invocation().state());
            assertEquals("TOOL_APPROVAL_EXPIRED", r.invocation().error().code());
            assertEquals(Integer.valueOf(0), r.approvals.expire(64).block(WAIT));
            assertEquals(0, r.dispatches());
        }
    }

    @Test
    public void oppositeConcurrentDecisionsHaveOnlyOneWinner() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var yes = pool.submit(() -> {
                    start.await();
                    return r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "yes"));
                });
                var no = pool.submit(() -> {
                    start.await();
                    return r.decide(r.command(a, ToolApprovalCommand.Decision.REJECT, "no"));
                });
                start.countDown();
                var results = List.of(yes.get(15, TimeUnit.SECONDS), no.get(15, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(StoreOutcome::successful).count());
                assertEquals(r.approval().state() == ToolApproval.State.APPROVED ? 1 : 0, r.dispatches());
            }
        }
    }

    @Test
    public void decisionAndCancellationCannotLeavePendingApprovalOrDuplicateDispatch() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            try (var pool = Executors.newFixedThreadPool(2)) {
                var start = new CountDownLatch(1);
                var yes = pool.submit(() -> {
                    start.await();
                    return r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "yes"));
                });
                var cancel = pool.submit(() -> {
                    start.await();
                    return r.executions.requestControl(r.f.owner,
                            new ExecutionControlRequest("cancel-command", a.invocationId(), "cancel-key", ControlReceipt.Command.CANCEL),
                            () -> r.authorize(r.snapshot)).block(WAIT);
                });
                start.countDown();
                yes.get(15, TimeUnit.SECONDS);
                assertTrue(cancel.get(15, TimeUnit.SECONDS).successful());
                assertEquals(Invocation.State.CANCELLED, r.invocation().state());
                assertNotEquals(ToolApproval.State.PENDING, r.approval().state());
                assertTrue(r.dispatches() <= 1);
                assertEquals(0, r.f.count("arte_ai_attempt"));
            }
        }
    }

    @Test
    public void dispatchInsertFailureRollsBackDecisionQueueEventsAndWakeup() throws Exception {
        try (var r = new Rig(false, false, true)) {
            var a = r.waiting();
            int events = r.f.count("arte_ai_event"), wakeups = r.f.wakeups.get();
            r.f.jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT reject_test_dispatch CHECK(kind <> 'DISPATCH')");
            assertThrows(RuntimeException.class, () -> r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "fail")));
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            assertEquals(Invocation.State.WAITING_APPROVAL, r.invocation().state());
            assertEquals(0, r.f.count("arte_ai_queue_capacity"));
            assertEquals(events, r.f.count("arte_ai_event"));
            assertEquals(wakeups, r.f.wakeups.get());
            r.f.jdbc.execute("ALTER TABLE arte_ai_outbox DROP CONSTRAINT reject_test_dispatch");
            assertEquals(APPLIED, r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "fail")).code());
        }
    }

    @Test
    public void missingApprovalTableRollsBackEntireWaitingAdmissionAndMigrationIsExplicit() throws Exception {
        try (var r = new Rig()) {
            r.f.jdbc.execute("DROP TABLE arte_ai_tool_approval");
            assertThrows(RuntimeException.class, () -> r.admissions.awaitApproval(r.snapshot, r.plan, r::authorize).block(WAIT));
            assertEquals(0, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_tool_execution_snapshot"));
            assertEquals(0, r.f.count("arte_ai_event"));
            assertEquals(0, r.f.count("arte_ai_outbox"));
            assertEquals(0, r.f.wakeups.get());
            new ResourceDatabasePopulator(new ClassPathResource("arte-ai-tool-approval-migration.sql")).populate(r.f.ddl);
            r.waiting();
        }
    }

    @Test
    public void corruptedSubjectAndBindingDisableCannotBeApproved() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            r.f.disable();
            assertEquals(INVALID_STATE, r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "disabled")).code());
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET request_digest=?", "f".repeat(64));
            assertThrows(IllegalStateException.class, () -> r.decide(r.command(a, ToolApprovalCommand.Decision.REJECT, "corrupt")));
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            assertEquals(0, r.dispatches());
        }
    }

    @Test
    public void dispatchRechecksApprovalAndFixedParametersBeforeCreatingAttempt() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            assertEquals(APPLIED, r.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "yes")).code());
            r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET request_digest=?", "f".repeat(64));
            assertThrows(IllegalStateException.class, () -> r.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                    new ExecutionCommands.Version(r.f.owner, a.invocationId(), 2), "attempt", "worker", Duration.ofSeconds(5))).block(WAIT));
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(Invocation.State.QUEUED, r.invocation().state());
        }
    }

    @Test
    public void planCannotExtendDeadlineUseNoApproverOrAllowIndependentSelfApproval() throws Exception {
        try (var r = new Rig()) {
            assertThrows(IllegalArgumentException.class, () -> new ToolApproval.Plan(ToolExecutionPolicy.ApprovalMode.INDEPENDENT_APPROVAL,
                    Set.of(), r.plan.expiresAt(), Duration.ofSeconds(30)));
            assertThrows(IllegalArgumentException.class, () -> new ToolApproval.Plan(r.plan.mode(), Set.of(), r.plan.expiresAt(), Duration.ofSeconds(29)));
            assertThrows(IllegalArgumentException.class, () -> ToolApproval.pending(r.snapshot,
                    new ToolApproval.Plan(r.plan.mode(), Set.of(), r.snapshot.request().options().deadline(), Duration.ofSeconds(30))));
            assertThrows(IllegalArgumentException.class, () -> new ToolApproval.Plan(ToolExecutionPolicy.ApprovalMode.NONE,
                    Set.of(), r.plan.expiresAt(), Duration.ofSeconds(30)));
            assertFalse(Invocation.State.WAITING_APPROVAL.canTransitionTo(Invocation.State.RUNNING));
            assertFalse(Invocation.State.WAITING_APPROVAL.canTransitionTo(Invocation.State.SUCCEEDED));
        }
    }

    @Test
    public void twoRecoveryInstancesExpireOnlyOnceAndOldReceiptSurvivesStoreRecreation() throws Exception {
        try (var r = new Rig(false, true, false)) {
            var a = r.waiting();
            var other = new MybatisToolApprovalStore(r.f.source, r.f.codec, r.f.io, r.executions, r.f.bindings);
            waitUntil(r.plan.expiresAt());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var one = pool.submit(() -> r.approvals.expire(64).block(WAIT));
                var two = pool.submit(() -> other.expire(64).block(WAIT));
                assertEquals(1, one.get(15, TimeUnit.SECONDS) + two.get(15, TimeUnit.SECONDS));
            }
            assertEquals(ToolApproval.State.EXPIRED, other.find(r.f.owner, a.invocationId()).block(WAIT).state());
            assertEquals(0, r.dispatches());
        }
        try (var r = new Rig()) {
            var a = r.waiting();
            var command = r.command(a, ToolApprovalCommand.Decision.REJECT, "durable-rejection");
            var original = r.decide(command);
            var other = new MybatisToolApprovalStore(r.f.source, r.f.codec, r.f.io, r.executions, r.f.bindings);
            var replay = other.decide(command, r.f.owner, (approval, value) -> r.authorize(value)).block(WAIT);
            assertEquals(REPLAYED, replay.code());
            assertEquals(original.value(), replay.value());
        }
    }

    @Test
    public void ordinaryS2AdmissionStillWorksBeforeInstallingApprovalTable() throws Exception {
        try (var r = new Rig()) {
            r.f.jdbc.execute("DROP TABLE arte_ai_tool_approval");
            var accepted = r.f.service.submit(r.f.input(), r.f.context("ordinary", "normal-key")).block(WAIT);
            assertEquals("ordinary", accepted.executionId());
            assertEquals(accepted, r.f.service.submit(r.f.input(), r.f.context("replay", "normal-key")).block(WAIT));
            assertEquals(1, r.dispatches());
        }
    }

    @Test
    public void approvalSnapshotVersionCannotBeDowngradedToV1AndOriginalV1RemainsReadable() throws Exception {
        try (var r = new Rig()) {
            var json = r.f.codec.encode(r.snapshot);
            assertTrue(json.contains("\"schemaVersion\":2"));
            assertThrows(IllegalArgumentException.class, () -> r.f.codec.decode(json.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), ToolExecutionSnapshot.class));
            var plain = r.f.service.preview(r.f.input(), r.f.context("ordinary", "normal-key")).block(WAIT);
            var old = r.f.codec.encode(plain);
            assertTrue(old.contains("\"schemaVersion\":1"));
            assertEquals(plain, r.f.codec.decode(old, ToolExecutionSnapshot.class));
            assertThrows(IllegalArgumentException.class, () -> r.f.codec.decode(old.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), ToolExecutionSnapshot.class));
        }
    }

    @Test
    public void cancellationWriteFailureRollsBackApprovalInvocationControlAndEvents() throws Exception {
        try (var r = new Rig()) {
            var a = r.waiting();
            int events = r.f.count("arte_ai_event"), wakeups = r.f.wakeups.get();
            r.f.jdbc.execute("ALTER TABLE arte_ai_tool_approval ADD CONSTRAINT reject_test_close CHECK(state='PENDING')");
            assertThrows(RuntimeException.class, () -> r.executions.requestControl(r.f.owner,
                    new ExecutionControlRequest("cancel", a.invocationId(), "cancel", ControlReceipt.Command.CANCEL),
                    () -> r.authorize(r.snapshot)).block(WAIT));
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            assertEquals(Invocation.State.WAITING_APPROVAL, r.invocation().state());
            assertFalse(r.executions.cancellationRequested(r.f.owner, a.invocationId()).block(WAIT));
            assertEquals(events, r.f.count("arte_ai_event"));
            assertEquals(wakeups, r.f.wakeups.get());
        }
    }

    @Test
    public void authorizationWaitConsumesApprovalWindowInsideDecisionTransaction() throws Exception {
        try (var r = new Rig(false, true, false)) {
            var a = r.waiting();
            var result = r.approvals.decide(r.command(a, ToolApprovalCommand.Decision.APPROVE, "waited"), r.f.owner,
                    (approval, snapshot) -> {
                        r.authorize(snapshot);
                        try {
                            waitUntil(approval.plan().expiresAt());
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }).block(WAIT);
            assertEquals(INVALID_STATE, result.code());
            assertEquals(ToolApproval.State.PENDING, r.approval().state());
            assertEquals(Invocation.State.WAITING_APPROVAL, r.invocation().state());
            assertEquals(0, r.dispatches());
        }
    }

    @Test
    public void baselineAndIncrementalApprovalDdlMatch() throws Exception {
        String full = new ClassPathResource("arte-ai-new-ddl-mysql.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String incremental = new ClassPathResource("arte-ai-tool-approval-migration.sql").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int start = full.indexOf("create table arte_ai_tool_approval"), end = full.indexOf(';', start) + 1;
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(), full.substring(start, end).trim());
    }

    private static void waitUntil(Instant at) throws InterruptedException {
        long millis = Duration.between(Instant.now(), at).toMillis() + 20;
        if (millis > 0) Thread.sleep(millis);
    }
}
