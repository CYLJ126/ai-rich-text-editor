package com.arte.ainew.application.tool.chat;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.pojo.tool.ToolChatSubmission;
import com.arte.ainew.spi.auth.ToolChatAccess;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.ainew.spi.persistence.ToolChatRunStore;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.platform.Scope;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 工具聊天受理及停止；受理完成才返回 RUN 回执，不在 HTTP 请求内执行循环。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
public final class ToolChatService {

    private final ToolChatRunStore runs;
    private final ConversationService conversations;
    private final AdmissionCatalogStore catalogStore;
    private final FixedControlCatalog catalog;
    private final ContextService contexts;
    private final ToolSetService sets;
    private final ToolChatBudgetSelector budgets;
    private final AdmissionAuthorization authorization;
    private final ToolChatAccess access;
    private final NewAiProperties properties;
    private final Clock clock;
    private final Supplier<ModelGateway> gateway;

    public ToolChatService(ToolChatRunStore runs, ConversationService conversations, AdmissionCatalogStore catalogStore,
                           FixedControlCatalog catalog, ContextService contexts, ToolSetService sets, ToolChatBudgetSelector budgets,
                           AdmissionAuthorization authorization, ToolChatAccess access, NewAiProperties properties, Clock clock,
                           Supplier<ModelGateway> gateway) {
        this.runs = runs;
        this.conversations = conversations;
        this.catalogStore = catalogStore;
        this.catalog = catalog;
        this.contexts = contexts;
        this.sets = sets;
        this.budgets = budgets;
        this.authorization = authorization;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
        this.gateway = gateway;
    }

    public Mono<AcceptedExecution> submit(ToolChatSubmission input, ToolManagementAccess.Session session, ExecutionContext context) {
        return submit(input, session, context, false);
    }

