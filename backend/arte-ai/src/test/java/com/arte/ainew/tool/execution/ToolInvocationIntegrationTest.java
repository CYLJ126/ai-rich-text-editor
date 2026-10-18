package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.execution.*;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.execution.ToolDispatcher;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiInvocationController;
import com.arte.ainew.web.controller.NewAiToolInvocationController;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.text;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * TOOL 查询、耐久停止与崩溃恢复；仅临时 H2 和受控适配，不连接业务数据库或外部服务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
public class ToolInvocationIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(12);
    private static final String ROOT = "/ai-new/invocation/";

    private static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f = new ToolExecutionFixture();
        final AtomicBoolean read = new AtomicBoolean(true), cancel = new AtomicBoolean(true), commit = new AtomicBoolean(true);
        final NewAiProperties properties = new NewAiProperties(false, "appDataSource", "release", null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        final ExecutionAuthorizationResolver resolver = (name, tenant, workspace, scopes) -> Mono.just(new ExecutionAuthorization(
                new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "grant"));
        final ToolInvocationAccess access = (invocation, context, operation) -> Mono.defer(() -> {
            if (!(operation == ToolInvocationAccess.Operation.READ ? read : cancel).get())
                return Mono.error(new AccessDeniedException("Revoked"));
            return Mono.just(new ToolInvocationAccess.Permit() {
                public ExecutionAuthorization authorization() {
                    return context.authorization();
                }

                public void requireInCurrentTransaction() {
                    assertTrue(TransactionSynchronizationManager.hasResource(f.source));
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    if (!commit.get() || !cancel.get()) throw new AccessDeniedException("Revoked before commit");
                }
            });
        });
        final AdmissionAuthorization auth = new AdmissionAuthorization(resolver, properties, f.clock, null, f.io, access);
        final FixedControlCatalog catalog = new FixedControlCatalog(properties, auth, f.clock);
        final AdmissionInvocationCoordinator admission = new AdmissionInvocationCoordinator(auth, catalog, catalog, f.executions,
                f.payloads, f.executions, properties, f.clock);
        final DefaultInvocationCoordinator coordinator = new DefaultInvocationCoordinator(admission, null, f.executions, f.dispatcher);
        final DefaultExecutionControl control = new DefaultExecutionControl(auth, f.executions, coordinator);
        final InvocationBudgetStatusResolver budget = new InvocationBudgetStatusResolver(f.executions, f.executions);
        final LocalExecutionEventNotifier notifier = new LocalExecutionEventNotifier();
        final DefaultExecutionEventService events = new DefaultExecutionEventService(auth, f.executions, f.executions, f.payloads,
                notifier, f.clock, budget);

        Rig() throws Exception {
        }

        ExecutionContext context() {
            return new ExecutionContext("query", "trace-query", new ExecutionAuthorization(
                    new ExecutionPrincipal("subject", "alice", ExecutionPrincipal.Kind.USER), "tenant", "workspace", Set.of("ai:read"), "grant"),
                    f.clock.instant().plusSeconds(30), null, null, "release", "cancel-key");
        }

        ControlReceipt cancel(String key) {
            return control.request(new ExecutionControlRequest("command-" + key, "one", key,
                    ControlReceipt.Command.CANCEL), context()).block(WAIT);
        }

        MockMvc mvc() {
            var http = new NewAiHttpContext(new ExecutionContextFactory(resolver, f.clock), properties);
            return standaloneSetup(new NewAiInvocationController(control, events, http, properties, budget),
                    new NewAiToolInvocationController(control, http, properties)).setControllerAdvice(new ConversationExceptionHandler())
                    .setAsyncRequestTimeout(15000).build();
        }

        public void close() throws Exception {
            SecurityContextHolder.clearContext();
            f.close();
        }
    }

    @Test
    public void statusResultEventsAndBatchUseOriginalIdentityWithoutNewExecution() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, r.control.status("one", r.context()).block(WAIT).state());
            assertTrue(r.events.result("one", r.context()).block(WAIT) instanceof InvocationResult.Tool);
            assertEquals(InvocationBudgetState.NOT_RESERVED, r.budget.resolve(r.f.invocation("one")).block(WAIT));
            var cursor = new ExecutionEvent.Cursor("one", 0);
            var page = r.events.replay(cursor, 64, r.context()).block(WAIT).value();
            assertEquals(1, page.events().stream().filter(e -> e.kind() == ExecutionEvent.Kind.TERMINAL).count());
            var snapshots = r.events.snapshots(List.of("one", "missing"), r.context()).block(WAIT);
            assertNotNull(snapshots.getFirst().result());
            assertNull(snapshots.getLast().invocation());
            assertEquals(1, r.f.invocations.get());
            assertEquals(0, r.f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void stagedBytesStayInvisibleUntilTerminalReferenceCommits() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            prepare(r.f, r.f.claim("worker"));
            save(r.f, success(r.f));
            assertEquals(ResultCodeEnum.AI_RESULT_NOT_AVAILABLE, assertThrows(AdmissionException.class,
                    () -> r.events.result("one", r.context()).block(WAIT)).getResultCode());
            assertNull(r.events.snapshots(List.of("one"), r.context()).block(WAIT).getFirst().result());
        }
    }

    @Test
    public void revokedToolReadCannotBypassThroughGenericStatusResultReplayBatchOrWatch() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            r.read.set(false);
            assertThrows(AccessDeniedException.class, () -> r.control.status("one", r.context()).block(WAIT));
            assertThrows(AdmissionException.class, () -> r.events.result("one", r.context()).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> r.events.replay(new ExecutionEvent.Cursor("one", 0), 10, r.context()).block(WAIT));
            assertNull(r.events.snapshots(List.of("one"), r.context()).block(WAIT).getFirst().invocation());
            assertThrows(AccessDeniedException.class, () -> r.events.prepareWatch(new ExecutionEvent.Cursor("one", 0), r.context()).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> r.events.watch(new ExecutionEvent.Cursor("one", 0), r.context()).collectList().block(WAIT));
        }
    }

    @Test
    public void missingToolAccessIsDeniedRatherThanInheritingGenericRead() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var old = new AdmissionAuthorization(r.resolver, r.properties, r.f.clock);
            assertThrows(AccessDeniedException.class, () -> new DefaultExecutionControl(old, r.f.executions, r.coordinator).status("one", r.context()).block(WAIT));
        }
    }

    @Test
    public void queryOwnershipDoesNotExposeAnotherWorkspaceOrSubject() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var context = r.context();
            var foreign = new ExecutionContext(context.executionId(), context.traceId(), new ExecutionAuthorization(context.authorization().principal(),
                    "tenant", "other", Set.of("ai:read"), "other"), context.deadline(), null, null, "release", "key");
            assertEquals(ResultCodeEnum.AI_NOT_FOUND, assertThrows(AdmissionException.class, () -> r.control.status("one", foreign).block(WAIT)).getResultCode());
            assertNull(r.events.snapshots(List.of("one"), foreign).block(WAIT).getFirst().invocation());
        }
    }

    @Test
    public void queuedCancellationIsAtomicAndSameKeyReplaysWithoutToolCall() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var receipt = r.cancel("stop");
            assertEquals(ControlReceipt.Outcome.ACCEPTED, receipt.outcome());
            assertEquals(receipt, r.cancel("stop"));
            assertEquals(Invocation.State.CANCELLED, r.f.invocation("one").state());
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            assertEquals(0, r.f.invocations.get());
            assertEquals(0, r.f.count("arte_ai_attempt"));
            assertEquals(1, terminals(r.f));
        }
    }

    @Test
    public void cancelRechecksPermissionInsideOriginalTransactionIncludingReplay() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.commit.set(false);
            assertThrows(AccessDeniedException.class, () -> r.cancel("stop"));
            assertEquals(Invocation.State.ACCEPTED, r.f.invocation("one").state());
            assertFalse(r.f.executions.cancellationRequested(r.f.owner, "one").block(WAIT));
            r.commit.set(true);
            r.cancel("stop");
            r.commit.set(false);
            assertThrows(AccessDeniedException.class, () -> r.cancel("stop"));
        }
    }

    @Test
    public void cancelPermissionIsSeparateFromToolReadPermission() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.cancel.set(false);
            assertNotNull(r.control.status("one", r.context()).block(WAIT));
            assertThrows(AccessDeniedException.class, () -> r.cancel("stop"));
            r.cancel.set(true);
            r.read.set(false);
            assertEquals(ControlReceipt.Outcome.ACCEPTED, r.cancel("stop").outcome());
        }
    }

    @Test
    public void runningCancellationStopsSubscriptionAndKeepsUnknownWhenExecutionWasEntered() throws Exception {
        try (var r = new Rig()) {
            var entered = new CountDownLatch(1);
            var stopped = new AtomicBoolean();
            r.f.operation = call -> {
                entered.countDown();
                return Mono.<ToolResult>never().doOnCancel(() -> stopped.set(true));
            };
            r.f.submit("one");
            var task = r.f.worker(r.f.dispatcher).pollOnce().toFuture();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(ControlReceipt.Outcome.ACCEPTED, r.cancel("stop").outcome());
            task.get(10, TimeUnit.SECONDS);
            assertTrue(stopped.get());
            assertEquals(Invocation.State.UNKNOWN, r.f.invocation("one").state());
            assertEquals(ExecutionError.Certainty.UNKNOWN, r.f.invocation("one").error().certainty());
            assertEquals(1, r.f.invocations.get());
            assertEquals(ControlReceipt.Outcome.ALREADY_TERMINAL, r.cancel("another").outcome());
        }
    }

    @Test
    public void completedSuccessIsNotOverwrittenByLateCancellation() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var original = r.f.invocation("one");
            assertEquals(ControlReceipt.Outcome.ALREADY_TERMINAL, r.cancel("late").outcome());
            assertEquals(original, r.f.invocation("one"));
            assertEquals(1, terminals(r.f));
        }
    }

    @Test
    public void crashesAfterResultSaveRecoverWithoutCallingBusinessAgain() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            var storage = (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("commitCompletion"))
                            return Mono.error(new IllegalStateException("Injected completion crash"));
                        try {
                            return method.invoke(f.executions, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            assertThrows(IllegalStateException.class, () -> f.dispatcher(storage, f.payloads, f.gateway).dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(1, f.invocations.get());
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            var stale = f.attempt("one");
            f.expireAttempt("one");
            expireClaim(f);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
            assertTrue(f.attempt("one").fencingToken() > stale.fencingToken());
            assertEquals(1, terminals(f));
            assertFalse(f.executions.commitCompletion(new ExecutionCommands.Complete(
                    ExecutionCommands.Guard.from(f.owner, f.invocation("one"), stale), "late-worker",
                    new ExecutionPayload.Terminal(Invocation.State.SUCCEEDED, f.invocation("one").result(), null), Usage.unknown(), null)).block(WAIT).successful());
        }
    }

    @Test
    public void recoveryPreservesStoredFailedUnknownTimeoutAndCancellationFacts() throws Exception {
        for (String code : List.of("FAIL", "UNKNOWN", "TOOL_DEADLINE_EXCEEDED", "TOOL_CANCELLED")) {
            try (var f = new ToolExecutionFixture()) {
                f.submit("one");
                var message = f.claim("first");
                prepare(f, message);
                boolean unknown = code.equals("UNKNOWN");
                var error = new ExecutionError(code, ExecutionError.Phase.INVOCATION, false,
                        unknown ? ExecutionError.SideEffect.POSSIBLE : ExecutionError.SideEffect.NONE,
                        unknown ? ExecutionError.Certainty.UNKNOWN : ExecutionError.Certainty.KNOWN, "trace-one");
                var value = new ToolResult(callId(f), unknown ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.FAILED,
                        null, List.of(), List.of(), error.sideEffect(), error);
                save(f, value);
                f.expireAttempt("one");
                f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
                var expected = unknown ? Invocation.State.UNKNOWN : code.equals("TOOL_DEADLINE_EXCEEDED") ? Invocation.State.TIMED_OUT
                        : code.equals("TOOL_CANCELLED") ? Invocation.State.CANCELLED : Invocation.State.FAILED;
                assertEquals(expected, f.invocation("one").state());
                assertEquals(error, f.invocation("one").error());
                assertNotNull(f.invocation("one").result());
                assertEquals(0, f.invocations.get());
            }
        }
    }

    @Test
    public void recoveryNeedsNoNewInvocationEligibilityAndKeepsOriginalDeadline() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            prepare(f, message);
            save(f, success(f));
            var deadline = f.invocation("one").request().options().deadline();
            f.expireAttempt("one");
            f.disable();
            f.executionAllowed.set(false);
            f.authorizationHook = ignored -> {
                throw new AssertionError("Recovery must not authorize new execution");
            };
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(deadline, f.invocation("one").request().options().deadline());
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void possibleDispatchWithoutSavedResultBecomesUnknownAndIsNeverRetried() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            prepare(f, message);
            f.expireAttempt("one");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(0, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
            assertEquals(1, terminals(f));
        }
    }

    @Test
    public void corruptSavedBytesCannotBecomeSuccessOrBeSilentlyDiscarded() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            prepare(f, message);
            save(f, success(f));
            f.expireAttempt("one");
            f.jdbc.update("UPDATE arte_ai_result SET workspace_snapshot='{}'");
            assertThrows(IllegalStateException.class, () -> f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, terminals(f));
        }
    }

    @Test
    public void wrongCallOrOutputSchemaCannotBeRecovered() throws Exception {
        for (boolean wrongCall : List.of(true, false))
            try (var f = new ToolExecutionFixture()) {
                f.submit("one");
                var message = f.claim("first");
                prepare(f, message);
                save(f, new ToolResult(wrongCall ? "other" : callId(f), ToolResult.Outcome.SUCCEEDED,
                        wrongCall ? object("text", text("ok")) : object("text", object()), List.of(), List.of(), ExecutionError.SideEffect.NONE, null));
                f.expireAttempt("one");
                assertThrows(IllegalStateException.class, () -> f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT));
                assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            }
    }

    @Test
    public void recoveryTransactionFailureRollsBackFenceStateAndEventsThenCanResume() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            prepare(f, message);
            save(f, success(f));
            f.expireAttempt("one");
            var before = f.attempt("one");
            f.jdbc.execute("ALTER TABLE arte_ai_invocation ADD CONSTRAINT reject_recovery CHECK(workspace_snapshot NOT LIKE '%\"state\":\"SUCCEEDED\"%')");
            assertThrows(Exception.class, () -> f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(before, f.attempt("one"));
            assertEquals(0, terminals(f));
            f.jdbc.execute("ALTER TABLE arte_ai_invocation DROP CONSTRAINT reject_recovery");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(1, terminals(f));
        }
    }

    @Test
    public void expiredAttemptCannotAppendNewResultBytes() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            prepare(f, f.claim("first"));
            f.expireAttempt("one");
            assertEquals(StoreOutcome.Code.LEASE_LOST, f.payloads.put(f.owner, "one", f.attempt("one").attemptId(),
                    "tool-result", new InvocationResult.Tool(success(f))).block(WAIT).code());
            assertEquals(0, f.count("arte_ai_result"));
        }
    }

    @Test
    public void stopExpiredCannotDiscardAlreadyStoredToolEvidence() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            prepare(f, f.claim("first"));
            save(f, success(f));
            f.expireAttempt("one");
            assertEquals(StoreOutcome.Code.VERSION_CONFLICT, f.executions.stopExpired(version(f.invocation("one"))).block(WAIT).code());
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
        }
    }

    @Test
    public void httpQueriesAndToolCancelUseGenericEnvelopeAndDurableReceipt() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            login();
            var mvc = r.mvc();
            var status = perform(mvc, ROOT + "getInvocationStatus", query(), null);
            assertEquals(200, status.getResponse().getStatus());
            assertTrue(status.getResponse().getContentAsString().contains("TOOL"));
            var cancelled = perform(mvc, "/ai-new/tool/cancelToolInvocation", query(), "stop");
            assertEquals(202, cancelled.getResponse().getStatus());
            assertTrue(cancelled.getResponse().getContentAsString().contains("ACCEPTED"));
            assertEquals(202, perform(mvc, "/ai-new/tool/cancelToolInvocation", query(), "stop").getResponse().getStatus());
            assertEquals(Invocation.State.CANCELLED, r.f.invocation("one").state());
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void httpRevocationAndMissingKeyDoNotCancelOrReturnToolDetails() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            login();
            var mvc = r.mvc();
            r.read.set(false);
            assertEquals(403, perform(mvc, ROOT + "getInvocationStatus", query(), null).getResponse().getStatus());
            r.read.set(true);
            r.cancel.set(false);
            assertEquals(403, perform(mvc, "/ai-new/tool/cancelToolInvocation", query(), "stop").getResponse().getStatus());
            assertEquals(400, perform(mvc, "/ai-new/tool/cancelToolInvocation", query(), null).getResponse().getStatus());
            assertEquals(Invocation.State.ACCEPTED, r.f.invocation("one").state());
        }
    }

    @Test
    public void cancellationRecordedAfterPreparationPreventsBusinessEntry() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var storage = intercepted(f, (method, args) -> method.equals("cancellationRequested") && reads.incrementAndGet() == 2
                    ? f.executions.requestControl(f.owner, new ExecutionControlRequest("stop", "one", "stop", ControlReceipt.Command.CANCEL))
                    .then(f.executions.cancellationRequested(f.owner, "one")) : null);
            f.dispatcher(storage, f.payloads, f.gateway).dispatch(f.claim("first"), f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.CANCELLED, f.invocation("one").state());
            assertEquals(ExecutionError.Certainty.KNOWN, f.invocation("one").error().certainty());
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void cancellationStoreFailureAfterPreparationDoesNotProduceFalseTerminal() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var storage = intercepted(f, (method, args) -> method.equals("cancellationRequested") && reads.incrementAndGet() == 2
                    ? Mono.error(new IllegalStateException("Injected cancellation store failure")) : null);
            assertThrows(IllegalStateException.class, () -> f.dispatcher(storage, f.payloads, f.gateway)
                    .dispatch(f.claim("first"), f.runtime("one")).block(WAIT));
            assertEquals(Invocation.State.RUNNING, f.invocation("one").state());
            assertEquals(0, f.invocations.get());
            assertEquals(0, terminals(f));
        }
    }

    @Test
    public void savedSuccessRemainsSuccessDespiteLaterCancellationIntent() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var message = r.f.claim("first");
            prepare(r.f, message);
            save(r.f, success(r.f));
            r.cancel("stop");
            r.f.expireAttempt("one");
            r.f.dispatcher.dispatch(message, r.f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, r.f.invocation("one").state());
            assertEquals(1, terminals(r.f));
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void undispatchedExpiredAttemptHasKnownInterruptedFact() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            assertTrue(f.executions.createAttempt(new ExecutionCommands.CreateAttempt(version(f.invocation("one")), "attempt-one", "first",
                    Duration.ofSeconds(3))).block(WAIT).successful());
            f.expireAttempt("one");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.INTERRUPTED, f.invocation("one").state());
            assertEquals(ExecutionError.Certainty.KNOWN, f.invocation("one").error().certainty());
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void savedResultCanBeClosedOutAfterOriginalDeadlineWithoutExtendingIt() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            var message = f.claim("first");
            prepare(f, message);
            save(f, success(f));
            f.expireAttempt("one");
            var deadline = f.invocation("one").request().options().deadline();
            var future = java.time.Clock.offset(f.clock, Duration.ofHours(1));
            var dispatcher = new ToolDispatcher(f.executions, f.executions, f.executions, f.store,
                    f.bindingService, f.access, f.gateway, f.payloads, f.codec, f.metadata, future, Duration.ofSeconds(3));
            dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(deadline, f.invocation("one").request().options().deadline());
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void acceptedButUnpreparedCrashReclaimsOriginalOperationAndExecutesOnce() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            f.claim("crashed");
            expireClaim(f);
            assertEquals(1, (int) f.worker(f.dispatcher).pollOnce().block(WAIT));
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void hostPermitCannotExpandScopesOrReplaceTheStableOwner() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            for (boolean foreign : List.of(true, false)) {
                ToolInvocationAccess unsafe = (invocation, context, operation) -> Mono.just(new ToolInvocationAccess.Permit() {
                    public ExecutionAuthorization authorization() {
                        var original = context.authorization();
                        return new ExecutionAuthorization(foreign ? new ExecutionPrincipal("other", "alice", ExecutionPrincipal.Kind.USER) : original.principal(),
                                original.tenantId(), original.workspaceId(), foreign ? original.scopes() : Set.of("ai:read", "ai:invoke"), "unsafe");
                    }

                    public void requireInCurrentTransaction() {
                        fail("Unsafe permission must be rejected before commit");
                    }
                });
                var auth = new AdmissionAuthorization(r.resolver, r.properties, r.f.clock, null, r.f.io, unsafe);
                assertThrows(AccessDeniedException.class, () -> auth.readInvocation(r.f.invocation("one"), r.context()).block(WAIT));
            }
        }
    }

    @Test
    public void identicalStoredResultCanReplayAfterLeaseExpiryWithoutAddingBytes() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.submit("one");
            prepare(f, f.claim("first"));
            var result = new InvocationResult.Tool(success(f));
            var first = f.payloads.put(f.owner, "one", f.attempt("one").attemptId(), "tool-result", result).block(WAIT);
            f.expireAttempt("one");
            var replay = f.payloads.put(f.owner, "one", f.attempt("one").attemptId(), "tool-result", result).block(WAIT);
            assertEquals(StoreOutcome.Code.REPLAYED, replay.code());
            assertEquals(first.value(), replay.value());
            assertEquals(1, f.count("arte_ai_result"));
            assertEquals(0, terminals(f));
        }
    }

    @Test
    public void toolCancelRejectsForgedIdentityAndUnknownScopeFields() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            login();
            var mvc = r.mvc();
            var forged = new java.util.HashMap<String, Object>(query());
            forged.put("owner", "other");
            assertEquals(400, perform(mvc, "/ai-new/tool/cancelToolInvocation", forged, "stop").getResponse().getStatus());
            var scope = new java.util.HashMap<String, Object>(Map.of("tenantId", "tenant", "workspaceId", "workspace"));
            scope.put("subjectId", "other");
            assertEquals(400, perform(mvc, "/ai-new/tool/cancelToolInvocation", Map.of("scope", scope, "invocationId", "one"), "stop").getResponse().getStatus());
            assertEquals(Invocation.State.ACCEPTED, r.f.invocation("one").state());
        }
    }

    private static ExecutionStore intercepted(ToolExecutionFixture f, java.util.function.BiFunction<String, Object[], Object> interceptor) {
        return (ExecutionStore) Proxy.newProxyInstance(ExecutionStore.class.getClassLoader(), new Class<?>[]{ExecutionStore.class},
                (proxy, method, args) -> {
                    var result = interceptor.apply(method.getName(), args);
                    if (result != null) return result;
                    try {
                        return method.invoke(f.executions, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static ExecutionCommands.Version version(Invocation invocation) {
        return new ExecutionCommands.Version(ExecutionOwner.from(invocation.request().context()), invocation.request().context().executionId(), invocation.version());
    }

    private static void prepare(ToolExecutionFixture f, OutboxMessage message) {
        assertTrue(f.executions.prepareDispatch(new DispatchPreparationStore.Command(version(f.invocation("one")), message,
                "attempt-one", Duration.ofSeconds(3), new DispatchPreparationStore.Free())).block(WAIT).successful());
    }

    private static String callId(ToolExecutionFixture f) {
        return f.store.snapshot(f.owner, "one").block(WAIT).request().input().callId();
    }

    private static ToolResult success(ToolExecutionFixture f) {
        return new ToolResult(callId(f), ToolResult.Outcome.SUCCEEDED, object("text", text("ok")), List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
    }

    private static void save(ToolExecutionFixture f, ToolResult result) {
        assertTrue(f.payloads.put(f.owner, "one", f.attempt("one").attemptId(), "tool-result", new InvocationResult.Tool(result)).block(WAIT).successful());
    }

    private static void expireClaim(ToolExecutionFixture f) {
        f.jdbc.update("UPDATE arte_ai_outbox SET lease_until=0 WHERE kind='DISPATCH'");
    }

    private static long terminals(ToolExecutionFixture f) {
        return f.executions.replay(f.owner, new ExecutionEvent.Cursor("one", 0), 256).block(WAIT).value().events().stream()
                .filter(e -> e.kind() == ExecutionEvent.Kind.TERMINAL).count();
    }

    private static void login() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
    }

    private static Map<String, Object> query() {
        return Map.of("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"), "invocationId", "one");
    }

    private static MvcResult perform(MockMvc mvc, String path, Object body, String key) throws Exception {
        var builder = post(path).contentType(MediaType.APPLICATION_JSON).content(JsonMapper.builder().build().writeValueAsString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        var result = mvc.perform(builder).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }
}
