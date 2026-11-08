package com.arte.ainew.application.entry;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.conversation.ConversationExecutionPolicy;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.TextInputs;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.InvocationSubmission;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 单条用户文本聊天入口，自动加载最近十轮完整历史；会话版本参与耐久幂等与受理检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Slf4j
public final class DefaultChatService implements ChatService {
    private final AdmissionAuthorization authorization;
    private final ConversationService conversationsService;
    private final BindingManager bindingManager;
    private final ContextService contextService;
    private final InvocationCoordinator invocationCoordinator;
    private final Clock clock;
    private final ExecutionStore executionStore;
    private final AdmissionCatalogStore admissionCatalogStore;
    private final NewAiProperties properties;

    public DefaultChatService(AdmissionAuthorization authorization, ConversationService conversationsService, BindingManager bindingManager,
                              ContextService contextService, InvocationCoordinator invocationCoordinator, Clock clock, ExecutionStore executionStore, AdmissionCatalogStore admissionCatalogStore, NewAiProperties properties) {
        this.authorization = authorization;
        this.conversationsService = conversationsService;
        this.bindingManager = bindingManager;
        this.contextService = contextService;
        this.invocationCoordinator = invocationCoordinator;
        this.clock = clock;
        this.executionStore = executionStore;
        this.admissionCatalogStore = admissionCatalogStore;
        this.properties = properties;
    }

