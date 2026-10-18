package com.arte.ainew.tool.execution;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.LocalToolCall;
import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.spi.persistence.ExecutionOutboxStore;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
 * 真实受理、网关、领域适配、MyBatis 和通用 Worker；模型／业务数据库／Redis 均不参与。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:00 ✾
 */
public class ToolDispatchIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Test
    public void workerCommitsTypedResultTerminalEventAndAcknowledgesWithoutBudgetOrModel() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            assertEquals(1, (int) f.worker(f.dispatcher).pollOnce().block(WAIT));
            var invocation = f.invocation("one");
            assertEquals(Invocation.State.SUCCEEDED, invocation.state());
            assertEquals(Attempt.State.SUCCEEDED, f.attempt("one").state());
            assertEquals(Attempt.Dispatch.CONFIRMED, f.attempt("one").dispatch());
            assertNull(f.attempt("one").budgetReservationId());
            assertEquals("tool-result", invocation.result().resultType());
            assertFalse(invocation.result().partial());
            assertEquals(new InvocationResult.Tool(ToolExecutionFixture.success(f.invoked.get())), f.payloads.find(f.owner, "one", invocation.result()).block(WAIT));
            var events = f.executions.replay(f.owner, new ExecutionEvent.Cursor("one", 0), 100).block(WAIT).value().events();
            assertEquals(1, events.stream().filter(event -> event.payload() instanceof ExecutionPayload.Terminal).count());
            assertEquals(invocation.result(), ((ExecutionPayload.Terminal) events.getLast().payload()).result());
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
            assertEquals(0, f.count("arte_ai_account"));
            assertEquals(0, f.count("arte_ai_reservation"));
            assertEquals(0, f.count("arte_ai_settlement"));
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void configChangeAndCompatibleUpgradeDoNotReplaceAdmittedMaterial() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("old");
            f.changeConfiguration();
            f.publish(f.v2, f.v1);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(f.v1.revision().tool().definition(), f.invoked.get().call().request().input().tool());
            assertEquals(object(), f.invoked.get().binding().effectiveConfiguration());
            f.submit("new");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(f.v2.revision().tool().definition(), f.invoked.get().call().request().input().tool());
            assertEquals(object("text", text("updated-config")), f.invoked.get().binding().effectiveConfiguration());
            assertEquals(2, f.invocations.get());
        }
    }

    @Test
    public void replayedAdmissionProducesOnlyOneBusinessInvocation() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var first = f.service.submit(f.input(), f.context("one", "same")).block(WAIT);
            assertEquals(first, f.service.submit(f.input(), f.context("other", "same")).block(WAIT));
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void terminalDispatchReplayDoesNotInvokeOrAppendAnotherTerminal() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("worker");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            int events = f.count("arte_ai_event");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(1, f.invocations.get());
            assertEquals(events, f.count("arte_ai_event"));
        }
    }

    @Test
    public void concurrentDuplicateClaimObtainsOnlyOneSendPermit() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("worker");
            var barrier = new CyclicBarrier(2);
            f.authorizationHook = ignored -> {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception failed) {
                    throw new AssertionError(failed);
                }
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(() -> dispatchOutcome(f, message));
                var b = executor.submit(() -> dispatchOutcome(f, message));
                assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            }
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void duplicateDuringActiveAttemptCannotSubscribeGatewayAgain() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var entered = new CountDownLatch(1);
            var ending = Sinks.<ToolResult>one();
            f.operation = call -> {
                entered.countDown();
                return ending.asMono();
            };
            f.submit("one");
            var message = f.claim("worker");
            var first = f.dispatcher.dispatch(message, f.runtime("one")).toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(AdmissionException.class, () -> f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(1, f.invocations.get());
            ending.tryEmitValue(ToolExecutionFixture.success(f.invoked.get()));
            first.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void hostQualificationRevokedAfterAdmissionCompletesBeforeAttempt() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.jdbc.update("UPDATE tool_test_permission SET enabled=false");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.invocations.get());
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void bindingDisabledBeforeDispatchPreventsAttempt() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.disable();
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void finalGatewayResourceDenialHasKnownNoExecutionResult() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.executionAllowed.set(false);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            var invocation = f.invocation("one");
            assertEquals(Invocation.State.FAILED, invocation.state());
            assertEquals(ExecutionError.SideEffect.NONE, invocation.error().sideEffect());
            assertTrue(invocation.result().partial());
            assertEquals(0, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void narrowedFreshGrantIsPassedToDomainWithoutChangingAcceptedAuthorization() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.permissions = old -> new ExecutionAuthorization(old.principal(), old.tenantId(), old.workspaceId(), old.scopes(), "new-grant");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals("new-grant", f.invoked.get().call().runtime().execution().authorization().grantRef());
            assertEquals("grant-one", f.invocation("one").request().context().authorization().grantRef());
        }
    }

    @Test
    public void expandingHostScopeCannotReachPreparation() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.permissions = old -> new ExecutionAuthorization(old.principal(), old.tenantId(), old.workspaceId(), Set.of("ai:invoke", "resource:read", "extra"), "changed");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void expiredLogicalDeadlineIsCommittedWithoutAttempt() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var dispatcher = new ToolDispatcher(f.executions, f.executions, f.executions, f.store, f.bindingService, f.access, f.gateway, f.payloads,
                    f.codec, f.metadata, Clock.offset(f.clock, Duration.ofMinutes(1)), f.executionProperties.attemptLease());
            f.worker(dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void authorizationWaitCannotKeepPreflightAlivePastDeadline() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var input = new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(1), null, null);
            f.service.submit(input, f.context("one", "one")).block(WAIT);
            var dispatcher = new ToolDispatcher(f.executions, f.executions, f.executions, f.store, f.bindingService,
                    (args, context, plan) -> Mono.never(), f.gateway, f.payloads, f.codec, f.metadata, f.clock, f.executionProperties.attemptLease());
            f.worker(dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void knownBusinessFailureIsDurableAndDoesNotAutomaticallyRetry() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.operation = call -> Mono.just(failure(call, true));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertTrue(f.invocation("one").error().retryable());
            assertEquals(1, f.count("arte_ai_attempt"));
            assertEquals(1, f.invocations.get());
        }
    }

    @Test
    public void adapterExceptionAndEmptyResultRemainUnknownWithoutRetry() throws Exception {
        for (boolean empty : List.of(false, true))
            try (var f = new ToolExecutionFixture()) {
                f.operation = call -> {
                    if (empty) return Mono.empty();
                    throw new IllegalStateException("untrusted detail");
                };
                f.submit("one");
                f.worker(f.dispatcher).pollOnce().block(WAIT);
                assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
                assertEquals(ExecutionError.Certainty.UNKNOWN, f.invocation("one").error().certainty());
                assertEquals(1, f.invocations.get());
                assertEquals(1, delivered(f));
            }
    }

    @Test
    public void invalidSchemaOutputCannotBeCommittedAsSuccess() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.operation = call -> Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                    object("text", object()), List.of(), List.of(), ExecutionError.SideEffect.NONE, null));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            assertTrue(f.invocation("one").result().partial());
        }
    }

    @Test
    public void customGatewayCannotSubmitWrongCallIdentityOrThrowAsKnownFailure() throws Exception {
        for (boolean throwsError : List.of(false, true))
            try (var f = new ToolExecutionFixture()) {
                var dispatcher = f.dispatcher(f.executions, f.payloads, call -> throwsError ? Mono.error(new IllegalStateException("unsafe"))
                        : Mono.just(new ToolResult("wrong-call", ToolResult.Outcome.SUCCEEDED, object(), List.of(), List.of(), ExecutionError.SideEffect.NONE, null)));
                f.submit("one");
                f.worker(dispatcher).pollOnce().block(WAIT);
                assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
                assertEquals(0, f.invocations.get());
            }
    }

    @Test
    public void timeoutAfterBusinessEntryPreservesUnknownOutcome() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.operation = call -> Mono.never();
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(1), null, null), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            assertEquals(1, f.invocations.get());
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void tinyOutputLimitStillPersistsSafeErrorWithoutOversizedResult() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), null, null, 1L), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            assertNull(f.invocation("one").result());
            assertNotNull(f.invocation("one").error());
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void resultStorageFailureLeavesDispatchUnacknowledgedAndNeverRepeatsBusiness() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.jdbc.execute("ALTER TABLE arte_ai_result ADD CONSTRAINT reject_result CHECK(1=0)");
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertEquals(0, f.count("arte_ai_result"));
            f.jdbc.execute("ALTER TABLE arte_ai_result DROP CONSTRAINT reject_result");
            f.expireAttempt("one");
            f.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            assertEquals(1, f.invocations.get());
        }
    }

    @Test
    public void emptyResultStoreOutcomeCannotAcknowledgeUnfinishedInvocation() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var results = proxy(ExecutionResultStore.class, f.payloads, (method, args) -> method.equals("put") ? Mono.empty() : null);
            f.submit("one");
            f.worker(f.dispatcher(f.executions, results, f.gateway)).pollOnce().block(WAIT);
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertEquals(1, f.invocations.get());
        }
    }

    @Test
    public void emptyPreparationOutcomeCannotLoseAcceptedWork() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var preparation = proxy(DispatchPreparationStore.class, f.executions, (method, args) -> Mono.empty());
            var dispatcher = new ToolDispatcher(f.executions, f.executions, preparation, f.store, f.bindingService, f.access,
                    f.gateway, f.payloads, f.codec, f.metadata, f.clock, f.executionProperties.attemptLease());
            f.submit("one");
            f.worker(dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.ACCEPTED, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void emptyCompletionOutcomeCannotBeAcknowledgedAsSuccess() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var storage = proxy(ExecutionStore.class, f.executions, (method, args) -> method.equals("commitCompletion") ? Mono.empty() : null);
            f.submit("one");
            f.worker(f.dispatcher(storage, f.payloads, f.gateway)).pollOnce().block(WAIT);
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertEquals(1, f.count("arte_ai_result"));
        }
    }

    @Test
    public void terminalTransactionFailureRollsBackAttemptStateAndTerminalEvent() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.jdbc.execute("ALTER TABLE arte_ai_invocation ADD CONSTRAINT reject_success CHECK(workspace_snapshot NOT LIKE '%\"state\":\"SUCCEEDED\"%')");
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(Attempt.State.RUNNING, f.attempt("one").state());
            assertEquals(1, f.count("arte_ai_result"));
            assertEquals(0, delivered(f));
            var events = f.executions.replay(f.owner, new ExecutionEvent.Cursor("one", 0), 100).block(WAIT).value().events();
            assertFalse(events.stream().anyMatch(event -> event.payload() instanceof ExecutionPayload.Terminal));
        }
    }

    @Test
    public void acknowledgeFailureReplaysTerminalWithoutCallingBusinessAgain() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var fail = new AtomicBoolean(true);
            var outbox = proxy(ExecutionOutboxStore.class, f.executions, (method, args) -> method.equals("acknowledge") && fail.getAndSet(false)
                    ? Mono.error(new IllegalStateException("test ACK unavailable")) : null);
            var worker = new InvocationDispatchWorker(outbox, f.executions, f.coordinator(f.dispatcher), f.executionProperties, f.timer);
            f.submit("one");
            worker.pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(0, delivered(f));
            int events = f.count("arte_ai_event");
            f.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
            worker.pollOnce().block(WAIT);
            assertEquals(1, delivered(f));
            assertEquals(1, f.invocations.get());
            assertEquals(events, f.count("arte_ai_event"));
        }
    }

    @Test
    public void workerRenewsBothLeasesDuringLongToolInvocation() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.operation = call -> Mono.delay(Duration.ofMillis(4200)).map(ignored -> ToolExecutionFixture.success(call));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertTrue(f.attempt("one").version() >= 3);
            assertEquals(1, f.invocations.get());
            assertEquals(1, delivered(f));
        }
    }

    @Test
    public void attemptLeaseLossCancelsGatewayAndDoesNotAcknowledgeOrDeclareSuccess() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            var stopped = new AtomicBoolean();
            f.operation = call -> Mono.<ToolResult>never().doOnCancel(() -> stopped.set(true));
            var storage = proxy(ExecutionStore.class, f.executions, (method, args) -> method.equals("renewLease")
                    ? Mono.just(StoreOutcome.rejected(StoreOutcome.Code.LEASE_LOST)) : null);
            f.submit("one");
            f.worker(f.dispatcher(storage, f.payloads, f.gateway)).pollOnce().block(WAIT);
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertTrue(stopped.get());
            assertEquals(1, f.invocations.get());
        }
    }

    @Test
    public void missingSnapshotCannotFallThroughToToolInvocationOrFalseCompletion() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.jdbc.update("DELETE FROM arte_ai_tool_execution_snapshot");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.ACCEPTED, f.invocation("one").state());
            assertEquals(0, delivered(f));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void wrongRuntimeOwnerAndExpandedScopesAreRejectedWithoutMutation() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("worker");
            var before = f.runtime("one").execution();
            var old = before.authorization();
            for (var auth : List.of(new ExecutionAuthorization(old.principal(), old.tenantId(), "another", old.scopes(), old.grantRef()),
                    new ExecutionAuthorization(old.principal(), old.tenantId(), old.workspaceId(), Set.of("ai:invoke", "resource:read", "extra"), old.grantRef()))) {
                var context = new ExecutionContext(before.executionId(), before.traceId(), auth, before.deadline(), null, null, before.releaseRef(), before.idempotencyKey());
                assertThrows(AdmissionException.class, () -> f.dispatcher.dispatch(message, ExecutionRuntimeContext.start(context)).block(WAIT));
            }
            assertEquals(Invocation.State.ACCEPTED, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void generationOnlyRoutingLeavesToolUnchangedWhenToolDispatcherIsUnavailable() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("worker");
            var error = assertThrows(AdmissionException.class, () -> f.coordinator(null).dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY, error.getResultCode());
            assertEquals(Invocation.State.ACCEPTED, f.invocation("one").state());
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void directCoordinatorSubmitDoesNotBypassToolAdmission() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var snapshot = f.store.snapshot(f.owner, "one").block(WAIT);
            var error = assertThrows(AdmissionException.class, () -> f.coordinator(f.dispatcher)
                    .submit(new InvocationSubmission<>(snapshot.request(), null, null, null, null)).block(WAIT));
            assertEquals(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY, error.getResultCode());
            assertEquals(1, f.count("arte_ai_invocation"));
            assertEquals(0, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void expiredClaimCannotObtainFreePreparationOrCallGateway() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("worker");
            f.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
            assertThrows(AdmissionException.class, () -> f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void completionRejectsWrongResultTypeWrongCallAndCorruptPayload() throws Exception {
        for (int mode = 0; mode < 3; mode++)
            try (var f = new ToolExecutionFixture()) {
                f.submit("one");
                var ready = prepare(f, "one", f.claim("worker"));
                var snapshot = f.store.snapshot(f.owner, "one").block(WAIT);
                var tool = new ToolResult(mode == 1 ? "wrong-call" : snapshot.request().input().callId(), ToolResult.Outcome.SUCCEEDED,
                        object(), List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
                var reference = f.payloads.put(f.owner, "one", ready.attempt().attemptId(), "tool-result", new InvocationResult.Tool(tool)).block(WAIT).value();
                var actual = mode == 0 ? new ResultRef(reference.resultId(), "model-result", 1, reference.contentDigest(), false) : reference;
                if (mode == 2) f.jdbc.update("UPDATE arte_ai_result SET workspace_snapshot='{}'");
                var command = new ExecutionCommands.Complete(ExecutionCommands.Guard.from(f.owner, ready.invocation(), ready.attempt()), "complete",
                        new ExecutionPayload.Terminal(Invocation.State.SUCCEEDED, actual, null), Usage.unknown(), null);
                if (mode == 2)
                    assertThrows(IllegalStateException.class, () -> f.executions.commitCompletion(command).block(WAIT));
                else
                    assertEquals(StoreOutcome.Code.INVALID_STATE, f.executions.commitCompletion(command).block(WAIT).code());
                assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
                assertEquals(Attempt.State.RUNNING, f.attempt("one").state());
            }
    }

    @Test
    public void completionCannotReferenceAnotherInvocationsResult() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var first = prepare(f, "one", f.claim("first"));
            var snapshot = f.store.snapshot(f.owner, "one").block(WAIT);
            var result = new ToolResult(snapshot.request().input().callId(), ToolResult.Outcome.SUCCEEDED, object(), List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
            var reference = f.payloads.put(f.owner, "one", first.attempt().attemptId(), "result", new InvocationResult.Tool(result)).block(WAIT).value();
            f.submit("two");
            var second = prepare(f, "two", f.claim("second"));
            var command = new ExecutionCommands.Complete(ExecutionCommands.Guard.from(f.owner, second.invocation(), second.attempt()), "complete",
                    new ExecutionPayload.Terminal(Invocation.State.SUCCEEDED, reference, null), Usage.unknown(), null);
            assertEquals(StoreOutcome.Code.INVALID_STATE, f.executions.commitCompletion(command).block(WAIT).code());
        }
    }

    private static DispatchPreparationStore.PreparedDispatch prepare(ToolExecutionFixture f, String id, OutboxMessage claim) {
        var invocation = f.invocation(id);
        return f.executions.prepareDispatch(new DispatchPreparationStore.Command(new ExecutionCommands.Version(f.owner, id, invocation.version()), claim,
                CanonicalJson.key(id, "attempt"), Duration.ofSeconds(3), new DispatchPreparationStore.Free())).block(WAIT).value();
    }

    private static boolean dispatchOutcome(ToolExecutionFixture f, OutboxMessage message) {
        try {
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            return true;
        } catch (AdmissionException expected) {
            return false;
        }
    }

    private static int delivered(ToolExecutionFixture f) {
        return f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH' AND delivered=1", Integer.class);
    }

    private static ToolResult failure(LocalToolCall call, boolean retryable) {
        var error = new ExecutionError("DOMAIN_NOT_AVAILABLE", ExecutionError.Phase.INVOCATION, retryable, ExecutionError.SideEffect.NONE,
                ExecutionError.Certainty.KNOWN, call.call().runtime().execution().traceId());
        return new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.FAILED, null, List.of(), List.of(), ExecutionError.SideEffect.NONE, error);
    }

    @FunctionalInterface
    private interface Intercept {
        Object call(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Object target, Intercept intercept) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            var replaced = intercept.call(method.getName(), args);
            if (replaced != null) return replaced;
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        }));
    }
}
