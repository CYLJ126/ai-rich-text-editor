package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.execution.DefaultExecutionEventService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.application.tool.approval.ToolApprovalPolicy;
import com.arte.ainew.application.tool.approval.ToolApprovalService;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.application.tool.workflow.WorkflowToolService;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.persistence.mybatis.MybatisToolApprovalStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;

/**
 * 逻辑步骤检查点由测试宿主保存，真实 H2／MyBatis／受理／Worker／结果查询验证 TOOL 消费契约。
 * 不模拟工作流引擎已上线，不访问模型、业务库或外部工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 13:40 ✾
 */
public class WorkflowToolIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f;
        final AtomicBoolean readable = new AtomicBoolean(true);
        final DefaultExecutionEventService events;

        Rig() throws Exception {
            this(new ToolExecutionFixture());
        }

        Rig(ToolExecutionFixture fixture) {
            f = fixture;
            var properties = new NewAiProperties(false, "appDataSource", "release", null, null,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
            var auth = new AdmissionAuthorization((name, tenant, workspace, scopes) -> Mono.just(new ExecutionAuthorization(
                    new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "read-grant")),
                    properties, f.clock, null, f.io, (invocation, context, operation) -> Mono.defer(() -> {
                if (!readable.get()) return Mono.error(new AccessDeniedException("Test read revoked"));
                return Mono.just(new ToolInvocationAccess.Permit() {
                    public ExecutionAuthorization authorization() {
                        return context.authorization();
                    }

                    public void requireInCurrentTransaction() {
                        throw new AssertionError("Read must not write");
                    }
                });
            }));
            events = new DefaultExecutionEventService(auth, f.executions, f.executions, f.payloads);
        }

        WorkflowToolService service() {
            return service(f.service);
        }

        WorkflowToolService service(ToolAdmissionService admission) {
            return new WorkflowToolService(admission, f.store, events, f.clock);
        }

        ToolWorkflowStep step() {
            return new ToolWorkflowStep(new ToolWorkflowStep.Key("workflow-1", "tool-node", "operation-1"), f.input());
        }

        ExecutionContext read() {
            var c = f.context("read-request", "unused");
            return new ExecutionContext(c.executionId(), c.traceId(), new ExecutionAuthorization(c.authorization().principal(),
                    c.authorization().tenantId(), c.authorization().workspaceId(), Set.of("ai:read"), "read-grant"),
                    c.deadline(), null, null, c.releaseRef(), null);
        }

        public void close() throws Exception {
            f.close();
        }
    }

    @Test
    public void lostAcceptanceAndConsumerRestartReuseCommittedIdentityResultAndOriginalDeadline() throws Exception {
        try (var r = new Rig()) {
            var step = r.step(); // 模拟宿主首次提交前已保存的不可变步骤。
            var first = r.service().submit(step, r.f.context("schedule-1", "attempt-1")).block(WAIT);
            var original = r.f.invocation(first.executionId());
            assertNull(original.request().context().parentExecutionId());
            assertEquals(0, r.f.invocations.get());
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var terminal = r.f.invocation(first.executionId());
            r.f.changeConfiguration();
            // 不保留首个服务实例或受理响应；重调度仅依赖保存的步骤，当前请求 ID／trace／期限均已刷新。
            var replay = r.service().submit(step, r.f.context("schedule-2", "attempt-2")).block(WAIT);
            assertEquals(first, replay);
            var restored = r.service().snapshot(step.key(), r.read()).block(WAIT);
            assertEquals(terminal, restored.invocation());
            assertEquals(original.request().options().deadline(), restored.invocation().request().options().deadline());
            assertEquals(original.request().binding(), restored.invocation().request().binding());
            assertEquals(new InvocationResult.Tool(ToolExecutionFixture.success(r.f.invoked.get())), restored.result());
            assertEquals(1, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(1, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void concurrentSchedulersAcceptOnlyOneInvocationAndDispatch() throws Exception {
        try (var r = new Rig(); var pool = Executors.newFixedThreadPool(4)) {
            var barrier = new CyclicBarrier(4);
            var step = r.step();
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(index -> pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return r.service().submit(step, r.f.context("scheduler-" + index, "attempt-" + index)).block(WAIT);
            })).toList();
            var first = tasks.getFirst().get(20, TimeUnit.SECONDS);
            for (var task : tasks) assertEquals(first, task.get(20, TimeUnit.SECONDS));
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(1, r.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
            assertEquals(0, r.f.count("arte_ai_attempt"));
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void sameOperationRejectsChangedArgumentsPolicyAccountAndBindingWithoutNewDispatch() throws Exception {
        try (var r = new Rig()) {
            var step = r.step();
            r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            var another = r.f.createBinding("other", ToolBindingSettings.VersionPolicy.FOLLOW_COMPATIBLE);
            var input = step.input();
            for (var changed : List.of(
                    new ToolAdmissionInput(input.bindingId(), object("text", text("changed")), null, null, null),
                    new ToolAdmissionInput(input.bindingId(), input.arguments(), Duration.ofSeconds(2), null, null),
                    new ToolAdmissionInput(input.bindingId(), input.arguments(), null, 1, null),
                    new ToolAdmissionInput(input.bindingId(), input.arguments(), null, null, 128L),
                    new ToolAdmissionInput(input.bindingId(), input.arguments(), null, null, null, "another-account"),
                    new ToolAdmissionInput(another.bindingId(), input.arguments(), null, null, null))) {
                assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, assertThrows(AdmissionException.class,
                        () -> r.service().submit(new ToolWorkflowStep(step.key(), changed), r.f.context("reschedule", "new-attempt")).block(WAIT)).getResultCode());
            }
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void differentBindingRaceStillCannotCreateTwoExecutionsForOneLogicalOperation() throws Exception {
        try (var r = new Rig(); var pool = Executors.newFixedThreadPool(2)) {
            var other = r.f.createBinding("other", ToolBindingSettings.VersionPolicy.FOLLOW_COMPATIBLE);
            var step = r.step();
            var competing = new ToolWorkflowStep(step.key(), new ToolAdmissionInput(other.bindingId(), object(), null, null, null));
            var barrier = new CyclicBarrier(2);
            var tasks = List.of(step, competing).stream().map(value -> pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    r.service().submit(value, r.f.context("race-" + value.input().bindingId(), "attempt")).block(WAIT);
                    return true;
                } catch (AdmissionException conflict) {
                    assertEquals(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, conflict.getResultCode());
                    return false;
                }
            })).toList();
            assertNotEquals(tasks.getFirst().get(20, TimeUnit.SECONDS), tasks.getLast().get(20, TimeUnit.SECONDS));
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(1, r.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Integer.class).intValue());
        }
    }

    @Test
    public void pendingAndMissingRecoveryNeverSubmitOrCreateAttempts() throws Exception {
        try (var r = new Rig()) {
            var step = r.step();
            assertEquals(ResultCodeEnum.AI_NOT_FOUND, assertThrows(AdmissionException.class,
                    () -> r.service().snapshot(step.key(), r.read()).block(WAIT)).getResultCode());
            assertEquals(0, r.f.count("arte_ai_invocation"));
            var accepted = r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            for (int i = 0; i < 3; i++) {
                var snapshot = r.service().snapshot(step.key(), r.read()).block(WAIT);
                assertEquals(accepted.executionId(), snapshot.invocationId());
                assertEquals(Invocation.State.ACCEPTED, snapshot.invocation().state());
                assertNull(snapshot.result());
            }
            assertEquals(1, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void recoveryWhileWorkerIsRunningCannotStartAnotherBusinessCall() throws Exception {
        try (var r = new Rig()) {
            var started = new CountDownLatch(1);
            var result = reactor.core.publisher.Sinks.<ToolResult>one();
            r.f.operation = call -> {
                started.countDown();
                return result.asMono();
            };
            var step = r.step();
            var accepted = r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            var dispatch = r.f.worker(r.f.dispatcher).pollOnce().toFuture();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(Invocation.State.RUNNING, r.service().snapshot(step.key(), r.read()).block(WAIT).invocation().state());
            assertEquals(accepted, r.service().submit(step, r.f.context("reschedule", "attempt-2")).block(WAIT));
            assertEquals(1, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_attempt"));
            assertEquals(reactor.core.publisher.Sinks.EmitResult.OK, result.tryEmitValue(ToolExecutionFixture.success(r.f.invoked.get())));
            dispatch.get(15, TimeUnit.SECONDS);
            assertEquals(Invocation.State.SUCCEEDED, r.service().snapshot(step.key(), r.read()).block(WAIT).invocation().state());
        }
    }

    @Test
    public void completedInvocationCanReplayAfterItsOriginalDeadlineWithoutExtendingIt() throws Exception {
        try (var r = new Rig()) {
            var step = r.step();
            var accepted = r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var original = r.f.invocation(accepted.executionId());
            var later = Clock.offset(r.f.clock, Duration.ofHours(1));
            assertTrue(later.instant().isAfter(original.request().options().deadline()));
            var admission = r.f.service(r.f.store, r.f.access, later);
            var c = r.f.context("recover", "attempt-2");
            var current = new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), later.instant().plusSeconds(30), null, null, "release", "attempt-2");
            assertEquals(accepted, new WorkflowToolService(admission, r.f.store, r.events, later).submit(step, current).block(WAIT));
            assertEquals(original, r.f.invocation(accepted.executionId()));
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void revokedReadAndAdmissionOrOtherWorkspaceCannotRecoverOrReplayFacts() throws Exception {
        try (var r = new Rig()) {
            var step = r.step();
            var accepted = r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            r.readable.set(false);
            assertEquals(ResultCodeEnum.AI_NOT_FOUND, assertThrows(AdmissionException.class,
                    () -> r.service().snapshot(step.key(), r.read()).block(WAIT)).getResultCode());
            r.readable.set(true);
            var read = r.read();
            var other = new ExecutionContext(read.executionId(), read.traceId(), new ExecutionAuthorization(read.authorization().principal(),
                    "tenant", "other-workspace", Set.of("ai:read"), "other-grant"), read.deadline(), null, null, "release", null);
            assertEquals(ResultCodeEnum.AI_NOT_FOUND, assertThrows(AdmissionException.class,
                    () -> r.service().snapshot(step.key(), other).block(WAIT)).getResultCode());
            r.f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            assertThrows(AccessDeniedException.class, () -> r.service().submit(step, r.f.context("again", "again")).block(WAIT));
            assertEquals(Invocation.State.SUCCEEDED, r.f.invocation(accepted.executionId()).state());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void explicitLoopIterationCreatesAnotherOperationWhileSchedulingAttemptDoesNot() throws Exception {
        try (var r = new Rig()) {
            var first = r.step();
            var second = new ToolWorkflowStep(new ToolWorkflowStep.Key(first.key().workflowRunId(), first.key().nodeId(), "operation-2"), first.input());
            var a = r.service().submit(first, r.f.context("attempt-1", "attempt-1")).block(WAIT);
            var replay = r.service().submit(first, r.f.context("attempt-2", "attempt-2")).block(WAIT);
            var b = r.service().submit(second, r.f.context("attempt-3", "attempt-3")).block(WAIT);
            assertEquals(a, replay);
            assertNotEquals(a.executionId(), b.executionId());
            assertEquals(2, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void paidWorkflowConsumptionUsesExistingLedgerAndNeverChargesReplayTwice() throws Exception {
        try (var r = new Rig(new ToolExecutionFixture(ToolBillingIntegrationTest.price("0.5")))) {
            r.f.operation = call -> Mono.just(ToolBillingIntegrationTest.success(call,
                    new ToolCharge(ToolBillingIntegrationTest.money("0.5"), "workflow-bill")));
            var step = r.step();
            r.service().submit(step, r.f.context("first", "first")).block(WAIT);
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            r.service().submit(step, r.f.context("again", "again")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, r.service().snapshot(step.key(), r.read()).block(WAIT).invocation().state());
            assertEquals(1, r.f.count("arte_ai_reservation"));
            assertEquals(1, r.f.count("arte_ai_tool_bill"));
            assertEquals(ToolBillingIntegrationTest.money("0.5"), ToolBillingIntegrationTest.account(r.f).charged());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void rejectsInventedParentOrInheritedRunAccountAndExpiredEntryBeforeWriting() throws Exception {
        try (var r = new Rig()) {
            var c = r.f.context("request", "attempt");
            for (var bad : List.of(
                    new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), c.deadline(), "fake-workflow-root", null, "release", "attempt"),
                    new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), c.deadline(), null, "fake-run-account", "release", "attempt")))
                assertEquals(ResultCodeEnum.AI_TOOL_POLICY_DENIED, assertThrows(AdmissionException.class,
                        () -> r.service().submit(r.step(), bad).block(WAIT)).getResultCode());
            var expired = new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), r.f.clock.instant().minusSeconds(1), null, null, "release", "attempt");
            assertEquals(ResultCodeEnum.AI_DEADLINE_EXCEEDED, assertThrows(AdmissionException.class,
                    () -> r.service().submit(r.step(), expired).block(WAIT)).getResultCode());
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void identityIsOwnerScopedAndCannotAliasDelimitedIdentifiers() {
        var owner = new ExecutionOwner("tenant", "workspace", "subject");
        var key = new ToolWorkflowStep.Key("run", "a:b", "c");
        assertNotEquals(key.invocationId(owner), new ToolWorkflowStep.Key("run", "a", "b:c").invocationId(owner));
        for (var other : List.of(new ExecutionOwner("other", "workspace", "subject"), new ExecutionOwner("tenant", "other", "subject"),
                new ExecutionOwner("tenant", "workspace", "other")))
            assertNotEquals(key.invocationId(owner), key.invocationId(other));
        assertThrows(IllegalArgumentException.class, () -> new ToolWorkflowStep.Key("run", "node", " "));
    }

    @Test
    public void approvedWriteUnknownSurvivesRecoveryWithoutAnotherAttemptOrBusinessCall() throws Exception {
        var policy = new ToolExecutionPolicy(Duration.ofMinutes(2), 1, Duration.ZERO, 1024, 4096, ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION);
        var limits = new ToolExecutionPolicy(Duration.ofMinutes(2), 1, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE);
        var risk = new ToolRiskProfile(CapabilityDescriptor.SideEffect.RESOURCE_WRITE, false, ToolRiskProfile.Idempotency.DOMAIN_OPERATION_KEY,
                Set.of("resource:read"), Set.of());
        try (var r = new Rig(new ToolExecutionFixture(policy, risk, limits))) {
            var access = new ToolAdmissionAccess() {
                public Mono<Permit> authorizeFreeReadOnly(ToolAdmissionInput i, ExecutionContext c, ToolBindingResolution b) {
                    return Mono.error(new AssertionError("Write test"));
                }

                public Mono<Permit> authorizeFreeWrite(ToolAdmissionInput i, ExecutionContext c, ToolBindingResolution b) {
                    return r.f.access.authorizeFreeReadOnly(i, c, b); // 测试权限事实；不代表平台写工具授权。
                }
            };
            var approvals = new MybatisToolApprovalStore(r.f.source, r.f.codec, r.f.io, r.f.executions, r.f.bindings);
            ToolApprovalAccess confirmation = new ToolApprovalAccess() {
                public Mono<PlanPermit> authorizePlan(ToolExecutionSnapshot s, ToolApproval.Plan p, ExecutionContext c) {
                    return Mono.just((actual, plan) -> assertTrue(TransactionSynchronizationManager.hasResource(r.f.source)));
                }

                public Mono<Permit> authorize(ToolApproval a, ToolExecutionSnapshot s, ExecutionContext c, boolean deciding) {
                    return Mono.just((actual, fixed) -> assertTrue(TransactionSynchronizationManager.hasResource(r.f.source)));
                }
            };
            var admission = new ToolAdmissionService(r.f.bindingService, r.f.catalog, r.f.store, access, new ToolSchemaValidator(), limits, r.f.metadata, r.f.clock,
                    new ToolApprovalPolicy(new NewAiToolApprovalProperties(true, null, 0), confirmation, approvals));
            var gateway = new com.arte.ainew.application.gateway.DefaultToolGateway(r.f.bindingService, r.f.catalog, r.f.registry,
                    call -> Mono.just(call.call().runtime().execution().authorization()), new ToolSchemaValidator(), r.f.codec, r.f.metadata, r.f.clock,
                    approvals::requireApproved);
            var dispatcher = new ToolDispatcher(r.f.executions, r.f.executions, r.f.executions, r.f.store, r.f.bindingService, access,
                    gateway, r.f.payloads, r.f.codec, r.f.metadata, r.f.clock, r.f.executionProperties.attemptLease());
            var service = r.service(admission);
            var c = r.f.context("write", "attempt-1");
            var context = new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), r.f.clock.instant().plusSeconds(120), null, null, "release", "attempt-1");
            var step = r.step();
            var accepted = service.submit(step, context).block(WAIT);
            assertEquals(Invocation.State.WAITING_APPROVAL, service.snapshot(step.key(), r.read()).block(WAIT).invocation().state());
            assertEquals(accepted, service.submit(step, context).block(WAIT));
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(0, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_tool_approval"));
            var approval = approvals.find(r.f.owner, accepted.executionId()).block(WAIT);
            new ToolApprovalService(approvals, r.f.store, r.f.executions, confirmation).decide(approval.approvalId(), approval.version(), approval.subjectDigest(),
                    ToolApprovalCommand.Decision.APPROVE, "confirm-write", context).block(WAIT);
            r.f.operation = call -> Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(),
                    ExecutionError.SideEffect.POSSIBLE, new ExecutionError("WRITE_OUTCOME_UNKNOWN", ExecutionError.Phase.INVOCATION, false,
                    ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace-write")));
            r.f.worker(dispatcher).pollOnce().block(WAIT);
            var unknown = r.f.invocation(accepted.executionId());
            assertEquals(Invocation.State.UNKNOWN, unknown.state());
            assertEquals(ExecutionError.SideEffect.POSSIBLE, unknown.error().sideEffect());
            for (int attempt = 0; attempt < 3; attempt++) {
                assertEquals(unknown, r.service(admission).snapshot(step.key(), r.read()).block(WAIT).invocation());
                assertEquals(accepted, r.service(admission).submit(step, context).block(WAIT));
                r.f.worker(dispatcher).pollOnce().block(WAIT);
            }
            assertEquals(1, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_attempt"));
            assertEquals(1, r.f.count("arte_ai_tool_approval"));
        }
    }
}