    @Override
    public Mono<AcceptedExecution> submit(EntryRequests.Chat request, ExecutionContext context) {
        return authorization.prepare(context, java.util.Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION), false, currentContext ->
                        // 查找会话
                        conversationsService.find(request.conversationId(), currentContext).flatMap(conversation -> {
                            if (conversation.state() != Conversation.State.ACTIVE) {
                                throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_ACTIVE);
                            }
                            if (request.parentTurnId() != null || request.supersedesTurnId() != null
                                    || !conversation.resources().isEmpty() || request.context().history() != null) {
                                throw new AdmissionException(ResultCodeEnum.AI_CHAT_SELECTION_NOT_SUPPORTED);
                            }
                            var input = request.context();
                            var selection = new ContextRequest(input.messages(), new ContextRequest.HistorySelection(
                                    conversation.conversationId(), request.expectedVersion(), List.of(), ChatHistoryLoader.MAX_TURNS),
                                    input.memoryIds(), input.resources(), input.target(), input.budget());
                            // 解析绑定
                            return bindingManager.resolve(request.binding(), request.capability(), currentContext)
                                    // 组装上下文
                                    .flatMap(binding -> conversation.chatProfile() == null
                                            ? contextService.assemble(selection, binding, currentContext)
                                            : contextService.assembleForAssistant(selection, binding, currentContext, conversation.chatProfile()))
                                    // 提交执行
                                    .flatMap(snapshot -> admissionCatalogStore.nextTurnSequence(ExecutionOwners.from(currentContext), conversation.conversationId())
                                            .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)))
                                            .flatMap(sequence -> {
                                                var generation = new GenerationRequest(snapshot.messages(), request.generationOptions(), List.of(), new GenerationRequest.TextOutput());
                                                var invocation = new InvocationRequest<>(request.capability(), request.binding(), generation.kind(), generation, request.options(), currentContext);
                                                var now = clock.instant();
                                                // 组装轮次
                                                var turn = new Turn(UUID.randomUUID().toString(), conversation.conversationId(),
                                                        sequence, null, null, snapshot.messages().getLast(),
                                                        List.of(currentContext.executionId()), null, 0, now, now);
                                                var link = new Invocation.ConversationLink(conversation.conversationId(), request.expectedVersion(), turn.turnId());
                                                // 委托给执行协调器
                                                return invocationCoordinator.submit(new InvocationSubmission<>(invocation, snapshot, link, turn, null));
                                            }));
                        }))
                .doOnSubscribe(ignored -> log.info("AI chat submission started, invocationId={}, traceId={}, conversationId={}, expectedVersion={}, bindingId={}",
                        context.executionId(), context.traceId(), request.conversationId(), request.expectedVersion(), request.binding().id()))
                .doOnNext(accepted -> log.info("AI chat submission accepted, requestedInvocationId={}, acceptedInvocationId={}, traceId={}, conversationId={}",
                        context.executionId(), accepted.executionId(), context.traceId(), request.conversationId()))
                .doOnError(error -> log.warn("AI chat submission failed, invocationId={}, traceId={}, conversationId={}, code={}, type={}",
                        context.executionId(), context.traceId(), request.conversationId(),
                        error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "CHAT_SUBMISSION_FAILED",
                        error.getClass().getName()));
    }

    @Override
    public Mono<AcceptedExecution> regenerate(EntryRequests.Regenerate request, ExecutionContext context) {
        return authorization.requireWrite(context, AdmissionAuthorization.INVOKE).flatMap(current ->
                executionStore.find(ExecutionOwners.from(current), request.originalInvocationId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(original -> {
                            if (original.conversation() == null || !(original.request().input() instanceof GenerationRequest))
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
                            var link = original.conversation();
                            // 新操作继承原预算、发布及精确模型版本；当前身份不继承旧授权。
                            var renewed = new ExecutionContext(current.executionId(), current.traceId(), current.authorization(),
                                    current.deadline(), null, original.request().context().budgetRef(),
                                    original.request().context().releaseRef(), current.idempotencyKey());
                            var owner = ExecutionOwners.from(renewed);
                            return conversationsService.find(link.conversationId(), renewed).flatMap(conversation ->
                                    conversationsService.turn(link.conversationId(), link.turnId(), renewed).flatMap(turn ->
                                            authorization.require(renewed, AdmissionAuthorization.READ).flatMap(read ->
                                                    executionStore.findAccepted(owner, original.request().capability().id(), renewed.idempotencyKey())
                                                            // 已受理同键先确认原操作，不因后来新增问题、源快照到期／删除或模型停用重建调用。
                                                            .map(accepted -> regenerationReceipt(request, original, accepted))
                                                            .switchIfEmpty(Mono.defer(() -> regenerateNew(request, read, original, conversation, turn))))));
                        }));
    }

    /**
     * 新操作的读取预检；会话版本、最新轮次及当前资格仍由最终受理事务复核。
     */
    private Mono<AcceptedExecution> regenerateNew(EntryRequests.Regenerate request, ExecutionContext current,
                                                  Invocation original, Conversation conversation, Turn turn) {
        if (!ConversationExecutionPolicy.ordinaryText(original, conversation, turn))
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
        if (!original.state().terminal())
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_BUSY));
        if (conversation.version() != request.expectedConversationVersion())
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT));
        return admissionCatalogStore.nextTurnSequence(ExecutionOwners.from(current), conversation.conversationId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND)))
                .flatMap(next -> {
                    if (turn.sequence() != next - 1)
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_INVALID_STATE));
                    return contextService.find(original.contextSnapshotId(), current).flatMap(saved -> {
                        TextInputs.requireActualSnapshot(original, saved);
                        if (!saved.messages().getLast().equals(turn.userMessage()))
                            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
                        return invocationCoordinator.submit(regenerationSubmission(request, current, original, turn, saved));
                    });
                });
    }

    /**
     * 核对原重新生成操作的完整可变请求语义；只返回已授权的原回执，不读取结果正文或重新派发。
     */
    private AcceptedExecution regenerationReceipt(EntryRequests.Regenerate request, Invocation source, Invocation accepted) {
        var link = accepted.conversation();
        var expected = accepted.request().options();
        var actual = request.options();
        if (!ExecutionOwners.from(source.request().context()).equals(ExecutionOwners.from(accepted.request().context()))
                || !accepted.request().capability().equals(source.request().capability())
                || !accepted.request().binding().equals(source.request().binding())
                || !accepted.request().input().equals(source.request().input())
                || !Objects.equals(accepted.request().context().budgetRef(), source.request().context().budgetRef())
                || !request.originalInvocationId().equals(accepted.replacesInvocationId()) || link == null
                || !link.conversationId().equals(source.conversation().conversationId())
                || !link.turnId().equals(source.conversation().turnId())
                || link.conversationVersion() != request.expectedConversationVersion()
                || expected.maxAttempts() != actual.maxAttempts() || expected.maxOutputBytes() != actual.maxOutputBytes()
                || expected.maxToolSteps() != actual.maxToolSteps() || expected.maxConcurrentTools() != actual.maxConcurrentTools()
                || !Objects.equals(expected.requestedTimeout(), actual.requestedTimeout())
                || expected.requestedTimeout() == null && !expected.deadline().equals(actual.deadline()))
            throw new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
        return new AcceptedExecution(accepted.request().context().executionId(), AcceptedExecution.Kind.INVOCATION, accepted.acceptedAt());
    }

    /**
     * 沿用原实际输入创建独立重新生成候选
     *
     * @param request  新的重新生成请求
     * @param context  当前执行上下文
     * @param original 原调用记录
     * @param turn     轮次记录
     * @param saved    上下文快照
     * @return 原轮次新增候选及独立调用的受理参数
     */
    private InvocationSubmission<GenerationRequest> regenerationSubmission(EntryRequests.Regenerate request, ExecutionContext context,
                                                                           Invocation original, Turn turn, ContextSnapshot saved) {
        var at = clock.instant();
        var snapshot = new ContextSnapshot(UUID.randomUUID().toString(), saved.history(), saved.modelBinding(),
                saved.messages(), saved.fragments(), saved.budget(), saved.inputTokens(), saved.estimatedTokens(),
                saved.tokenizerVersion(), saved.truncations(), saved.contentDigest(), at,
                at.plus(properties.limits().snapshotRetention()), saved.assistant());
        var candidates = new ArrayList<>(turn.candidates());
        candidates.add(AnswerCandidate.invocation(context.executionId()));
        // 重放已在上层返回原回执；只有新操作沿用原实际输入并追加候选。
        var turnUpdatedAt = at.isBefore(turn.updatedAt()) ? turn.updatedAt() : at;
        var updated = Turn.typed(turn.turnId(), turn.conversationId(), turn.sequence(), turn.parentTurnId(),
                turn.supersedesTurnId(), turn.userMessage(), candidates,
                turn.effectiveCandidate(),
                turn.version() + 1, turn.createdAt(), turnUpdatedAt);
        var generation = (GenerationRequest) original.request().input();
        var input = new GenerationRequest(snapshot.messages(), generation.options(), generation.tools(), generation.outputFormat());
        var invocation = new InvocationRequest<>(original.request().capability(), original.request().binding(), input.kind(), input, request.options(), context);
        return new InvocationSubmission<>(invocation, snapshot,
                new Invocation.ConversationLink(turn.conversationId(), request.expectedConversationVersion(), turn.turnId()),
                updated, request.originalInvocationId());
    }

}
