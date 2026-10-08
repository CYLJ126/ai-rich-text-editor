package com.arte.ainew.application.entry;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
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
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(currentContext ->
                        // 查找会话
                        conversationsService.find(request.conversationId(), currentContext).flatMap(conversation -> {
                            if (conversation.state() != Conversation.State.ACTIVE) {
                                throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_ACTIVE);
                            }
                            if (request.parentTurnId() != null || request.supersedesTurnId() != null || conversation.chatProfile() != null
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
                                    .flatMap(binding -> contextService.assemble(selection, binding, currentContext))
                                    // 提交执行
                                    .flatMap(snapshot -> admissionCatalogStore.nextTurnSequence(ExecutionOwner.from(currentContext), conversation.conversationId())
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
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current ->
                // 查找原调用（invocationId 对应的记录）
                executionStore.find(ExecutionOwner.from(current), request.originalInvocationId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(original -> {
                            // 校验对应的会话是否为空，或是否为 GenerationRequest 请求
                            if (original.conversation() == null || !(original.request().input() instanceof GenerationRequest)) {
                                return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
                            }
                            var link = original.conversation();
                            // 预算和生成参数继承原调用；当前主体必须仍有使用该预算及绑定的权限。
                            var renewed = new ExecutionContext(current.executionId(), current.traceId(), current.authorization(),
                                    current.deadline(), null, original.request().context().budgetRef(),
                                    original.request().context().releaseRef(), current.idempotencyKey());
                            // 查找对应轮次（Turn）
                            return conversationsService.turn(link.conversationId(), link.turnId(), renewed).flatMap(turn ->
                                    // 查找轮次的调用记录（Invocation）
                                    executionStore.findAccepted(ExecutionOwner.from(renewed), original.request().capability().id(), renewed.idempotencyKey())
                                            .map(accepted -> true).defaultIfEmpty(false).flatMap(replay ->
                                                    // 查找对应上下文快照（ContextSnapshot）
                                                    contextService.find(original.contextSnapshotId(), renewed)
                                                            // 新的提交
                                                            .flatMap(saved -> invocationCoordinator.submit(regenerationSubmission(request, renewed, original, turn, saved, replay)))));
                        }));
    }

    /**
     * 生成轮次重放提交
     *
     * @param request  重放请求
     * @param context  当前执行上下文
     * @param original 原调用记录
     * @param turn     轮次记录
     * @param saved    上下文快照
     * @param replay   是否为重放提交
     * @return 生成的轮次重放提交记录（即本次重新提交生成对应的调用记录）
     */
    private InvocationSubmission<GenerationRequest> regenerationSubmission(EntryRequests.Regenerate request, ExecutionContext context,
                                                                           Invocation original, Turn turn, ContextSnapshot saved, boolean replay) {
        var at = clock.instant();
        var snapshot = new ContextSnapshot(UUID.randomUUID().toString(), saved.history(), saved.modelBinding(),
                saved.messages(), saved.fragments(), saved.budget(), saved.inputTokens(), saved.estimatedTokens(),
                saved.tokenizerVersion(), saved.truncations(), saved.contentDigest(), at,
                at.plus(properties.limits().snapshotRetention()));
        var candidates = new ArrayList<>(turn.invocationIds());
        if (!replay) candidates.add(context.executionId());
        // 重放不追加候选（包括候选已达到上限时）；协调器仍核对完整语义摘要。
        var updated = replay ? turn : new Turn(turn.turnId(), turn.conversationId(), turn.sequence(), turn.parentTurnId(),
                turn.supersedesTurnId(), turn.userMessage(), candidates,
                turn.selectedInvocationId() != null ? turn.selectedInvocationId() : turn.invocationIds().getLast(),
                turn.version() + 1, turn.createdAt(), at);
        var generation = (GenerationRequest) original.request().input();
        var input = new GenerationRequest(snapshot.messages(), generation.options(), generation.tools(), generation.outputFormat());
        var invocation = new InvocationRequest<>(original.request().capability(), original.request().binding(), input.kind(), input, request.options(), context);
        return new InvocationSubmission<>(invocation, snapshot,
                new Invocation.ConversationLink(turn.conversationId(), request.expectedConversationVersion(), turn.turnId()),
                updated, request.originalInvocationId());
    }

}
