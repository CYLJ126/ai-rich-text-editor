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
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.InvocationSubmission;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
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
    private final ConversationService conversations;
    private final BindingManager bindings;
    private final ContextService contexts;
    private final InvocationCoordinator coordinator;
    private final Clock clock;

    public DefaultChatService(AdmissionAuthorization authorization, ConversationService conversations, BindingManager bindings,
                              ContextService contexts, InvocationCoordinator coordinator, Clock clock) {
        this.authorization = authorization;
        this.conversations = conversations;
        this.bindings = bindings;
        this.contexts = contexts;
        this.coordinator = coordinator;
        this.clock = clock;
    }

    @Override
    public Mono<AcceptedExecution> submit(EntryRequests.Chat request, ExecutionContext context) {
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(currentContext ->
                // 查找会话
                conversations.find(request.conversationId(), currentContext).flatMap(conversation -> {
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
                    return bindings.resolve(request.binding(), request.capability(), currentContext)
                            // 组装上下文
                            .flatMap(binding -> contexts.assemble(selection, binding, currentContext))
                            // 提交执行
                            .flatMap(snapshot -> {
                                var generation = new GenerationRequest(snapshot.messages(), request.generationOptions(), List.of(), new GenerationRequest.TextOutput());
                                var invocation = new InvocationRequest<>(request.capability(), request.binding(), generation.kind(), generation, request.options(), currentContext);
                                var now = clock.instant();
                                // 组装轮次
                                var turn = new Turn(UUID.randomUUID().toString(), conversation.conversationId(),
                                        Math.addExact(request.expectedVersion(), 1), null, null, snapshot.messages().getLast(),
                                        List.of(currentContext.executionId()), null, 0, now, now);
                                var link = new Invocation.ConversationLink(conversation.conversationId(), request.expectedVersion(), turn.turnId());
                                // 委托给执行协调器
                                return coordinator.submit(new InvocationSubmission<>(invocation, snapshot, link, turn, null));
                            });
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
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
    }
}
