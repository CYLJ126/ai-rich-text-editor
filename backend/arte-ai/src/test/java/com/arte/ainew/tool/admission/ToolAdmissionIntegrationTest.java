package com.arte.ainew.tool.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.persistence.mybatis.MybatisToolAdmissionStore;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolBindingSettings;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;

/**
 * 真实 MyBatis／物理事务验证，不调用业务工具，不依赖平台库、Redis 或外部模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public class ToolAdmissionIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    public void firstAcceptancePersistsOneFixedSnapshotAndAtomicDispatchWithoutAttemptOrBudget() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var receipt = f.service.submit(f.input(), f.context("first", "key")).block(WAIT);
            assertEquals("first", receipt.executionId());
            var accepted = f.store.findAccepted(f.owner, f.binding.bindingId(), "key").block(WAIT);
            var snapshot = accepted.snapshot();
            assertEquals(Invocation.State.ACCEPTED, accepted.invocation().state());
            assertEquals(0, accepted.invocation().version());
            assertNull(accepted.invocation().contextSnapshotId());
            assertEquals(f.v1.revision().tool().definition(), snapshot.request().input().tool());
            assertEquals(object(), snapshot.binding().effectiveConfiguration());
            assertEquals(Duration.ofSeconds(5), Duration.between(snapshot.receivedAt(), snapshot.request().options().deadline()));
            assertEquals(3, snapshot.request().options().maxAttempts());
            assertEquals(1, snapshot.schemas().size());
            assertEquals(snapshot, f.store.snapshot(f.owner, "first").block(WAIT));
            assertEquals(snapshot, f.codec.decode(f.codec.encode(snapshot), ToolExecutionSnapshot.class));
            assertCounts(f, 1);
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.count("arte_ai_reservation"));
            assertEquals(2, f.wakeups.get());
            assertEquals(1, f.guards.get());
        }
    }

    @Test
    public void replayWithNewIdentityReturnsOriginalReceiptAndNoAdditionalDispatch() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var first = f.service.submit(f.input(), f.context("first", "same")).block(WAIT);
            var second = f.service.submit(f.input(), f.context("other", "same")).block(WAIT);
            assertEquals(first, second);
            assertEquals(2, f.authorizations.get());
            assertEquals(2, f.guards.get());
            assertEquals(2, f.wakeups.get());
            assertCounts(f, 1);
        }
    }

    @Test
    public void changedIntentConflictsWithoutOverwritingOriginal() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "same")).block(WAIT);
            var changed = new ToolAdmissionInput(f.binding.bindingId(), object("text", text("changed")), null, null, null);
            expectCode(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> f.service.submit(changed, f.context("other", "same")).block(WAIT));
            var limited = new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(2), 1, 100L);
            expectCode(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> f.service.submit(limited, f.context("third", "same")).block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void replayPinsOldVersionAndConfigurationWhileNewKeyUsesCurrentPlan() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var originalInput = f.input();
            var first = f.service.submit(originalInput, f.context("old", "same")).block(WAIT);
            var old = f.store.snapshot(f.owner, "old").block(WAIT);
            f.changeConfiguration();
            f.publish(f.v2, f.v1);
            assertEquals(first, f.service.submit(originalInput, f.context("replay", "same")).block(WAIT));
            assertEquals(old, f.store.snapshot(f.owner, "old").block(WAIT));
            f.service.submit(f.input(), f.context("new", "new-key")).block(WAIT);
            var next = f.store.snapshot(f.owner, "new").block(WAIT);
            assertEquals(f.v2.revision().tool().definition(), next.request().input().tool());
            assertEquals(object("text", text("updated-config")), next.binding().effectiveConfiguration());
            assertCounts(f, 2);
        }
    }

    @Test
    public void currentBindingDisableDeniesNewAndReplay() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var input = f.input();
            f.service.submit(input, f.context("first", "same")).block(WAIT);
            f.disable();
            expectCode(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE, () -> f.service.submit(input, f.context("replay", "same")).block(WAIT));
            expectCode(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE, () -> f.service.submit(input, f.context("new", "new")).block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void currentHostPermissionRevocationDeniesReplay() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "same")).block(WAIT);
            f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> f.service.submit(f.input(), f.context("replay", "same")).block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void permissionRevokedAfterPreflightRollsBackWithinPhysicalTransaction() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.authorizationHook = ignored -> f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> f.service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertEquals(1, f.guards.get());
            assertCounts(f, 0);
            assertEquals(0, f.wakeups.get());
        }
    }

    @Test
    public void configurationChangedAfterPreflightRejectsStalePlan() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.authorizationHook = ignored -> f.changeConfiguration();
            expectCode(ResultCodeEnum.AI_TOOL_PLAN_CHANGED, () -> f.service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void compatiblePublicationAfterPreflightRejectsStaleSelection() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.authorizationHook = ignored -> f.publish(f.v2, f.v1);
            expectCode(ResultCodeEnum.AI_TOOL_PLAN_CHANGED, () -> f.service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void bindingDisabledAfterPreflightCannotCommit() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.authorizationHook = ignored -> f.disable();
            expectCode(ResultCodeEnum.AI_INVALID_STATE, () -> f.service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void returnedAuthorizationCannotChangeOwnerOrExpandScope() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.permissions = old -> new ExecutionAuthorization(old.principal(), old.tenantId(), "other-workspace", old.scopes(), old.grantRef());
            expectCode(ResultCodeEnum.AI_TOOL_POLICY_DENIED, () -> f.service.submit(f.input(), f.context("owner", "owner")).block(WAIT));
            f.permissions = old -> new ExecutionAuthorization(old.principal(), old.tenantId(), old.workspaceId(), Set.of("ai:invoke", "resource:read", "extra"), old.grantRef());
            expectCode(ResultCodeEnum.AI_TOOL_POLICY_DENIED, () -> f.service.submit(f.input(), f.context("scope", "scope")).block(WAIT));
            f.permissions = old -> new ExecutionAuthorization(old.principal(), old.tenantId(), old.workspaceId(), Set.of("ai:invoke"), old.grantRef());
            expectCode(ResultCodeEnum.AI_TOOL_POLICY_DENIED, () -> f.service.submit(f.input(), f.context("missing", "missing")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void emptyHostAuthorizationFailsClosed() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var service = f.service(f.store, (input, context, plan) -> Mono.empty(), f.clock);
            expectCode(ResultCodeEnum.AI_TOOL_POLICY_DENIED, () -> service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void invalidArgumentsAreRejectedBeforeHostAuthorizationAndPersistence() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var invalid = new ToolAdmissionInput(f.binding.bindingId(), object("text", object()), null, null, null);
            expectCode(ResultCodeEnum.AI_TOOL_INVALID_REQUEST, () -> f.service.submit(invalid, f.context("first", "key")).block(WAIT));
            assertEquals(0, f.authorizations.get());
            assertCounts(f, 0);
        }
    }

    @Test
    public void completeTightenedPolicyIncludingInputAndBackoffSurvivesDurableSnapshot() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var limits = new ToolExecutionPolicy(Duration.ofSeconds(2), 1, Duration.ofSeconds(1), 128, 100, ToolExecutionPolicy.ApprovalMode.NONE);
            var service = new ToolAdmissionService(f.bindingService, f.catalog, f.store, f.access, new ToolSchemaValidator(), limits, f.metadata, f.clock);
            service.submit(f.input(), f.context("first", "key")).block(WAIT);
            var snapshot = f.store.snapshot(f.owner, "first").block(WAIT);
            assertEquals(limits, snapshot.policy());
            assertEquals(Duration.ZERO, snapshot.binding().effectivePolicy().retryBackoff());
            assertEquals(1, snapshot.request().options().maxAttempts());
            assertEquals(100, snapshot.request().options().maxOutputBytes());
            assertEquals(snapshot.receivedAt().plusSeconds(2), snapshot.request().options().deadline());
            assertEquals(snapshot, f.codec.decode(f.codec.encode(snapshot), ToolExecutionSnapshot.class));
        }
    }

    @Test
    public void requestedLimitsOnlyTightenAndDoNotResetAtPersistence() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var input = new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(2), 1, 100L);
            f.service.submit(input, f.context("first", "key")).block(WAIT);
            var snapshot = f.store.snapshot(f.owner, "first").block(WAIT);
            assertEquals(snapshot.receivedAt().plusSeconds(2), snapshot.request().options().deadline());
            assertEquals(1, snapshot.request().options().maxAttempts());
            assertEquals(100, snapshot.request().options().maxOutputBytes());
        }
    }

    @Test
    public void preflightWaitConsumesTotalDeadline() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(f.clock.instant());
            Clock advancing = new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return now.get();
                }
            };
            var service = f.service(f.store, f.access, advancing);
            f.authorizationHook = ignored -> now.updateAndGet(value -> value.plusSeconds(6));
            expectCode(ResultCodeEnum.AI_DEADLINE_EXCEEDED, () -> service.submit(f.input(), f.context("first", "key")).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void schedulerQueueWaitDoesNotMoveAdmissionDeadlineStart() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(f.clock.instant());
            Clock advancing = new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return now.get();
                }
            };
            var occupied = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            Runnable occupy = () -> {
                occupied.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                }
            };
            f.metadata.schedule(occupy);
            f.metadata.schedule(occupy);
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            try {
                var pending = f.service(f.store, f.access, advancing).submit(f.input(), f.context("queued", "key")).toFuture();
                now.updateAndGet(value -> value.plusSeconds(6));
                release.countDown();
                var error = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                assertEquals(ResultCodeEnum.AI_DEADLINE_EXCEEDED, ((AdmissionException) error.getCause()).getResultCode());
                assertCounts(f, 0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void expiredCallerDeadlineCreatesNoInvocation() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var base = f.context("first", "key");
            var expired = new ExecutionContext(base.executionId(), base.traceId(), base.authorization(), f.clock.instant().minusSeconds(1), null, null, base.releaseRef(), base.idempotencyKey());
            expectCode(ResultCodeEnum.AI_DEADLINE_EXCEEDED, () -> f.service.submit(f.input(), expired).block(WAIT));
            assertCounts(f, 0);
        }
    }

    @Test
    public void historicalReplayDoesNotExtendOriginalDeadline() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var first = f.service.submit(f.input(), f.context("first", "key")).block(WAIT);
            var old = f.store.snapshot(f.owner, "first").block(WAIT);
            var later = Clock.offset(f.clock, Duration.ofSeconds(60));
            var base = f.context("later", "key");
            var request = new ExecutionContext(base.executionId(), base.traceId(), base.authorization(), later.instant().plusSeconds(30), null, null, base.releaseRef(), base.idempotencyKey());
            assertEquals(first, f.service(f.store, f.access, later).submit(f.input(), request).block(WAIT));
            assertEquals(old, f.store.snapshot(f.owner, "first").block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void concurrentServicesReturnOneDurableAcceptance() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var barrier = new CyclicBarrier(2);
            f.authorizationHook = ignored -> await(barrier);
            var secondStore = new MybatisToolAdmissionStore(f.source, f.codec, f.io, f.executions, f.bindings, f.catalog);
            var second = f.service(secondStore, f.access, f.clock);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> f.service.submit(f.input(), f.context("one", "same")).block(WAIT));
                var b = executor.submit(() -> second.submit(f.input(), f.context("two", "same")).block(WAIT));
                assertEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            }
            assertCounts(f, 1);
            assertEquals(2, f.wakeups.get());
            assertEquals(3, f.guards.get());
        }
    }

    @Test
    public void snapshotInsertFailureRollsBackInvocationIndexEventOutboxAndWakeup() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.jdbc.execute("ALTER TABLE arte_ai_tool_execution_snapshot ADD CONSTRAINT reject_snapshot CHECK (1=0)");
            assertThrows(RuntimeException.class, () -> f.service.submit(f.input(), f.context("first", "same")).block(WAIT));
            assertCounts(f, 0);
            assertEquals(0, f.wakeups.get());
            f.jdbc.execute("ALTER TABLE arte_ai_tool_execution_snapshot DROP CONSTRAINT reject_snapshot");
            assertEquals("first", f.service.submit(f.input(), f.context("first", "same")).block(WAIT).executionId());
            assertCounts(f, 1);
        }
    }

    @Test
    public void dispatchWriteFailureCannotLeaveSnapshotOrReceipt() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT reject_dispatch CHECK (kind <> 'DISPATCH')");
            assertThrows(RuntimeException.class, () -> f.service.submit(f.input(), f.context("first", "same")).block(WAIT));
            assertCounts(f, 0);
            assertEquals(0, f.wakeups.get());
        }
    }

    @Test
    public void corruptedSnapshotNeverReturnsSuccessfulReplay() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "key")).block(WAIT);
            f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET payload_digest=?", "0".repeat(64));
            assertThrows(IllegalStateException.class, () -> f.service.submit(f.input(), f.context("second", "key")).block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void ownerIsolationAppliesToSnapshotAndReplayLookup() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "key")).block(WAIT);
            var other = new ExecutionOwner(f.owner.tenantId(), "other", f.owner.subjectId());
            assertNull(f.store.snapshot(other, "first").block(WAIT));
            assertNull(f.store.findAccepted(other, f.binding.bindingId(), "key").block(WAIT));
        }
    }

    @Test
    public void separateBindingsCannotBypassExistingCapabilityIdempotencyFence() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "same")).block(WAIT);
            var another = f.createBinding("another", ToolBindingSettings.VersionPolicy.PINNED);
            var input = new ToolAdmissionInput(another.bindingId(), object(), null, null, null);
            expectCode(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> f.service.submit(input, f.context("other", "same")).block(WAIT));
            assertCounts(f, 1);
        }
    }

    @Test
    public void deadlineExhaustedWhileWaitingForCommitCannotPersist() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var now = new java.util.concurrent.atomic.AtomicReference<>(f.clock.instant());
            Clock advancing = new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    return now.get();
                }
            };
            var storage = new MybatisToolAdmissionStore(f.source, f.codec, f.io, f.executions, f.bindings, f.catalog, advancing);
            f.commitHook = ignored -> now.updateAndGet(value -> value.plusSeconds(60));
            expectCode(ResultCodeEnum.AI_DEADLINE_EXCEEDED,
                    () -> f.service(storage, f.access, f.clock).submit(f.input(), f.context("late", "key")).block(WAIT));
            assertCounts(f, 0);
            assertEquals(0, f.wakeups.get());
        }
    }

    @Test
    public void lateDeadlineAfterHttpTimeoutRollsBackWithoutDroppedErrorOrJdbcInterruption() throws Exception {
        assertLateRollback(false);
    }

    @Test
    public void unexpectedLateTransactionFailureIsConsumedAndStillRollsBack() throws Exception {
        assertLateRollback(true);
    }

    private void assertLateRollback(boolean unexpectedFailure) throws Exception {
        var writer = Schedulers.newBoundedElastic(1, 8, "tool-late-rollback");
        var atFinalCheck = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var completionStatus = new AtomicInteger(-1);
        var dropped = new CopyOnWriteArrayList<Throwable>();
        Hooks.onErrorDropped(dropped::add);
        try (var f = new ToolAdmissionFixture()) {
            var candidate = f.service.preview(f.input(), f.context("late-rollback", "original-key")).block(WAIT);
            var checks = new AtomicInteger();
            Clock delayed = new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId zone) {
                    return this;
                }

                public Instant instant() {
                    if (checks.incrementAndGet() == 1) return candidate.receivedAt();
                    // 真正写入快照及 Outbox 后才阻塞，模拟 HTTP 超时后数据库返回失败。
                    atFinalCheck.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("Late transaction was not released");
                    } catch (InterruptedException error) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    if (unexpectedFailure) throw new IllegalStateException("simulated late JDBC failure");
                    return candidate.request().options().deadline().plusSeconds(1);
                }
            };
            var storage = new MybatisToolAdmissionStore(f.source, f.codec, writer, f.executions, f.bindings, f.catalog, delayed);
            var future = storage.accept(candidate, ignored -> TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        public void afterCompletion(int status) {
                            completionStatus.set(status);
                            completed.countDown();
                        }
                    })).timeout(Duration.ofSeconds(2)).toFuture();
            assertTrue("Transaction must reach the post-write deadline check", atFinalCheck.await(5, TimeUnit.SECONDS));
            assertInstanceOfTimeout(assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)));
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            // 同一单线程 worker 的栅栏保证事务终态以及 Reactor 的错误消费均已完成。
            Mono.fromRunnable(() -> {
            }).subscribeOn(writer).block(WAIT);
            assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, completionStatus.get());
            assertFalse("HTTP cancellation must not interrupt an active JDBC transaction", interrupted.get());
            assertTrue("Late errors must not reach the global dropped-error handler", dropped.isEmpty());
            assertCounts(f, 0);
            assertEquals(0, f.wakeups.get());
        } finally {
            release.countDown();
            writer.dispose();
            Hooks.resetOnErrorDropped();
        }
    }

    private static void assertInstanceOfTimeout(ExecutionException failure) {
        assertTrue(failure.getCause() instanceof TimeoutException);
    }

    @Test
    public void snapshotCodecRejectsUnknownVersionOrWrongRootAndRetainsImmutableSchemas() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            f.service.submit(f.input(), f.context("first", "key")).block(WAIT);
            var snapshot = f.store.snapshot(f.owner, "first").block(WAIT);
            var json = f.codec.encode(snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.schemas().clear());
            for (var version : new String[]{"99", "1.2", "\"1\""})
                assertThrows(RuntimeException.class, () -> f.codec.decode(json.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), ToolExecutionSnapshot.class));
            assertThrows(RuntimeException.class, () -> f.codec.decode(json, Invocation.class));
        }
    }

    @Test
    public void wrongPhysicalDataSourceIsRejectedAtConstruction() throws Exception {
        try (var f = new ToolAdmissionFixture()) {
            var wrapped = new DelegatingDataSource(f.source);
            assertThrows(IllegalArgumentException.class, () -> new MybatisToolAdmissionStore(wrapped, f.codec, f.io, f.executions, f.bindings, f.catalog));
        }
    }

    private static void assertCounts(ToolAdmissionFixture f, int count) {
        assertEquals(count, f.count("arte_ai_invocation"));
        assertEquals(count, f.count("arte_ai_acceptance"));
        assertEquals(count, f.count("arte_ai_tool_execution_snapshot"));
        assertEquals(count, f.count("arte_ai_event"));
        assertEquals(count * 2, f.count("arte_ai_outbox"));
    }

    private static void expectCode(ResultCodeEnum code, Runnable work) {
        assertEquals(code, assertThrows(AdmissionException.class, work::run).getResultCode());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new AssertionError(failed);
        }
    }
}