    /**
     * 重新读取资料仅允许只读工具；检查固定集合，避免检查后配置变化引入写工具。
     */
    public Mono<AcceptedExecution> submit(ToolChatSubmission input, ToolManagementAccess.Session session, ExecutionContext context, boolean readOnly) {
        return authorization.prepare(context, Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION), true, current -> {
            if (current.parentExecutionId() != null || current.budgetRef() != null)
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_POLICY_DENIED));
            return runs.accepted(ExecutionOwner.from(current), current.idempotencyKey()).flatMap(run -> replay(input, run, current, readOnly))
                    .switchIfEmpty(Mono.defer(() -> create(input, session, current, readOnly)))
                    .onErrorResume(AdmissionException.class, error -> (error.getResultCode() == ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT
                            || error.getResultCode() == ResultCodeEnum.AI_VERSION_CONFLICT || error.getResultCode() == ResultCodeEnum.AI_CONVERSATION_BUSY)
                            ? runs.accepted(ExecutionOwner.from(current), current.idempotencyKey()).flatMap(run -> replay(input, run, current, readOnly))
                            .switchIfEmpty(Mono.error(error)) : Mono.error(error));
        });
    }

    private Mono<AcceptedExecution> create(ToolChatSubmission input, ToolManagementAccess.Session session, ExecutionContext current, boolean readOnly) {
        var received = clock.instant();
        return conversations.find(input.conversationId(), current).flatMap(conversation -> {
            if (conversation.state() != Conversation.State.ACTIVE || conversation.chatProfile() != null || !conversation.resources().isEmpty())
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_CHAT_SELECTION_NOT_SUPPORTED));
            return toolModel(input, current).flatMap(model -> sets.prepare(session,
                    new Scope(current.authorization().tenantId(), current.authorization().workspaceId()),
                    input.toolBindingIds().stream().map(id -> new ToolSetService.Selection(id, null)).toList()).flatMap(set -> {
                        requireReadOnly(set, readOnly);
                        var user = new ChatMessage(com.arte.ainew.serialization.CanonicalJson.key("tool-chat-user-v1", current.authorization().tenantId(),
                                current.authorization().workspaceId(), current.authorization().principal().subjectId(), current.idempotencyKey()),
                                ChatMessage.Role.USER, List.of(new ChatMessage.Text(input.text())), List.of(), null);
                        var capacity = new ContextBudget(model.contextWindowTokens(), input.maxInputTokens(), input.generation().maxOutputTokens(), 0);
                        var selection = new ContextRequest(List.of(user), new ContextRequest.HistorySelection(input.conversationId(), input.expectedVersion(), List.of(), ChatHistoryLoader.MAX_TURNS),
                                List.of(), List.of(), null, capacity);
                        return contexts.assemble(selection, model, current).flatMap(initial -> {
                            var fixedInput = ToolChatInputs.snapshot(initial.snapshotId(), initial, initial.messages(), set, initial.createdAt(), properties.limits());
                            var deadline = current.deadline().isBefore(received.plus(input.limits().timeout())) ? current.deadline() : received.plus(input.limits().timeout());
                            if (initial.expiresAt().isBefore(deadline)) deadline = initial.expiresAt();
                            var root = new ExecutionContext(current.executionId(), current.traceId(), current.authorization(), deadline, null, null,
                                    current.releaseRef(), current.idempotencyKey());
                            var prepared = new ToolChatRunSnapshot(root, fixedInput, model, input.generation(), set, input.limits(), received);
                            return budgets.prepare(prepared, input.budgetLimits(), input.modelBudgetRef(), input.toolBudgetRefs(), current).flatMap(plan -> {
                                var fixed = new ToolChatRunSnapshot(root, fixedInput, model, input.generation(), set, input.limits(), received, plan);
                                return catalogStore.nextTurnSequence(ExecutionOwner.from(current), input.conversationId())
                                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)))
                                        .flatMap(sequence -> commit(fixed, input.conversationId(), UUID.randomUUID().toString(), sequence, input.expectedVersion(), current));
                            });
                        });
                    }));
        });
    }

    /**
     * 在工具准备、历史读取及预算计划前检查当前模型与实际网关，不用声明替代协议实现。
     */
    private Mono<ResolvedBinding> toolModel(ToolChatSubmission input, ExecutionContext current) {
        return catalog.resolve(input.binding(), input.capability(), current).flatMap(model ->
                catalog.resolveConnection(model.connection(), current).map(connection -> {
                    var modelGateway = gateway.get();
                    if (model.capability().kind() != CapabilityDescriptor.Kind.GENERATION
                            || !model.capability().features().containsAll(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT,
                            CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS))
                            || modelGateway == null || !modelGateway.supportsToolChat(model, connection))
                        throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
                    return model;
                }));
    }

    private Mono<AcceptedExecution> replay(ToolChatSubmission input, ToolChatRun run, ExecutionContext current, boolean readOnly) {
        return runs.snapshot(run.owner(), run.runId()).switchIfEmpty(Mono.error(new IllegalStateException("Run snapshot missing")))
                .flatMap(fixed -> runs.link(run.owner(), run.runId()).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT))).flatMap(link -> {
                    requireReadOnly(fixed.tools(), readOnly);
                    var user = fixed.initialContext().messages().getLast();
                    var toolAccounts = fixed.budgetPlan() == null ? java.util.Map.<String, String>of() : fixed.budgetPlan().selections().stream().filter(s -> s.kind() == CapabilityDescriptor.Kind.TOOL && s.budgetRef() != null)
                            .collect(java.util.stream.Collectors.toMap(s -> s.binding().id(), com.arte.ainew.pojo.tool.ToolChatBudgetPlan.Selection::budgetRef));
                    boolean same = toolAccounts.equals(input.toolBudgetRefs()) && link.conversationId().equals(input.conversationId()) && link.conversationVersion() == input.expectedVersion()
                            && user.content().equals(List.of(new ChatMessage.Text(input.text()))) && fixed.model().definition().equals(input.binding())
                            && fixed.model().capability().definition().equals(input.capability()) && fixed.generation().equals(input.generation())
                            && fixed.initialContext().budget().maxInputTokens() == input.maxInputTokens() && fixed.limits().equals(input.limits())
                            && fixed.tools().entries().stream().map(e -> e.binding().definition().id()).toList().equals(input.toolBindingIds())
                            && fixed.budgetPlan() != null && fixed.budgetPlan().limits().equals(input.budgetLimits().stream().sorted(Comparator.comparing(m -> m.currency().getCurrencyCode())).toList())
                            && fixed.budgetPlan().selection(fixed.model().definition()).budgetRef().equals(input.modelBudgetRef());
                    if (!same) return Mono.error(new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT));
                    return conversations.turn(link.conversationId(), link.turnId(), current)
                            .flatMap(turn -> budgets.prepare(fixed, fixed.budgetPlan().limits(), input.modelBudgetRef(), input.toolBudgetRefs(), current)
                                    .flatMap(plan -> {
                                        if (!plan.equals(fixed.budgetPlan()))
                                            return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE));
                                        return commit(fixed, link.conversationId(), link.turnId(), turn.sequence(), link.conversationVersion(), current);
                                    }));
                }));
    }

    private static void requireReadOnly(com.arte.ainew.pojo.tool.ToolSetSnapshot tools, boolean required) {
        if (required && tools.entries().stream().anyMatch(e -> e.binding().tool().tool().risk().sideEffect() != com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY))
            throw new AdmissionException(ResultCodeEnum.AI_TOOL_POLICY_DENIED);
    }

    private Mono<AcceptedExecution> commit(ToolChatRunSnapshot fixed, String conversationId, String turnId, long sequence,
                                           long version, ExecutionContext current) {
        var turn = Turn.typed(turnId, conversationId, sequence, null, null, fixed.initialContext().messages().getLast(),
                List.of(AnswerCandidate.run(fixed.context().executionId())), null, 0, fixed.receivedAt(), fixed.receivedAt());
        return access.authorize(current, false).switchIfEmpty(Mono.error(new org.springframework.security.access.AccessDeniedException("Run access denied")))
                .flatMap(permit -> runs.acceptChat(fixed, turn, version, permit::requireInCurrentTransaction))
                .flatMap(ToolChatService::applied).map(run -> new AcceptedExecution(run.runId(), AcceptedExecution.Kind.RUN, run.acceptedAt()));
    }

    /**
     * 当前本人停止资格由宿主单独检查；READ 及会话资格本身不授予工具停止权限。
     */
    public Mono<ToolChatRun> cancel(String runId, String commandKey, ExecutionContext current) {
        return conversationsForControl(runId, current).flatMap(run -> access.authorize(current, true)
                .switchIfEmpty(Mono.error(new org.springframework.security.access.AccessDeniedException("Run cancel denied")))
                .flatMap(permit -> runs.cancel(run.owner(), runId, commandKey, actual -> permit.requireInCurrentTransaction(actual.snapshot())))
                .flatMap(ToolChatService::applied));
    }

    private Mono<ToolChatRun> conversationsForControl(String runId, ExecutionContext current) {
        return authorization.requireWrite(current, AdmissionAuthorization.READ).flatMap(trusted -> runs.find(ExecutionOwner.from(trusted), runId)
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(run -> runs.link(run.owner(), runId).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(link -> conversations.turn(link.conversationId(), link.turnId(), trusted).flatMap(turn ->
                                turn.candidates().contains(AnswerCandidate.run(runId)) ? Mono.just(run) : Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND))))));
    }

    private static <T> Mono<T> applied(StoreOutcome<T> outcome) {
        return outcome.successful() ? Mono.just(outcome.value()) : Mono.error(AdmissionException.fromStoreRejection(outcome.code()));
    }
}
