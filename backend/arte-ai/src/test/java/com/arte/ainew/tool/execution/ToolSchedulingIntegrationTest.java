package com.arte.ainew.tool.execution;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DispatchSchedulingException;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.execution.LocalDispatchNotifier;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisToolAdmissionStore;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.ainew.pojo.execution.OutboxMessage.WorkClass.*;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static org.junit.Assert.*;

/**
 * 隔离 H2 的真实队列／领取／许可／Worker；只验证调度行为，不代表 W1—W4 生产压测。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 21:47 ✾
 */
public class ToolSchedulingIntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(12);

    static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f = new ToolExecutionFixture();
        final String manifest = f.discovery.state().snapshot().checksum();
        final NewAiSchedulingProperties policy;
        final MybatisExecutionPersistence store;
        final MybatisToolAdmissionStore snapshots;
        final ToolAdmissionService admission;
        final ToolDispatcher dispatcher;
        final NewAiExecutionProperties execution = new NewAiExecutionProperties(true, false, 4, Duration.ofMillis(100),
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofDays(1));

        Rig() throws Exception {
            this(8, 8, 2, 1000, 100, 20);
        }

        Rig(int concurrency, int provider, int space, int queue, int spaceQueue, int subjectQueue) throws Exception {
            policy = properties(manifest, concurrency, provider, space, queue, spaceQueue, subjectQueue);
            store = new MybatisExecutionPersistence(f.source, f.codec, f.io, f.wakeups::incrementAndGet, f.wakeups::incrementAndGet, policy, true);
            snapshots = new MybatisToolAdmissionStore(f.source, f.codec, f.io, store, f.bindings, f.catalog);
            admission = f.service(snapshots, f.access, f.clock);
            dispatcher = new ToolDispatcher(store, store, store, snapshots, f.bindingService, f.access, f.gateway, f.payloads,
                    f.codec, f.metadata, f.clock, Duration.ofSeconds(3));
        }

        void submit(String id) {
            admission.submit(f.input(), f.context(id, id)).block(WAIT);
        }

        void raw(String id, String space, String provider, OutboxMessage.WorkClass type) {
            var original = f.context(id, id);
            var context = new ExecutionContext(id, original.traceId(), new ExecutionAuthorization(original.authorization().principal(), "tenant", space,
                    original.authorization().scopes(), "grant"), original.deadline(), null, null, "release", id);
            CapabilityInput input = type == GENERATION ? new GenerationRequest(List.of(new ChatMessage("message-" + id, ChatMessage.Role.USER,
                    List.of(new ChatMessage.Text("test")), List.of(), null)), new GenerationOptions(16, null, null, List.of()), List.of(), new GenerationRequest.TextOutput())
                    : new com.arte.ainew.pojo.tool.ToolInvocation("call-" + id, f.v1.revision().tool().definition(), object());
            var request = new InvocationRequest<>(new DefinitionRef("capability", "test", "v1"), new DefinitionRef("binding", "test", "v1"), input.kind(), input,
                    new ExecutionOptions(context.deadline(), type == GENERATION ? 2 : 1, 4096, 0, 0), context);
            var invocation = new Invocation(request, "1".repeat(64), null, type == GENERATION ? "snapshot-" + id : null, null,
                    Invocation.State.ACCEPTED, 0, null, null, null, f.clock.instant(), f.clock.instant());
            assertTrue(store.accept(new ExecutionCommands.Accept(invocation, null, new DispatchPlan(type, provider))).block(WAIT).successful());
        }

        List<OutboxMessage> claim(OutboxMessage.WorkClass type, int count) {
            return store.claimClass(type, "worker", Duration.ofSeconds(3), count, manifest).block(WAIT);
        }

        Invocation invocation(String id) {
            return store.find(f.owner, id).block(WAIT);
        }

        long number(String sql) {
            return f.jdbc.queryForObject(sql, Long.class);
        }

        InvocationDispatchWorker worker(InvocationCoordinator coordinator, java.util.function.Supplier<String> checksum) {
            return new InvocationDispatchWorker(store, store, coordinator, execution, f.timer, new LocalDispatchNotifier(), policy, checksum);
        }

        public void close() throws Exception {
            f.close();
        }
    }

    static NewAiSchedulingProperties properties(String manifest, int concurrency, int provider, int space, int queue, int spaceQueue, int subjectQueue) {
        return new NewAiSchedulingProperties(true, new NewAiSchedulingProperties.Slots(1, 1, 1, 1), concurrency, concurrency, concurrency, 1,
                space, provider, queue, 300, 500, 100, 100, spaceQueue, subjectQueue, Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), manifest);
    }

    @Test
    public void admissionFixesClassProviderAndQueueDeadlineFromTrustedProductPolicy() throws Exception {
        try (var r = new Rig()) {
            var input = new ToolAdmissionInput(r.f.binding.bindingId(), object(), Duration.ofSeconds(1), null, null);
            r.admission.submit(input, r.f.context("one", "one")).block(WAIT);
            assertEquals("TOOL_LONG", r.f.jdbc.queryForObject("SELECT work_class FROM arte_ai_outbox WHERE kind='DISPATCH'", String.class));
            assertTrue(r.number("SELECT queue_deadline FROM arte_ai_outbox WHERE kind='DISPATCH'") <= r.invocation("one").request().options().deadline().toEpochMilli());
            assertFalse(r.f.jdbc.queryForObject("SELECT provider_key FROM arte_ai_outbox WHERE kind='DISPATCH'", String.class).isEmpty());
        }
    }

    @Test
    public void initialToolEventAndOutboxKeepSequenceAndCommitWakeupsOnReplay() throws Exception {
        try (var r = new Rig()) {
            int before = r.f.wakeups.get();
            r.submit("one");
            assertEquals(before + 2, r.f.wakeups.get());
            assertEquals(1, r.number("SELECT next_sequence FROM arte_ai_invocation"));
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='EVENT' AND sequence_no=1"));
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH' AND sequence_no=0"));
            var initial = r.store.replay(r.f.owner, new ExecutionEvent.Cursor("one", 0), 100).block(WAIT).value().events();
            assertEquals(1, initial.size());
            assertEquals(1, initial.getFirst().sequence());
            assertEquals(new ExecutionPayload.Status(Invocation.State.ACCEPTED), initial.getFirst().payload());
            r.submit("one");
            assertEquals(before + 2, r.f.wakeups.get());
            assertEquals(4, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
            r.store.requestControl(r.f.owner, new ExecutionControlRequest("stop", "one", "stop", ControlReceipt.Command.CANCEL)).block(WAIT);
            var events = r.store.replay(r.f.owner, new ExecutionEvent.Cursor("one", 0), 100).block(WAIT).value().events();
            assertTrue(events.size() > 1);
            for (int index = 0; index < events.size(); index++) assertEquals(index + 1, events.get(index).sequence());
            assertEquals(events.size(), r.number("SELECT next_sequence FROM arte_ai_invocation"));
        }
    }

    @Test
    public void zeroCounterRollsBackAllReleaseAndTerminalWrites() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            r.f.jdbc.update("UPDATE arte_ai_queue_capacity SET reservations=0 WHERE counter_key=(SELECT MIN(counter_key) FROM arte_ai_queue_capacity)");
            int wakeups = r.f.wakeups.get();
            assertThrows(IllegalStateException.class, () -> r.store.requestControl(r.f.owner,
                    new ExecutionControlRequest("stop", "one", "stop", ControlReceipt.Command.CANCEL)).block(WAIT));
            assertEquals(Invocation.State.ACCEPTED, r.invocation("one").state());
            assertEquals(3, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1"));
            assertEquals(1, r.number("SELECT next_sequence FROM arte_ai_invocation"));
            assertEquals(wakeups, r.f.wakeups.get());
        }
    }

    @Test
    public void classifiedClaimNeverFallsBackToUnfilteredLegacyClaim() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            assertThrows(IllegalStateException.class, () -> r.store.claim(OutboxMessage.Kind.DISPATCH, "old", Duration.ofSeconds(3), 1).block(WAIT));
        }
    }

    @Test
    public void globalQueueLimitRollsBackAndSameKeyReplayDoesNotConsumeAgain() throws Exception {
        try (var r = new Rig(8, 8, 2, 1, 100, 20)) {
            r.submit("one");
            r.admission.submit(r.f.input(), r.f.context("other-id", "one")).block(WAIT);
            var error = assertThrows(DispatchSchedulingException.class, () -> r.submit("two"));
            assertEquals(DispatchSchedulingException.Reason.QUEUE_FULL, error.reason());
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(1, r.f.count("arte_ai_tool_execution_snapshot"));
            assertEquals(0, r.f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void queueCapacityIsArbitratedAcrossTwoConcurrentAdmissions() throws Exception {
        try (var r = new Rig(8, 8, 2, 1, 100, 20); var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CyclicBarrier(2);
            var a = pool.submit(() -> {
                gate.await();
                try {
                    r.submit("one");
                    return true;
                } catch (DispatchSchedulingException full) {
                    return false;
                }
            });
            var b = pool.submit(() -> {
                gate.await();
                try {
                    r.submit("two");
                    return true;
                } catch (DispatchSchedulingException full) {
                    return false;
                }
            });
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(1, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void workspaceAndSubjectQueueLimitsAreIndependentOfGlobalLimit() throws Exception {
        for (boolean workspace : List.of(true, false))
            try (var r = new Rig(8, 8, 2, 1000, workspace ? 1 : 100, workspace ? 20 : 1)) {
                r.raw("one", "a", "p", TOOL_SHORT);
                assertThrows(DispatchSchedulingException.class, () -> r.raw("two", workspace ? "a" : "b", "p", TOOL_SHORT));
                assertEquals(1, r.f.count("arte_ai_invocation"));
            }
    }

    @Test
    public void cancellationAtomicallyReleasesQueueReservation() throws Exception {
        try (var r = new Rig(8, 8, 2, 1, 100, 20)) {
            r.submit("one");
            r.store.requestControl(r.f.owner, new ExecutionControlRequest("stop", "one", "stop", ControlReceipt.Command.CANCEL)).block(WAIT);
            r.submit("two");
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1"));
        }
    }

    @Test
    public void failedOutboxWriteDoesNotLeaveCapacityOrPartialAdmission() throws Exception {
        try (var r = new Rig(8, 8, 2, 1, 100, 20)) {
            r.f.jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT fail_queue CHECK(kind&lt;&gt;'DISPATCH')".replace("&lt;&gt;", "<>"));
            int wakeups = r.f.wakeups.get();
            assertThrows(Exception.class, () -> r.submit("one"));
            for (String table : List.of("arte_ai_invocation", "arte_ai_acceptance", "arte_ai_event", "arte_ai_outbox",
                    "arte_ai_tool_execution_snapshot", "arte_ai_queue_capacity"))
                assertEquals(table, 0, r.f.count(table));
            assertEquals(wakeups, r.f.wakeups.get());
            r.f.jdbc.execute("ALTER TABLE arte_ai_outbox DROP CONSTRAINT fail_queue");
            r.submit("two");
        }
    }

    @Test
    public void failedBatchReservationRollsBackInitialEventsAndSuppressesWakeups() throws Exception {
        try (var r = new Rig()) {
            r.f.jdbc.execute("ALTER TABLE arte_ai_queue_capacity ADD CONSTRAINT fail_reservation CHECK(reservations=0)");
            int wakeups = r.f.wakeups.get();
            var failure = assertThrows(DispatchSchedulingException.class, () -> r.submit("one"));
            assertEquals(DispatchSchedulingException.Reason.UNAVAILABLE, failure.reason());
            for (String table : List.of("arte_ai_invocation", "arte_ai_acceptance", "arte_ai_event", "arte_ai_outbox",
                    "arte_ai_tool_execution_snapshot", "arte_ai_queue_capacity", "arte_ai_dispatch_pool"))
                assertEquals(table, 0, r.f.count(table));
            assertEquals(wakeups, r.f.wakeups.get());
            r.f.jdbc.execute("ALTER TABLE arte_ai_queue_capacity DROP CONSTRAINT fail_reservation");
            r.submit("one");
            assertEquals(wakeups + 2, r.f.wakeups.get());
            assertEquals(4, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
        }
    }

    @Test
    public void fullClassDoesNotClaimAnotherMessageOrConsumeOtherClasses() throws Exception {
        try (var r = new Rig(1, 8, 8, 1000, 100, 20)) {
            r.raw("long-one", "a", "long", TOOL_LONG);
            r.raw("long-two", "b", "long", TOOL_LONG);
            r.raw("short", "c", "short", TOOL_SHORT);
            r.raw("generation", "d", "model", GENERATION);
            assertEquals(1, r.claim(TOOL_LONG, 2).size());
            assertTrue(r.claim(TOOL_LONG, 1).isEmpty());
            assertEquals(1, r.claim(TOOL_SHORT, 1).size());
            assertEquals(1, r.claim(GENERATION, 1).size());
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH' AND token=0"));
        }
    }

    @Test
    public void saturatedSpaceIsSkippedForAnotherSpace() throws Exception {
        try (var r = new Rig(8, 8, 1, 1000, 100, 20)) {
            r.raw("a1", "a", "p", TOOL_SHORT);
            r.raw("a2", "a", "p", TOOL_SHORT);
            r.raw("b1", "b", "p", TOOL_SHORT);
            var first = r.claim(TOOL_SHORT, 1).getFirst();
            var second = r.claim(TOOL_SHORT, 1).getFirst();
            assertNotEquals(first.owner().workspaceId(), second.owner().workspaceId());
            assertTrue(r.claim(TOOL_SHORT, 1).isEmpty());
        }
    }

    @Test
    public void saturatedProviderDoesNotBlockAnotherProviderInSameSpace() throws Exception {
        try (var r = new Rig(8, 1, 8, 1000, 100, 20)) {
            r.raw("one", "a", "p", TOOL_SHORT);
            r.claim(TOOL_SHORT, 1);
            r.raw("blocked", "a", "p", TOOL_SHORT);
            r.raw("eligible", "a", "q", TOOL_SHORT);
            assertEquals("eligible", r.claim(TOOL_SHORT, 1).getFirst().invocationId());
        }
    }

    @Test
    public void roundRobinCursorPersistsAcrossStoreInstances() throws Exception {
        try (var r = new Rig(8, 8, 8, 1000, 500, 500)) {
            for (int i = 0; i < 20; i++) {
                r.raw("a" + i, "a", "p", TOOL_SHORT);
                r.raw("b" + i, "b", "p", TOOL_SHORT);
            }
            var second = new MybatisExecutionPersistence(r.f.source, r.f.codec, r.f.io, () -> {
            }, () -> {
            }, r.policy, true);
            var spaces = new ArrayList<String>();
            for (int i = 0; i < 40; i++) {
                var store = i % 2 == 0 ? r.store : second;
                var message = store.claimClass(TOOL_SHORT, "node-" + i, Duration.ofSeconds(3), 1, r.manifest).block(WAIT).getFirst();
                spaces.add(message.owner().workspaceId());
                assertTrue(store.acknowledge(message).block(WAIT).successful());
            }
            for (int i = 1; i < spaces.size(); i++) assertNotEquals(spaces.get(i - 1), spaces.get(i));
        }
    }

    @Test
    public void providerQuotaIsSharedAcrossClassesAndNodes() throws Exception {
        try (var r = new Rig(8, 1, 8, 1000, 100, 20)) {
            r.raw("one", "a", "p", TOOL_LONG);
            r.raw("two", "b", "p", TOOL_SHORT);
            var first = r.claim(TOOL_LONG, 1).getFirst();
            var second = new MybatisExecutionPersistence(r.f.source, r.f.codec, r.f.io, () -> {
            }, () -> {
            }, r.policy, true);
            assertTrue(second.claimClass(TOOL_SHORT, "other", Duration.ofSeconds(3), 1, r.manifest).block(WAIT).isEmpty());
            r.store.acknowledge(first).block(WAIT);
            assertEquals(1, r.claim(TOOL_SHORT, 1).size());
        }
    }

    @Test
    public void renewalExtendsSameCapacityPermitWithoutAllocatingAnother() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var message = r.claim(TOOL_LONG, 1).getFirst();
            var renewed = r.store.renewClaim(message, Duration.ofSeconds(5)).block(WAIT).value();
            assertEquals(renewed.leaseExpiresAt().toEpochMilli(), r.number("SELECT lease_until FROM arte_ai_scheduling_permit"));
            assertEquals(1, r.f.count("arte_ai_scheduling_permit"));
            assertEquals(TOOL_LONG, renewed.workClass());
        }
    }

    @Test
    public void deferReleasesCapacityWithoutResettingQueueDeadline() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var message = r.claim(TOOL_LONG, 1).getFirst();
            long deadline = r.number("SELECT queue_deadline FROM arte_ai_outbox WHERE kind='DISPATCH'");
            r.store.defer(message, Duration.ZERO).block(WAIT);
            assertEquals(0, r.f.count("arte_ai_scheduling_permit"));
            var next = r.claim(TOOL_LONG, 1).getFirst();
            assertTrue(next.fencingToken() > message.fencingToken());
            assertEquals(deadline, r.number("SELECT queue_deadline FROM arte_ai_outbox WHERE kind='DISPATCH'"));
        }
    }

    @Test
    public void staleAckAndRenewCannotReleaseOrExtendNewOwnersPermit() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var old = r.claim(TOOL_LONG, 1).getFirst();
            r.f.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
            r.f.jdbc.update("UPDATE arte_ai_scheduling_permit SET lease_until=0");
            var current = r.claim(TOOL_LONG, 1).getFirst();
            assertEquals(StoreOutcome.Code.LEASE_LOST, r.store.acknowledge(old).block(WAIT).code());
            assertEquals(StoreOutcome.Code.LEASE_LOST, r.store.renewClaim(old, Duration.ofSeconds(3)).block(WAIT).code());
            assertEquals(current.fencingToken(), r.number("SELECT token FROM arte_ai_scheduling_permit"));
        }
    }

    @Test
    public void duplicateAckDoesNotReleaseAnotherMessagesPermit() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            r.submit("two");
            var messages = r.claim(TOOL_LONG, 2);
            r.store.acknowledge(messages.getFirst()).block(WAIT);
            assertEquals(StoreOutcome.Code.REPLAYED, r.store.acknowledge(messages.getFirst()).block(WAIT).code());
            assertEquals(1, r.f.count("arte_ai_scheduling_permit"));
        }
    }

    @Test
    public void forgedClassCannotPassClaimValidation() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var actual = r.claim(TOOL_LONG, 1).getFirst();
            var forged = new OutboxMessage(actual.messageId(), actual.invocationId(), actual.owner(), actual.kind(), 0, actual.workerId(), actual.fencingToken(), actual.leaseExpiresAt(), TOOL_SHORT);
            assertEquals(StoreOutcome.Code.LEASE_LOST, r.store.validateClaim(forged).block(WAIT).code());
        }
    }

    @Test
    public void wrongNodeManifestCannotClaimOrAdvanceToken() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            assertThrows(DispatchSchedulingException.class, () -> r.store.claimClass(TOOL_LONG, "wrong", Duration.ofSeconds(3), 1, "0".repeat(64)).block(WAIT));
            assertEquals(0, r.number("SELECT token FROM arte_ai_outbox WHERE kind='DISPATCH'"));
        }
    }

    @Test
    public void inconsistentPoolPolicyFailsClosed() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var other = properties(r.manifest, 3, 4, 1, 1000, 100, 20);
            var node = new MybatisExecutionPersistence(r.f.source, r.f.codec, r.f.io, () -> {
            }, () -> {
            }, other, true);
            assertThrows(DispatchSchedulingException.class, () -> node.claimClass(TOOL_LONG, "other", Duration.ofSeconds(3), 1, r.manifest).block(WAIT));
        }
    }

    @Test
    public void queueTimeoutIsCollectedWithoutExecutionCapacityAndOnlyOnce() throws Exception {
        try (var r = new Rig(1, 8, 8, 1000, 100, 20)) {
            r.submit("one");
            r.claim(TOOL_LONG, 1);
            r.submit("two");
            r.f.jdbc.update("UPDATE arte_ai_outbox SET queue_deadline=0 WHERE invocation_id='two' AND kind='DISPATCH'");
            assertEquals(1, (int) r.store.expireQueued(64).block(WAIT));
            assertEquals(0, (int) r.store.expireQueued(64).block(WAIT));
            var invocation = r.invocation("two");
            assertEquals(Invocation.State.TIMED_OUT, invocation.state());
            assertNull(invocation.activeAttemptId());
            assertEquals(ExecutionError.SideEffect.NONE, invocation.error().sideEffect());
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void realToolWorkerCommitsAndReleasesQueueAndExecutionCapacity() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var worker = r.worker(r.f.coordinator(r.dispatcher), () -> r.manifest);
            assertEquals(1, (int) worker.pollOnce().block(WAIT));
            assertEquals(Invocation.State.SUCCEEDED, r.invocation("one").state());
            assertEquals(0, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1"));
            assertEquals(0, r.f.count("arte_ai_scheduling_permit"));
            assertEquals(1, r.f.invocations.get());
            assertEquals(0, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void readinessLossSkipsToolClaimsWhileGenerationCanContinue() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var checksum = new AtomicReference<String>();
            var worker = r.worker(r.f.coordinator(r.dispatcher), checksum::get);
            assertFalse(worker.toolExecutionReady());
            assertEquals(0, (int) worker.pollOnce().block(WAIT));
            checksum.set(r.manifest);
            assertEquals(1, (int) worker.pollOnce().block(WAIT));
            assertTrue(worker.toolExecutionReady());
        }
    }

    @Test
    public void localSlotTotalMustMatchExistingConcurrency() throws Exception {
        try (var r = new Rig()) {
            var wrong = new NewAiExecutionProperties(true, false, 6, Duration.ofMillis(100), Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofDays(1));
            assertThrows(IllegalArgumentException.class, () -> new InvocationDispatchWorker(r.store, r.store, r.f.coordinator(r.dispatcher), wrong, r.f.timer,
                    new LocalDispatchNotifier(), r.policy, () -> r.manifest));
        }
    }

    @Test
    public void outcomeCheckHasReservedSlotButNoFabricatedS2Work() throws Exception {
        try (var r = new Rig()) {
            assertTrue(r.claim(OUTCOME_CHECK, 1).isEmpty());
            assertEquals(1, r.policy.slots().outcomeCheck());
        }
    }

    @Test
    public void productionToolAdmissionRequiresExplicitSchedulingActivation() throws Exception {
        try (var r = new Rig()) {
            var disabled = new MybatisExecutionPersistence(r.f.source, r.f.codec, r.f.io, () -> {
            }, () -> {
            }, NewAiSchedulingProperties.disabled(), true);
            var snapshots = new MybatisToolAdmissionStore(r.f.source, r.f.codec, r.f.io, disabled, r.f.bindings, r.f.catalog);
            assertThrows(DispatchSchedulingException.class, () -> r.f.service(snapshots, r.f.access, r.f.clock).submit(r.f.input(), r.f.context("one", "one")).block(WAIT));
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void fullQueueMapsTo429WithRetryAfterAndUnavailableMapsTo503() throws Exception {
        var handler = new com.arte.ainew.web.ConversationExceptionHandler();
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var full = handler.handle(new DispatchSchedulingException(DispatchSchedulingException.Reason.QUEUE_FULL), request, Locale.SIMPLIFIED_CHINESE);
        assertEquals(429, full.getStatusCode().value());
        assertEquals("5", full.getHeaders().getFirst("Retry-After"));
        assertEquals(503, handler.handle(new DispatchSchedulingException(DispatchSchedulingException.Reason.NODE_NOT_READY), request, Locale.US).getStatusCode().value());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(NewAiExecutionProperties.class)
    static class BindingConfiguration {
    }

    @Test
    public void strictNestedSchedulingConfigurationBindsAndRejectsTypos() {
        for (boolean typo : List.of(false, true))
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                var flags = new HashMap<String, Object>();
                flags.put("arte.ai-new-execution.enabled", true);
                flags.put("arte.ai-new-execution.concurrency", 6);
                flags.put("arte.ai-new-execution.scheduling.enabled", true);
                if (typo) flags.put("arte.ai-new-execution.scheduling.short-concurency", 2);
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", flags));
                context.register(BindingConfiguration.class);
                if (typo) assertThrows(org.springframework.beans.BeansException.class, context::refresh);
                else {
                    context.refresh();
                    var policy = context.getBean(NewAiExecutionProperties.class).scheduling();
                    assertTrue(policy.enabled());
                    assertEquals(6, policy.slots().total());
                }
            }
    }

    @Test
    public void queueDeadlineIsRecheckedInAtomicPreparationBeforeBusinessEntry() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var message = r.claim(TOOL_LONG, 1).getFirst();
            r.f.jdbc.update("UPDATE arte_ai_outbox SET queue_deadline=0 WHERE kind='DISPATCH'");
            var invocation = r.invocation("one");
            var result = r.store.prepareDispatch(new com.arte.ainew.spi.persistence.DispatchPreparationStore.Command(
                    new ExecutionCommands.Version(r.f.owner, "one", invocation.version()), message, "attempt", Duration.ofSeconds(3),
                    new com.arte.ainew.spi.persistence.DispatchPreparationStore.Free())).block(WAIT);
            assertEquals(StoreOutcome.Code.INVALID_STATE, result.code());
            assertEquals(0, r.f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void missingCapacityPermitCannotBeRenewedIntoAnUnmeteredClaim() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var message = r.claim(TOOL_LONG, 1).getFirst();
            long original = r.number("SELECT lease_until FROM arte_ai_outbox WHERE kind='DISPATCH'");
            r.f.jdbc.update("DELETE FROM arte_ai_scheduling_permit");
            assertEquals(StoreOutcome.Code.LEASE_LOST, r.store.renewClaim(message, Duration.ofSeconds(5)).block(WAIT).code());
            assertEquals(original, r.number("SELECT lease_until FROM arte_ai_outbox WHERE kind='DISPATCH'"));
        }
    }

    @Test
    public void automaticWorkerKeepsLongToolFromOccupyingShortAndGenerationSlots() throws Exception {
        try (var r = new Rig()) {
            var longEntered = new CountDownLatch(1);
            var others = new CountDownLatch(2);
            var finish = Sinks.<Void>one();
            InvocationCoordinator coordinator = new InvocationCoordinator() {
                public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
                    return Mono.error(new UnsupportedOperationException());
                }

                public Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime) {
                    return Mono.error(new UnsupportedOperationException());
                }

                public Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context) {
                    return Mono.error(new UnsupportedOperationException());
                }

                public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
                    Mono<Void> gate;
                    if (message.workClass() == TOOL_LONG) {
                        longEntered.countDown();
                        gate = finish.asMono();
                    } else {
                        others.countDown();
                        gate = Mono.empty();
                    }
                    return gate.then(r.store.find(message.owner(), message.invocationId())).flatMap(invocation -> {
                        var error = new ExecutionError("CONTROLLED_TEST_COMPLETE", ExecutionError.Phase.ADMISSION, false, ExecutionError.SideEffect.NONE,
                                ExecutionError.Certainty.KNOWN, runtime.execution().traceId());
                        return r.store.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(new ExecutionCommands.Version(message.owner(), message.invocationId(), invocation.version()),
                                "test-completion", new ExecutionPayload.Terminal(Invocation.State.FAILED, null, error)));
                    }).then();
                }
            };
            var worker = r.worker(coordinator, () -> r.manifest);
            try {
                r.raw("long", "a", "long", TOOL_LONG);
                worker.start();
                assertTrue(longEntered.await(5, TimeUnit.SECONDS));
                r.raw("short", "b", "short", TOOL_SHORT);
                r.raw("generation", "c", "model", GENERATION);
                assertTrue("Other classes must run while long tool is still blocked", others.await(5, TimeUnit.SECONDS));
                assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE invocation_id='long' AND delivered=0 AND kind='DISPATCH'"));
            } finally {
                finish.tryEmitEmpty();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < end && r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH' AND delivered=0") != 0)
                    Thread.sleep(10);
                worker.stop();
            }
        }
    }

    @Test
    public void incrementalMigrationRejectsUndrainedOldDispatchWork() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            assertThrows(org.springframework.jdbc.datasource.init.ScriptException.class, () -> new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource("arte-ai-dispatch-scheduling-migration.sql")).populate(r.f.ddl));
            assertEquals(Invocation.State.ACCEPTED, r.invocation("one").state());
        }
    }

    @Test
    public void incrementalMigrationMatchesCleanSchemaAfterDraining() throws Exception {
        try (var r = new Rig()) {
            for (var index : List.of("class", "queue", "reserved"))
                r.f.jdbc.execute("DROP INDEX idx_arte_ai_outbox_" + index);
            for (var column : List.of("work_class", "provider_key", "space_key", "subject_key", "queued_at", "queue_deadline", "queue_pending", "queue_reserved", "finished"))
                r.f.jdbc.execute("ALTER TABLE arte_ai_outbox DROP COLUMN " + column);
            for (var table : List.of("arte_ai_scheduling_permit", "arte_ai_dispatch_cursor", "arte_ai_dispatch_pool", "arte_ai_queue_capacity"))
                r.f.jdbc.execute("DROP TABLE " + table);
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("arte-ai-dispatch-scheduling-migration.sql")).populate(r.f.ddl);
            r.submit("one");
            assertEquals(1, r.claim(TOOL_LONG, 1).size());
        }
    }

    @Test
    public void unavailableQueueAuthorityFailsClosedWithoutPartialAdmission() throws Exception {
        try (var r = new Rig()) {
            r.f.jdbc.execute("DROP TABLE arte_ai_dispatch_pool");
            var error = assertThrows(DispatchSchedulingException.class, () -> r.submit("one"));
            assertEquals(DispatchSchedulingException.Reason.UNAVAILABLE, error.reason());
            assertEquals(0, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_tool_execution_snapshot"));
        }
    }

    @Test
    public void realPreparationReleasesQueueCountersAndAllowsNextAdmission() throws Exception {
        try (var r = new Rig(8, 8, 2, 1, 100, 20)) {
            r.submit("one");
            r.worker(r.f.coordinator(r.dispatcher), () -> r.manifest).pollOnce().block(WAIT);
            r.submit("two");
            assertEquals(4, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1"));
        }
    }

    @Test
    public void replayedCancellationCannotUnderflowDurableQueueCounters() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            var command = new ExecutionControlRequest("stop", "one", "key", ControlReceipt.Command.CANCEL);
            r.store.requestControl(r.f.owner, command).block(WAIT);
            r.store.requestControl(r.f.owner, command).block(WAIT);
            r.store.requestControl(r.f.owner, new ExecutionControlRequest("other", "one", "other", ControlReceipt.Command.CANCEL)).block(WAIT);
            assertEquals(0, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
        }
    }

    @Test
    public void missingQueueCounterRollsBackCancellationInsteadOfLosingCapacityFacts() throws Exception {
        try (var r = new Rig()) {
            r.submit("one");
            r.f.jdbc.update("DELETE FROM arte_ai_queue_capacity");
            assertThrows(IllegalStateException.class, () -> r.store.requestControl(r.f.owner,
                    new ExecutionControlRequest("stop", "one", "key", ControlReceipt.Command.CANCEL)).block(WAIT));
            assertEquals(Invocation.State.ACCEPTED, r.invocation("one").state());
            assertEquals(1, r.number("SELECT COUNT(*) FROM arte_ai_outbox WHERE queue_reserved=1"));
        }
    }

    @Test
    public void expiredUndispatchedGenerationRetryCanFinishWithoutWaitingForExecutionCapacity() throws Exception {
        try (var r = new Rig()) {
            r.raw("one", "workspace", "model", GENERATION);
            var message = r.claim(GENERATION, 1).getFirst();
            var first = r.invocation("one");
            r.store.createAttempt(new ExecutionCommands.CreateAttempt(new ExecutionCommands.Version(r.f.owner, "one", first.version()), "attempt", "worker", Duration.ofSeconds(3))).block(WAIT);
            var attempt = r.store.findAttempt(r.f.owner, "one", "attempt").block(WAIT);
            var expired = new Attempt(attempt.attemptId(), attempt.invocationId(), attempt.attemptNumber(), attempt.workerId(), attempt.fencingToken(),
                    attempt.createdAt().plusMillis(1), attempt.version(), attempt.state(), attempt.dispatch(), null, null, attempt.usage(), null, attempt.createdAt(), attempt.updatedAt());
            r.f.jdbc.update("UPDATE arte_ai_attempt SET workspace_snapshot=?", r.f.codec.encode(expired));
            // 租约判断使用数据库毫秒时钟；不能假定下一次事务已经越过 createdAt + 1ms。
            long waitUntil = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (r.f.jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant()
                    .toEpochMilli() < expired.leaseExpiresAt().toEpochMilli()) {
                assertTrue("Database clock did not reach lease expiry", System.nanoTime() < waitUntil);
                Thread.sleep(1);
            }
            var running = r.invocation("one");
            var queued = r.store.queueExpiredRetry(new ExecutionCommands.Version(r.f.owner, "one", running.version()), Duration.ZERO).block(WAIT);
            assertTrue(queued.code().name(), queued.successful());
            r.f.jdbc.update("UPDATE arte_ai_outbox SET queue_deadline=0 WHERE kind='DISPATCH'");
            r.store.expireQueued(64).block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, r.invocation("one").state());
            assertEquals(0, r.number("SELECT SUM(reservations) FROM arte_ai_queue_capacity"));
            r.store.acknowledge(message).block(WAIT);
        }
    }

}
