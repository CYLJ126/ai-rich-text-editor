package com.arte.ainew.application.entry;

import com.arte.ainew.api.context.ContextService;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.conversation.ConversationService;
import com.arte.ainew.api.entry.ChatService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.InvocationSubmission;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * 首批单条用户文本聊天入口；会话版本由受理存储在耐久幂等判定之后检查。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
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
        return authorization.require(context, AdmissionAuthorization.INVOKE).flatMap(current ->
                // 查找会话
                conversations.find(request.conversationId(), current).flatMap(conversation -> {
                    if (conversation.state() != Conversation.State.ACTIVE) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONVERSATION_NOT_ACTIVE);
                    }
                    if (request.parentTurnId() != null || request.supersedesTurnId() != null || conversation.chatProfile() != null
                            || !conversation.resources().isEmpty()) {
                        throw new AdmissionException(ResultCodeEnum.AI_CHAT_SELECTION_NOT_SUPPORTED);
                    }
                    // 解析绑定
                    return bindings.resolve(request.binding(), request.capability(), current)
                            // 组装上下文
                            .flatMap(binding -> contexts.assemble(request.context(), binding, current))
                            // 提交执行
                            .flatMap(snapshot -> {
                                var generation = new GenerationRequest(snapshot.messages(), request.generationOptions(), List.of(), new GenerationRequest.TextOutput());
                                var invocation = new InvocationRequest<>(request.capability(), request.binding(), generation.kind(), generation, request.options(), current);
                                var now = clock.instant();
                                // 组装轮次
                                var turn = new Turn(UUID.randomUUID().toString(), conversation.conversationId(),
                                        Math.addExact(request.expectedVersion(), 1), null, null, snapshot.messages().getFirst(),
                                        List.of(current.executionId()), null, 0, now, now);
                                var link = new Invocation.ConversationLink(conversation.conversationId(), request.expectedVersion(), turn.turnId());
                                // 委托给执行协调器
                                return coordinator.submit(new InvocationSubmission<>(invocation, snapshot, link, turn, null));
                            });
                }));
    }

    @Override
    public Mono<AcceptedExecution> regenerate(EntryRequests.Regenerate request, ExecutionContext context) {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED));
    }
}
