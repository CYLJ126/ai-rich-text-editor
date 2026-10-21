package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.execution.AdmissionInvocationCoordinator;
import com.arte.ainew.application.execution.DefaultInvocationCoordinator;
import com.arte.ainew.application.execution.GenerationDispatcher;
import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.chat.ToolChatBudgetSelector;
import com.arte.ainew.application.tool.chat.ToolChatCoordinator;
import com.arte.ainew.application.tool.chat.ToolChatService;
import com.arte.ainew.application.tool.chat.ToolSetService;
import com.arte.ainew.common.execution.*;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.spi.gateway.ModelGateway;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static org.junit.Assert.*;

/**
 * 真实 H2 受理、Run／Invocation Worker 和本地业务网关；模型以固定流代替，不访问供应商或业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
public class ToolChatLoopTest {

    static final Duration WAIT = Duration.ofSeconds(15);

    static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    static Money money(String value) {
        return new Money(new BigDecimal(value), Currency.getInstance("CNY"));
    }

    static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f;
        final NewAiProperties properties;
        final AdmissionAuthorization authorization;
        final FixedControlCatalog catalog;
        final DefaultConversationService conversations;
        final ChatHistoryLoader history;
        final ToolChatBudgetSelector budgets;
        final ToolChatAccess access;
        final ToolManagementAccess.Session session;
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final AtomicInteger modelCalls = new AtomicInteger();
        final List<GenerationRequest> sent = new ArrayList<>();
        final ResolvedBinding model;
        final String conversation;
        final ExecutionContext context;
        final com.arte.ainew.api.execution.InvocationCoordinator invocationCoordinator;
        final InvocationDispatchWorker dispatch;
        final GenerationDispatcher generation;
        final com.arte.ainew.application.tool.admission.ToolAdmissionService toolAdmission;
        final com.arte.ainew.persistence.mybatis.MybatisToolApprovalStore approvals;
        MybatisToolChatRunStore runs;
        ToolChatService service;
        ModelGateway gateway;
        ToolChatCoordinator coordinator;
        boolean unknownModel;
        boolean finalFirst;
        boolean partialProposal;
        boolean repeatProposal;
        String reportedModel = "model";
        String reportedProvider = "provider";
        Usage providerUsage = new Usage(Usage.Basis.PROVIDER_REPORTED, 100L, 10L, 110L);
        int proposedCalls = 1;

        Rig() throws Exception {
            this(false);
        }

        Rig(boolean confirmation) throws Exception {
            this(confirmation, CapabilityDescriptor.SideEffect.READ_ONLY);
        }

        Rig(boolean confirmation, CapabilityDescriptor.SideEffect effect) throws Exception {
            this(confirmation, effect, true);
        }

        Rig(boolean confirmation, CapabilityDescriptor.SideEffect effect, boolean supportsTools) throws Exception {
            this(confirmation, effect, supportsTools, null);
        }

        Rig(boolean confirmation, CapabilityDescriptor.SideEffect effect, boolean supportsTools,
            com.arte.ainew.spi.adapter.HttpToolAdapter http) throws Exception {
            this(confirmation, effect, supportsTools, http, null);
        }

        Rig(boolean confirmation, CapabilityDescriptor.SideEffect effect, boolean supportsTools, com.arte.ainew.spi.adapter.HttpToolAdapter http, ToolPrice paidPrice) throws Exception {
            f = paidPrice != null ? new ToolExecutionFixture(paidPrice) : http != null ? new ToolExecutionFixture(http) : new ToolExecutionFixture(new ToolExecutionPolicy(Duration.ofSeconds(120), 1, Duration.ZERO, 4096, 4096,
                    confirmation ? ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION : ToolExecutionPolicy.ApprovalMode.NONE),
                    new ToolRiskProfile(effect, false, ToolRiskProfile.Idempotency.NONE, Set.of("ai:invoke"), Set.of()),
                    new ToolExecutionPolicy(Duration.ofSeconds(120), 10, Duration.ZERO, 100_000, 100_000, ToolExecutionPolicy.ApprovalMode.NONE));
            approvals = new com.arte.ainew.persistence.mybatis.MybatisToolApprovalStore(f.source, f.codec, f.io, f.executions, f.bindings);
            var approvalAccess = new ToolApprovalAccess() {
                public Mono<PlanPermit> authorizePlan(ToolExecutionSnapshot snapshot, ToolApproval.Plan plan, ExecutionContext requester) {
                    return Mono.just((actual, fixedPlan) -> {
                        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                        fixedPlan.requireMatches(actual);
                    });
                }

                public Mono<Permit> authorize(ToolApproval approval, ToolExecutionSnapshot snapshot, ExecutionContext actor, boolean deciding) {
                    return Mono.just((a, s) -> assertTrue(TransactionSynchronizationManager.isActualTransactionActive()));
                }
            };
            toolAdmission = new com.arte.ainew.application.tool.admission.ToolAdmissionService(f.bindingService, f.catalog, f.store, f.access,
                    new com.arte.ainew.application.tool.schema.ToolSchemaValidator(), f.limits, f.metadata, f.clock,
                    new com.arte.ainew.application.tool.approval.ToolApprovalPolicy(new NewAiToolApprovalProperties(confirmation, null, 0), approvalAccess, approvals));
            var scopes = Set.of("ai:invoke", "ai:read", "ai:conversation", "resource:read");
            var capability = new CapabilityDescriptor(ref("capability", "model"), CapabilityDescriptor.Kind.GENERATION, FixedControlCatalog.INPUT_SCHEMA,
                    FixedControlCatalog.OUTPUT_SCHEMA, supportsTools ? Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS)
                    : Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING),
                    CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
            model = new ResolvedBinding(ref("binding", "model"), capability, ref("connection", "model"), "model", 32768L, ref("rate", "model"));
            var connection = new ConnectionDefinition(model.connection(), "provider", ref("protocol", "http"), URI.create("https://example.invalid/v1"), ref("secret", "test"),
                    ConnectionDefinition.State.ENABLED, Duration.ofSeconds(5), Duration.ofSeconds(120), 65536);
            properties = new NewAiProperties(true, "test", "release", null,
                    new NewAiProperties.Limits(65536, 1024, 65536, Duration.ofSeconds(120), Duration.ofMinutes(10)),
                    List.of(new NewAiProperties.Grant("user", "subject", ExecutionPrincipal.Kind.USER, "tenant", "workspace", "grant", true, scopes, Set.of("model"), paidPrice == null ? Set.of("wallet") : Set.of("wallet", "tool-wallet"))),
                    List.of(capability), List.of(model), List.of(connection), paidPrice == null ? List.of(new NewAiProperties.Rate(model.rate(), money("1"), money("2")))
                    : List.of(new NewAiProperties.Rate(model.rate(), money("1"), money("2")), new NewAiProperties.Rate(paidPrice.rate(), null, null, paidPrice.perCall())),
                    paidPrice == null ? List.of(new NewAiProperties.Budget("wallet", f.owner, money("100"), model.rate()))
                            : List.of(new NewAiProperties.Budget("wallet", f.owner, money("100"), model.rate()), new NewAiProperties.Budget("tool-wallet", f.owner,
                            new Money(new BigDecimal("10"), paidPrice.perCall().currency()), paidPrice.rate())));
            var resolver = new FixedExecutionAuthorizationResolver(properties);
            authorization = new AdmissionAuthorization((name, tenant, workspace, required) -> allowed.get() ? resolver.resolve(name, tenant, workspace, required)
                    : Mono.error(new AccessDeniedException("revoked")), properties, f.clock);
            catalog = new FixedControlCatalog(properties, authorization, f.clock);
            if (paidPrice != null) {
                f.billing = new com.arte.ainew.application.tool.billing.ToolBillingService(authorization, catalog, f.executions, f.executions, f.executions);
                f.service.paid(f.billing);
                toolAdmission.paid(f.billing);
            }
            var auth = new ExecutionAuthorization(new ExecutionPrincipal("subject", "user", ExecutionPrincipal.Kind.USER), "tenant", "workspace", scopes, "grant");
            context = new ExecutionContext("run", "root-trace", auth, Instant.now().plusSeconds(90), null, null, "release", "submit");
            conversations = new DefaultConversationService(f.executions, authorization, f.clock);
            var create = new ExecutionContext("conversation-create", "trace", auth, context.deadline(), null, null, "release", "create");
            conversation = conversations.create("chat", null, List.of(), create).block(WAIT).conversationId();
            runs = new MybatisToolChatRunStore(f.source, f.codec, f.io);
            history = new ChatHistoryLoader(conversations, authorization, f.executions, f.payloads, f.payloads, runs);
            budgets = new ToolChatBudgetSelector(runs, authorization, catalog, f.executions, f.bindingService, f.clock).paid(f.billing);
            f.executions.createAccount(new BudgetCommands.Account("wallet", f.owner, money("100"), money("0"), money("0"), model.rate(), 0)).block(WAIT);
            access = (current, cancelling) -> Mono.just(fixed -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.hasResource(f.source));
                if (!cancelling && !allowed.get()) throw new AccessDeniedException("revoked in transaction");
                assertEquals(f.owner, ExecutionOwner.from(fixed.context()));
            });
            session = (scope, operation) -> Mono.just(new ToolManagementAccess.Permit() {
                public com.arte.core.pojo.platform.TrustedActor actor() {
                    return f.actor;
                }

                public ExecutionOwner owner() {
                    return f.owner;
                }

                public ToolManagementAccess.Operation operation() {
                    return operation;
                }

                public Set<String> executionScopes() {
                    return scopes;
                }

                public void requireInCurrentTransaction(Set<String> required) {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                }
            });
            var admission = new AdmissionInvocationCoordinator(authorization, catalog, catalog, f.executions, f.payloads, f.executions, properties, f.clock);
            gateway = new ModelGateway() {
                public boolean supportsToolChat(ResolvedBinding binding, ConnectionDefinition connection) {
                    return supportsTools;
                }
                public Flux<GenerationSignal> generate(GatewayCall<GenerationRequest> call) {
                    return Flux.error(new AssertionError("Tool snapshot missing"));
                }

                public Flux<GenerationSignal> generate(GatewayCall<GenerationRequest> call, ToolSetSnapshot tools) {
                    return Flux.defer(() -> {
                        modelCalls.incrementAndGet();
                        sent.add(call.request().input());
                        if (unknownModel)
                            return Flux.just(new GenerationSignal.Failure(new ExecutionError("PROVIDER_FLOW_INTERRUPTED", ExecutionError.Phase.INVOCATION,
                                    false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace"), Usage.unknown(), null));
                        boolean proposal = !finalFirst && (repeatProposal || call.request().input().messages().stream().noneMatch(m -> m.role() == ChatMessage.Role.TOOL));
                        var calls = new ArrayList<ToolCall>();
                        if (proposal) for (int i = 0; i < proposedCalls; i++)
                            calls.add(new ToolCall("call-" + i, tools.entries().getFirst().binding().tool().tool().definition(), object()));
                        var output = new ChatMessage("answer-" + modelCalls.get(), ChatMessage.Role.ASSISTANT,
                                proposal ? List.of() : List.of(new ChatMessage.Text("completed")), calls, null);
                        var usage = providerUsage;
                        var result = new ModelResult("result-" + modelCalls.get(), new ModelResult.ModelIdentity(reportedProvider, reportedModel, null), List.of(output),
                                proposal ? ModelResult.FinishReason.TOOL_CALLS : ModelResult.FinishReason.STOP, !partialProposal, null, usage, List.of());
                        if (proposal)
                            return Flux.just(new GenerationSignal.Delta(new GenerationEvent.ToolCallDelta(0, "call-0", "tool_0", "{}")),
                                    new GenerationSignal.Result(result));
                        return Flux.just(new GenerationSignal.Delta(new GenerationEvent.TextDelta("completed")), new GenerationSignal.Result(result));
                    });
                }
            };
            generation = new GenerationDispatcher(authorization, catalog, f.executions, f.executions, f.executions, f.payloads, f.payloads,
                    f.executions, gateway, properties, f.executionProperties, f.clock).toolChats(runs);
            var gatewayTools = new com.arte.ainew.application.gateway.DefaultToolGateway(f.bindingService, f.catalog, f.registry,
                    call -> Mono.just(call.call().runtime().execution().authorization()), new com.arte.ainew.application.tool.schema.ToolSchemaValidator(),
                    f.codec, f.toolIo, f.clock, approvals::requireApproved);
            invocationCoordinator = new DefaultInvocationCoordinator(admission, generation, f.executions, f.dispatcher(f.executions, f.payloads, http == null && paidPrice == null ? gatewayTools : f.gateway));
            dispatch = new InvocationDispatchWorker(f.executions, f.executions, invocationCoordinator, f.executionProperties, f.timer);
            restart();
        }

        void restart() {
            runs = new MybatisToolChatRunStore(f.source, f.codec, f.io);
            var selector = new ToolChatBudgetSelector(runs, authorization, catalog, f.executions, f.bindingService, f.clock).paid(f.billing);
            service = new ToolChatService(runs, conversations, f.executions, catalog,
                    new TextContextService(authorization, catalog, f.payloads, properties, f.clock, history),
                    new ToolSetService(f.bindingService, f.catalog, f.metadata, Duration.ofSeconds(5)), selector, authorization, access, properties, f.clock, () -> gateway);
            coordinator = new ToolChatCoordinator(runs, f.executions, f.payloads, invocationCoordinator, toolAdmission, selector, authorization, access, f.store, properties, f.clock, Duration.ofMillis(100));
        }

        ToolChatSubmission input() {
            return input("question", 3, 4, "1");
        }

        ToolChatSubmission input(String text, int rounds, int calls, String limit) {
            return new ToolChatSubmission(conversation, 0, text, model.definition(), model.capability().definition(), "wallet", 20000,
                    new GenerationOptions(128, null, null, List.of()), List.of(f.binding.bindingId()),
                    new ToolChatRunSnapshot.Limits(Duration.ofSeconds(90), rounds, calls, 1), List.of(money(limit)), f.v1.revision().tool().price() == null ? java.util.Map.of() : java.util.Map.of(f.binding.bindingId(), "tool-wallet"));
        }

        void submit() {
            assertEquals(AcceptedExecution.Kind.RUN, service.submit(input(), session, context).block(WAIT).kind());
        }

        ToolChatRun run() {
            return runs.find(f.owner, "run").block(WAIT);
        }

        void tick() {
            if (!run().state().terminal()) runs.wake(f.owner, "run").block(WAIT);
            var leases = runs.claim("test", 1, Duration.ofSeconds(30)).block(WAIT);
            for (var lease : leases) coordinator.process(lease).block(WAIT);
        }

        void dispatch() {
            dispatch.pollOnce().block(WAIT);
        }

        void until(java.util.function.Predicate<ToolChatRun> condition) {
            for (int i = 0; i < 20; i++) {
                if (condition.test(run())) return;
                tick();
                dispatch();
            }
            fail("Run did not reach expected checkpoint: " + run());
        }

        int count(String table) {
            return f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        public void close() throws Exception {
            dispatch.stop();
            f.close();
        }
    }

    @Test
    public void actualProviderModelMayDifferFromRequestNameAcrossToolLoopAndRecovery() throws Exception {
        try (var r = new Rig()) {
            r.reportedModel = "actual-provider-model";
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.WAITING_TOOL);
            r.restart();
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(2, r.modelCalls.get());
            assertEquals(1, r.f.invocations.get());
            var query = new com.arte.ainew.application.tool.chat.ToolChatQueryService(r.runs, r.conversations, r.authorization, r.f.executions, r.f.payloads);
            assertEquals("actual-provider-model", query.result("run", r.context).block(WAIT).result().value().model().modelId());
        }
        try (var r = new Rig()) {
            r.reportedModel = "actual-provider-model";
            r.finalFirst = true;
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void differentActualModelDoesNotAuthorizeADifferentProvider() throws Exception {
        try (var r = new Rig()) {
            r.reportedModel = "actual-provider-model";
            r.reportedProvider = "untrusted-provider";
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.PENDING_RECONCILIATION);
            assertEquals("CHILD_OUTCOME_UNKNOWN", r.run().error().code());
            assertEquals(0, r.f.invocations.get());
            assertEquals(0, r.run().toolSteps());
        }
    }

    @Test
    public void completeLoopRecoversOriginalIdentitiesAndProjectsOnlyFinalAnswer() throws Exception {
        try (var r = new Rig()) {
            r.submit();
            r.tick(); // 固定首轮计划，模拟受理子调用前崩溃。
            var id = r.run().checkpoint().children().getFirst();
            r.restart();
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(2, r.modelCalls.get());
            assertEquals(1, r.f.invocations.get());
            assertEquals(3, r.count("arte_ai_invocation"));
            assertEquals(id, r.runs.steps(r.f.owner, "run").block(WAIT).stream().filter(s -> s.roundIndex() == 0 && s.input() instanceof ToolChatStep.Model).findFirst().orElseThrow().childExecutionId());
            assertEquals(2, r.count("arte_ai_tool_chat_budget_allocation"));
            var budget = r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst();
            assertEquals(money("0"), budget.held());
            assertEquals(money("0.00024"), budget.charged());
            var second = r.sent.getLast();
            assertTrue(second.messages().stream().anyMatch(m -> m.role() == ChatMessage.Role.TOOL && "call-0".equals(m.toolCallId())));
            var link = r.runs.link(r.f.owner, "run").block(WAIT);
            assertNotNull(link.finalInvocationId());
            assertEquals("completed", ((ChatMessage.Text) r.history.load(new com.arte.ainew.pojo.context.ContextRequest.HistorySelection(r.conversation,
                    r.f.executions.findConversation(r.f.owner, r.conversation).block(WAIT).version(), List.of(), 10), "model", r.context).block(WAIT).messages().getLast().content().getFirst()).text());
        }
    }

    @Test
    public void replayAfterCompletionAndChangedIntent() throws Exception {
        try (var r = new Rig()) {
            r.finalFirst = true;
            r.submit();
            r.until(run -> run.state().terminal());
            var newer = new ExecutionContext("temporary", "new-trace", r.context.authorization(), Instant.now().plusSeconds(90), null, null, "release", "submit");
            assertEquals("run", r.service.submit(r.input(), r.session, newer).block(WAIT).executionId());
            assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> r.service.submit(r.input("changed", 3, 4, "1"), r.session, newer).block(WAIT));
            assertEquals(1, r.modelCalls.get());
            assertEquals(1, r.count("arte_ai_tool_chat_run"));
        }
    }

    @Test
    public void unknownModelStopsAllFurtherDispatchAndSurvivesRestart() throws Exception {
        try (var r = new Rig()) {
            r.unknownModel = true;
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.PENDING_RECONCILIATION);
            r.restart();
            for (int i = 0; i < 3; i++) r.tick();
            assertEquals(ToolChatRun.State.PENDING_RECONCILIATION, r.run().state());
            assertEquals(1, r.modelCalls.get());
            assertEquals(0, r.f.invocations.get());
            assertTrue(r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().held().amount().signum() > 0);
        }
    }

    @Test
    public void durableCancelBlocksNewChildAndPreservesUnknownHold() throws Exception {
        try (var r = new Rig()) {
            r.unknownModel = true;
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.PENDING_RECONCILIATION);
            var held = r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().held();
            r.service.cancel("run", "cancel", r.context).block(WAIT);
            r.restart();
            r.tick();
            assertEquals(ToolChatRun.State.CANCELLED, r.run().state());
            assertEquals(held, r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().held());
            assertEquals(1, r.modelCalls.get());
        }
        try (var r = new Rig()) {
            r.submit();
            r.tick();
            r.service.cancel("run", "cancel", r.context).block(WAIT);
            r.tick();
            r.dispatch();
            assertEquals(ToolChatRun.State.CANCELLED, r.run().state());
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void runBudgetAndCallLimitsBlockFurtherWork() throws Exception {
        try (var r = new Rig()) {
            r.service.submit(r.input("question", 3, 4, "0.00001"), r.session, r.context).block(WAIT);
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.FAILED, r.run().state());
            assertEquals(0, r.modelCalls.get());
            assertEquals(0, r.f.invocations.get());
            assertEquals(0, r.count("arte_ai_reservation"));
        }
        try (var r = new Rig()) {
            r.service.submit(r.input("question", 1, 4, "1"), r.session, r.context).block(WAIT);
            r.until(run -> run.state().terminal());
            assertEquals("RUN_EXECUTION_LIMIT_EXCEEDED", r.run().error().code());
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void permissionRevokedWhileWaitingCancelsAcceptedChild() throws Exception {
        try (var r = new Rig()) {
            r.submit();
            r.tick();
            r.tick(); // 模型已受理但未派发。
            r.allowed.set(false);
            r.tick();
            assertEquals(ToolChatRun.State.FAILED, r.run().state());
            assertEquals("RUN_AUTHORIZATION_REVOKED", r.run().error().code());
            assertEquals(0, r.modelCalls.get());
            var child = r.runs.steps(r.f.owner, "run").block(WAIT).getFirst();
            assertTrue(r.f.executions.cancellationRequested(r.f.owner, child.childExecutionId()).block(WAIT));
        }
    }

    @Test
    public void confirmationWaitSurvivesRestartAndApprovalResumesSameTool() throws Exception {
        try (var r = new Rig(true)) {
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.WAITING_APPROVAL);
            assertEquals(0, r.f.invocations.get());
            var step = r.runs.steps(r.f.owner, "run").block(WAIT).stream().filter(s -> s.input() instanceof ToolChatStep.Tool).findFirst().orElseThrow();
            var approval = r.approvals.find(r.f.owner, step.childExecutionId()).block(WAIT);
            assertEquals(ToolApproval.State.PENDING, approval.state());
            r.restart();
            r.tick();
            var command = new ToolApprovalCommand(r.f.owner, step.childExecutionId(), approval.approvalId(), approval.version(), approval.subjectDigest(),
                    ToolApprovalCommand.Decision.APPROVE, "approve");
            assertTrue(r.approvals.decide(command, r.f.owner, (a, s) -> assertTrue(TransactionSynchronizationManager.isActualTransactionActive())).block(WAIT).successful());
            // Run 仍展示审批等待时，已批准的原子调用也能由统一派发器取得资格。
            r.dispatch();
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(1, r.f.invocations.get());
            assertEquals(3, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void rejectedApprovalDoesNotExecuteOrAskModelAgain() throws Exception {
        try (var r = new Rig(true)) {
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.WAITING_APPROVAL);
            var id = r.run().checkpoint().children().getFirst();
            var approval = r.approvals.find(r.f.owner, id).block(WAIT);
            var command = new ToolApprovalCommand(r.f.owner, id, approval.approvalId(), approval.version(), approval.subjectDigest(), ToolApprovalCommand.Decision.REJECT, "reject");
            assertTrue(r.approvals.decide(command, r.f.owner, (a, s) -> assertTrue(TransactionSynchronizationManager.isActualTransactionActive())).block(WAIT).successful());
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.FAILED, r.run().state());
            assertEquals(0, r.f.invocations.get());
            assertEquals(1, r.modelCalls.get());
        }
    }

    @Test
    public void concurrentSameIntentAndRestartAfterChildAcceptanceDoNotDuplicateWork() throws Exception {
        try (var r = new Rig()) {
            var receipts = Flux.range(0, 3).flatMap(i -> r.service.submit(r.input(), r.session,
                            new ExecutionContext("run-" + i, "trace-" + i, r.context.authorization(), r.context.deadline(), null, null, "release", "submit")), 3)
                    .collectList().block(WAIT);
            assertEquals(1, receipts.stream().map(AcceptedExecution::executionId).distinct().count());
            assertEquals(1, r.count("arte_ai_tool_chat_run"));
            assertEquals(1, r.count("arte_ai_turn"));
        }
        try (var r = new Rig()) {
            r.submit();
            r.tick();
            r.tick();
            assertEquals(1, r.count("arte_ai_invocation"));
            r.restart();
            r.tick();
            r.tick();
            assertEquals(1, r.count("arte_ai_invocation"));
            r.until(run -> run.state().terminal());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void multipleReadProposalsRespectRunConcurrencyAndDoNotRepeatCalls() throws Exception {
        try (var r = new Rig()) {
            r.proposedCalls = 3;
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.WAITING_TOOL);
            r.tick(); // maxConcurrentTools=1，本次只受理一个工具。
            assertEquals(2, r.count("arte_ai_invocation"));
            r.tick(); // 已受理但未执行，不能再占第二个名额。
            assertEquals(2, r.count("arte_ai_invocation"));
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(3, r.f.invocations.get());
            assertEquals(2, r.modelCalls.get());
            assertEquals(3, r.sent.getLast().messages().stream().filter(m -> m.role() == ChatMessage.Role.TOOL).count());
        }
    }

    @Test
    public void expiryStopsWithoutNewModelAndFinalAnswerSurvivesBillingOverrun() throws Exception {
        try (var r = new Rig()) {
            var in = r.input();
            var shortRun = new ToolChatSubmission(in.conversationId(), in.expectedVersion(), in.text(), in.binding(), in.capability(), in.modelBudgetRef(),
                    in.maxInputTokens(), in.generation(), in.toolBindingIds(), new ToolChatRunSnapshot.Limits(Duration.ofSeconds(1), 3, 4, 1), in.budgetLimits());
            r.service.submit(shortRun, r.session, r.context).block(WAIT);
            Thread.sleep(1100);
            r.tick();
            assertEquals(ToolChatRun.State.TIMED_OUT, r.run().state());
            assertEquals(0, r.modelCalls.get());
            assertEquals(0, r.count("arte_ai_invocation"));
        }
        try (var r = new Rig()) {
            r.finalFirst = true;
            r.providerUsage = new Usage(Usage.Basis.PROVIDER_REPORTED, 100000L, 1000L, 101000L);
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertTrue(r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().blocked());
            assertEquals(1, r.modelCalls.get());
        }
    }

    @Test
    public void lostLeaseCannotPlanAndWaitingDoesNotCreateEmptyProgressEvents() throws Exception {
        try (var r = new Rig()) {
            r.submit();
            var stale = r.runs.claim("old", 1, Duration.ofSeconds(10)).block(WAIT).getFirst();
            r.f.jdbc.update("UPDATE arte_ai_tool_chat_run_work SET lease_until=0");
            var winner = r.runs.claim("new", 1, Duration.ofSeconds(10)).block(WAIT).getFirst();
            assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> r.coordinator.process(stale).block(WAIT));
            assertEquals(0, r.count("arte_ai_tool_chat_run_step"));
            r.coordinator.process(winner).block(WAIT);
            r.tick(); // 子受理。
            long version = r.run().version();
            int events = r.count("arte_ai_tool_chat_run_event");
            r.tick();
            r.tick();
            assertEquals(version, r.run().version());
            assertEquals(events, r.count("arte_ai_tool_chat_run_event"));
        }
    }

    @Test
    public void unknownToolOnlyQueriesOriginalOperationAndResumesAfterTrustedReconciliation() throws Exception {
        try (var r = new Rig()) {
            var adapter = new com.arte.ainew.spi.adapter.ToolOutcomeQueryAdapter() {
                public DefinitionRef tool() {
                    return r.f.v1.revision().tool().definition();
                }

                public boolean supports(ToolExecutionSnapshot fixed) {
                    return fixed.request().context().parentExecutionId() != null;
                }

                public String operationKey(ToolExecutionSnapshot fixed) {
                    return fixed.request().context().executionId();
                }

                public Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim) {
                    throw new AssertionError("Use explicit test evidence");
                }
            };
            var scheduling = new NewAiSchedulingProperties(true, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null, "a".repeat(64));
            r.f.jdbc.update("INSERT INTO arte_ai_dispatch_pool(pool_key,policy_digest,manifest_digest) VALUES('default',?,?)",
                    com.arte.ainew.serialization.CanonicalJson.key(scheduling.toString()), scheduling.toolManifest());
            var outcomes = new com.arte.ainew.persistence.mybatis.MybatisToolOutcomeStore(r.f.source, r.f.codec, r.f.executions,
                    new com.arte.ainew.application.tool.outcome.ToolOutcomeQueryRegistry(List.of(adapter)), scheduling, r.f.io);
            r.f.executions.configureToolOutcomeRecorder(outcomes::recordUnknownInCurrentTransaction);
            r.f.operation = call -> Mono.error(new IllegalStateException("lost tool response"));
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.PENDING_RECONCILIATION);
            assertEquals(1, r.modelCalls.get());
            assertEquals(1, r.f.invocations.get());
            r.restart();
            r.tick();
            assertEquals(1, r.f.invocations.get());
            var id = r.run().checkpoint().children().getFirst();
            assertNotNull(outcomes.find(r.f.owner, id).block(WAIT));
            r.f.jdbc.update("UPDATE arte_ai_tool_outcome_check SET next_check=0");
            var claim = outcomes.claim("query", 1).block(WAIT).getFirst();
            var result = new ToolResult("call-0", ToolResult.Outcome.SUCCEEDED, object("text", com.arte.ainew.tool.discovery.LocalToolFixtures.text("confirmed")),
                    List.of(), List.of(), ExecutionError.SideEffect.NONE, null);
            var evidence = new ToolOutcomeEvidence(r.f.owner, id, claim.work().attemptId(), claim.work().operationKey(), ToolOutcomeEvidence.Decision.CONFIRMED_SUCCEEDED,
                    "trusted-test-receipt", "a".repeat(64), result);
            assertTrue(outcomes.finish(claim, evidence, "CONFIRMED", false).block(WAIT));
            r.until(run -> run.state().terminal());
            assertEquals(r.run().toString(), ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(1, r.f.invocations.get());
            assertEquals(2, r.modelCalls.get());
        }
    }

    @Test
    public void partialAndRepeatedToolProposalsNeverCreateAnotherBusinessOperation() throws Exception {
        try (var r = new Rig()) {
            r.partialProposal = true;
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.FAILED, r.run().state());
            assertEquals(0, r.f.invocations.get());
            assertEquals(0, r.run().toolSteps());
        }
        try (var r = new Rig()) {
            r.repeatProposal = true;
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals("DUPLICATE_TOOL_CALL_ID", r.run().error().code());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void cancelGateBlocksAlreadyAcceptedModelBeforeDispatchAndToolSnapshotUsesV4() throws Exception {
        try (var r = new Rig()) {
            r.submit();
            r.tick();
            r.tick();
            assertEquals(1, r.count("arte_ai_invocation"));
            r.service.cancel("run", "cancel", r.context).block(WAIT);
            r.dispatch();
            assertEquals(0, r.modelCalls.get());
            assertEquals(0, r.count("arte_ai_reservation"));
            r.tick();
            assertEquals(ToolChatRun.State.CANCELLED, r.run().state());
        }
        try (var r = new Rig()) {
            r.submit();
            r.until(run -> run.state() == ToolChatRun.State.WAITING_TOOL);
            r.tick();
            var id = r.run().checkpoint().children().getFirst();
            var snapshot = r.f.store.snapshot(r.f.owner, id).block(WAIT);
            String encoded = r.f.codec.encode(snapshot);
            assertTrue(encoded.contains("\"schemaVersion\":4"));
            assertEquals(snapshot, r.f.codec.decode(encoded, ToolExecutionSnapshot.class));
            assertThrows(IllegalArgumentException.class, () -> r.f.codec.decode(encoded.replace("\"schemaVersion\":4", "\"schemaVersion\":1"), ToolExecutionSnapshot.class));
        }
    }
}
