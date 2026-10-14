package com.arte.ainew.admission;

import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.execution.DispatchDeferredException;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.execution.LocalDispatchNotifier;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/**
 * 真实 H2 领取与提交事务＋可控协调器；验证调度，不访问真实模型或当前数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/14 20:06 ✾
 */
public class DispatchWorkerIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue("等待 Worker 条件超时", condition.getAsBoolean());
    }

    private static final class Task {
        final OutboxMessage message;
        final ExecutionRuntimeContext runtime;
        final Sinks.Empty<Void> completion = Sinks.empty();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final long startedNanos = System.nanoTime();

        Task(OutboxMessage message, ExecutionRuntimeContext runtime) {
            this.message = message;
            this.runtime = runtime;
        }
    }

    private static final class Rig implements AutoCloseable {
        final JdbcDataSource source = new JdbcDataSource();
        final reactor.core.scheduler.Scheduler db = Schedulers.newBoundedElastic(4, 128, "worker-db-test");
        final reactor.core.scheduler.Scheduler timer = Schedulers.newSingle("worker-timer-test");
        final LocalDispatchNotifier notifier = new LocalDispatchNotifier();
        final AtomicBoolean failClaim = new AtomicBoolean(), failWake = new AtomicBoolean();
        final AtomicInteger commits = new AtomicInteger(), claims = new AtomicInteger();
        final AtomicLong committedNanos = new AtomicLong();
        final AtomicInteger active = new AtomicInteger(), maxActive = new AtomicInteger();
        final List<Integer> limits = new CopyOnWriteArrayList<>();
        final List<Task> started = new CopyOnWriteArrayList<>();
        final JdbcTemplate jdbc;
        final AdmissionFixture fixture;
        final InvocationCoordinator coordinator;
        final InvocationDispatchWorker worker;

        @SuppressWarnings("unchecked")
        Rig(int concurrency, Duration recovery) {
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
            jdbc = new JdbcTemplate(source);
            fixture = new AdmissionFixture(source, db, new JacksonExecutionRecordCodec(), AdmissionFixture.properties(), () -> {
            }, () -> {
                committedNanos.set(System.nanoTime());
                // 独立连接能看到 Outbox，证明提示发生于提交之后。
                try (var connection = source.getConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'")) {
                    assertTrue(rows.next());
                    assertTrue(rows.getInt(1) > 0);
                } catch (java.sql.SQLException error) {
                    throw new IllegalStateException(error);
                }
                commits.incrementAndGet();
                if (failWake.get()) throw new IllegalStateException("模拟唤醒故障");
                notifier.committed();
            });
            fixture.initializeBudget("alice");
            coordinator = new InvocationCoordinator() {
                public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
                    return fixture.coordinator.submit(submission);
                }

                public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
                    return fixture.executions.validateClaim(message).flatMap(valid -> {
                        assertTrue(valid.successful());
                        var task = new Task(message, runtime);
                        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                        started.add(task);
                        return task.completion.asMono().doOnCancel(() -> task.cancelled.set(true))
                                .doFinally(ignored -> active.decrementAndGet());
                    });
                }

                public Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime) {
                    return Mono.error(new UnsupportedOperationException());
                }

                public Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context) {
                    return Mono.error(new UnsupportedOperationException());
                }
            };
            var recording = (ExecutionOutboxStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ExecutionOutboxStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("claim")) {
                    limits.add((Integer) args[3]);
                    if (failClaim.compareAndSet(true, false)) {
                        claims.incrementAndGet();
                        return Mono.error(new IllegalStateException("模拟领取故障"));
                    }
                    return ((Mono<List<OutboxMessage>>) method.invoke(fixture.executions, args)).doOnSuccess(ignored -> claims.incrementAndGet());
                }
                return method.invoke(fixture.executions, args);
            });
            worker = new InvocationDispatchWorker(recording, fixture.executions, coordinator, settings(concurrency, recovery), timer, notifier);
        }

        AcceptedExecution submit(String key) {
            var context = fixture.context("alice", key);
            var conversation = fixture.conversations.create("worker", null, List.of(), fixture.context("alice", "conversation-" + key)).block(WAIT);
            return fixture.chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "test", context), context).block(WAIT);
        }

        void idleStart() throws Exception {
            worker.start();
            await(() -> claims.get() > 0);
        }

        public void close() {
            worker.stop();
            timer.dispose();
            db.dispose();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    private static NewAiExecutionProperties settings(int concurrency, Duration recovery) {
        return new NewAiExecutionProperties(true, false, concurrency, recovery, Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofDays(1));
    }

    @Test
    public void committedSubmissionWakesIdleWorkerBeforeRecoveryInterval() throws Exception {
        try (var rig = new Rig(2, Duration.ofMinutes(1))) {
            rig.idleStart();
            long began = System.nanoTime();
            var accepted = rig.submit("immediate");
            await(() -> rig.started.size() == 1);
            assertEquals(accepted.executionId(), rig.started.getFirst().message.invocationId());
            assertEquals(1, rig.commits.get());
            System.out.println("WORKER_SUBMIT_TO_CONTROLLED_DISPATCH_MS=" + (System.nanoTime() - began) / 1_000_000);
            System.out.println("WORKER_COMMIT_TO_CONTROLLED_DISPATCH_MS="
                    + (rig.started.getFirst().startedNanos - rig.committedNanos.get()) / 1_000_000);
            rig.started.getFirst().completion.tryEmitEmpty();
        }
    }

    @Test
    public void completesOneSlotAndRefillsWhileAnotherTaskStaysOpen() throws Exception {
        try (var rig = new Rig(2, Duration.ofMinutes(1))) {
            for (int i = 0; i < 4; i++) rig.submit("queued-" + i);
            rig.worker.start();
            await(() -> rig.started.size() == 2);
            var longTask = rig.started.getFirst();
            int fullClaims = rig.claims.get();
            for (int i = 0; i < 100; i++) rig.notifier.wakeWorker();
            var barrier = new CountDownLatch(1);
            rig.timer.schedule(barrier::countDown);
            assertTrue(barrier.await(5, TimeUnit.SECONDS));
            assertEquals(fullClaims, rig.claims.get());
            assertEquals(Integer.valueOf(0), rig.worker.pollOnce().block(WAIT));
            long began = System.nanoTime();
            rig.started.get(1).completion.tryEmitEmpty();
            await(() -> rig.started.size() == 3);
            System.out.println("WORKER_FREE_SLOT_TO_REPLACEMENT_MS=" + (rig.started.get(2).startedNanos - began) / 1_000_000);
            assertFalse(longTask.cancelled.get());
            assertEquals(2, rig.active.get());
            assertEquals(1, rig.limits.getLast().intValue());
            rig.started.get(2).completion.tryEmitEmpty();
            await(() -> rig.started.size() == 4);
            assertEquals(2, rig.maxActive.get());
            assertEquals(4, rig.started.stream().map(t -> t.message.messageId()).distinct().count());
        }
    }

    @Test
    public void recoveryScanFindsCommittedWorkWhenHintFails() throws Exception {
        try (var rig = new Rig(1, Duration.ofMillis(100))) {
            rig.idleStart();
            rig.failWake.set(true);
            var accepted = rig.submit("lost-hint");
            await(() -> rig.started.size() == 1);
            assertEquals(accepted.executionId(), rig.started.getFirst().message.invocationId());
            assertTrue(rig.worker.isRunning());
        }
    }

    @Test
    public void claimFailureKeepsWorkerAliveForNextCommit() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.failClaim.set(true);
            rig.idleStart();
            rig.submit("after-error");
            await(() -> rig.started.size() == 1);
            assertTrue(rig.worker.isRunning());
        }
    }

    @Test
    public void rollbackDoesNotWakeAndSuccessfulReplayDoesNotWakeAgain() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.idleStart();
            rig.jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT reject_dispatch CHECK (kind <> 'DISPATCH')");
            assertThrows(RuntimeException.class, () -> rig.submit("rollback"));
            assertEquals(0, rig.commits.get());
            assertTrue(rig.started.isEmpty());
            assertEquals(0, rig.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_invocation", Integer.class).intValue());
            rig.jdbc.execute("ALTER TABLE arte_ai_outbox DROP CONSTRAINT reject_dispatch");
            var context = rig.fixture.context("alice", "replay");
            var conversation = rig.fixture.conversations.create("worker", null, List.of(), rig.fixture.context("alice", "conversation-replay")).block(WAIT);
            var request = rig.fixture.chatRequest(conversation.conversationId(), 0, "test", context);
            var first = rig.fixture.chat.submit(request, context).block(WAIT);
            var second = rig.fixture.chat.submit(request, context).block(WAIT);
            assertEquals(first.executionId(), second.executionId());
            assertEquals(1, rig.commits.get());
            await(() -> rig.started.size() == 1);
        }
    }

    @Test
    public void stoppingCancelsEachTaskAndRestartKeepsIndependentSlots() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.submit("first");
            rig.worker.start();
            await(() -> rig.started.size() == 1);
            var stopped = rig.started.getFirst();
            rig.worker.stop();
            await(stopped.cancelled::get);
            assertTrue(stopped.runtime.cancellation().isCancelled());
            assertFalse(rig.worker.isRunning());
            assertEquals(0, rig.jdbc.queryForObject("SELECT delivered FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
            rig.submit("second");
            rig.worker.start();
            await(() -> rig.started.size() == 2);
            stopped.completion.tryEmitEmpty();
            rig.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE message_key=?", stopped.message.messageId());
            rig.notifier.wakeWorker();
            var barrier = new CountDownLatch(1);
            rig.timer.schedule(barrier::countDown);
            assertTrue(barrier.await(5, TimeUnit.SECONDS));
            assertEquals(2, rig.started.size());
            rig.started.get(1).completion.tryEmitEmpty();
            await(() -> rig.started.size() == 3);
            assertEquals(stopped.message.messageId(), rig.started.get(2).message.messageId());
            assertTrue(rig.started.get(2).message.fencingToken() > stopped.message.fencingToken());
            assertEquals(1, rig.maxActive.get());
        }
    }

    @Test
    public void automaticStartWaitsForExistingManualBatch() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.submit("manual");
            rig.submit("automatic");
            var manual = rig.worker.pollOnce().toFuture();
            await(() -> rig.started.size() == 1);
            rig.worker.start();
            rig.started.getFirst().completion.tryEmitEmpty();
            assertEquals(1, manual.get(5, TimeUnit.SECONDS).intValue());
            await(() -> rig.started.size() == 2);
            assertEquals(1, rig.maxActive.get());
        }
    }

    @Test
    public void competingAutomaticWorkersClaimDifferentMessages() throws Exception {
        try (var rig = new Rig(2, Duration.ofMinutes(1))) {
            for (int i = 0; i < 8; i++) rig.submit("compete-" + i);
            var otherStore = new com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence(rig.source, rig.fixture.codec, rig.db);
            var other = new InvocationDispatchWorker(otherStore, otherStore, rig.coordinator, settings(2, Duration.ofMinutes(1)), rig.timer, rig.notifier);
            try {
                rig.worker.start();
                other.start();
                await(() -> rig.started.size() == 4);
                for (int i = 0; i < 4; i++) rig.started.get(i).completion.tryEmitEmpty();
                await(() -> rig.started.size() == 8);
                assertEquals(8, rig.started.stream().map(t -> t.message.messageId()).distinct().count());
                assertEquals(2, rig.started.stream().map(t -> t.message.workerId()).distinct().count());
                assertTrue(rig.maxActive.get() <= 4);
            } finally {
                other.stop();
            }
        }
    }

    @Test
    public void failedTaskFreesSlotWithoutAcknowledgingFailedMessage() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.submit("fails");
            rig.submit("after-failure");
            rig.worker.start();
            await(() -> rig.started.size() == 1);
            var failed = rig.started.getFirst();
            failed.completion.tryEmitError(new IllegalStateException("模拟基础设施故障"));
            await(() -> rig.started.size() == 2);
            assertTrue(failed.runtime.cancellation().isCancelled());
            assertEquals(0, rig.jdbc.queryForObject("SELECT delivered FROM arte_ai_outbox WHERE message_key=?",
                    Integer.class, failed.message.messageId()).intValue());
            assertEquals(1, rig.maxActive.get());
            assertTrue(rig.worker.isRunning());
        }
    }

    @Test
    public void deferredTaskKeepsDurableDelayAndFreesSlotForEligibleWork() throws Exception {
        try (var rig = new Rig(1, Duration.ofMinutes(1))) {
            rig.submit("defers");
            rig.submit("after-deferral");
            rig.worker.start();
            await(() -> rig.started.size() == 1);
            var deferred = rig.started.getFirst();
            deferred.completion.tryEmitError(new DispatchDeferredException(Duration.ofMinutes(1)));
            await(() -> rig.started.size() == 2);
            var row = rig.jdbc.queryForMap("SELECT worker_id,delivered,lease_until FROM arte_ai_outbox WHERE message_key=?",
                    deferred.message.messageId());
            assertNull(row.get("worker_id"));
            assertEquals(0, ((Number) row.get("delivered")).intValue());
            assertTrue(((Number) row.get("lease_until")).longValue() > System.currentTimeMillis());
            assertEquals(1, rig.maxActive.get());
        }
    }
}
