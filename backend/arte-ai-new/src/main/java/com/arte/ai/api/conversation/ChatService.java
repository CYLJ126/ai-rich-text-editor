package com.arte.ai.api.conversation;

import com.arte.ai.api.context.ContextService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.conversation.ChatTurnResult;
import com.arte.ai.model.conversation.Turn;
import com.arte.ai.model.conversation.TurnKind;
import com.arte.ai.model.conversation.TurnStatus;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.execution.InvocationRequest;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.spi.security.ModelConsentProvider;
import com.arte.ai.spi.store.ChatStore;
import com.arte.ai.validation.ChatContractChecks;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.*;
import com.arte.base.validation.ContractChecks;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 最小非流式聊天：耐久提交、固定上下文、统一模型入口及可靠关联恢复。
 * 不自动重试已派发执行；同一提交的驱动用数据库行锁串行，模型受理仍独立提交。
 */
public class ChatService {
    private final ConversationService conversations;
    private final ContextService contexts;
    private final ChatStore store;
    private final InvocationCoordinator coordinator;
    private final DefinitionRef capability;
    private final Clock clock;
    private final int outputTokens;
    private static final ExecutionOptions OPTIONS = new ExecutionOptions(Duration.ofSeconds(90), false);

    public ChatService(ConversationService conversations, ContextService contexts, ChatStore store,
                       InvocationCoordinator coordinator, DefinitionRef capability, Clock clock, int outputTokens) {
        this.conversations = Objects.requireNonNull(conversations);
        this.contexts = Objects.requireNonNull(contexts);
        this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.capability = Objects.requireNonNull(capability);
        this.clock = Objects.requireNonNull(clock);
        if (outputTokens <= 0) throw new IllegalArgumentException("outputTokens must be positive");
        this.outputTokens = outputTokens;
    }

    public ChatTurnResult submit(ExecutionContext viewer, String conversationId, long version, String text,
                                 ModelOptions options, String key, ModelConsentProvider consent) {
        ContractChecks.required(text, "text");
        if (text.isBlank() || text.length() > 1048576) throw new IllegalArgumentException("invalid text size");
        return submit(viewer, conversationId, version, List.of(new Message(MessageRole.USER, List.of(new TextPart(text)))),
                normalize(options), null, key, consent);
    }

    public ChatTurnResult regenerate(ExecutionContext viewer, String conversationId, long version, String originalId,
                                     ModelOptions options, String key, ModelConsentProvider consent) {
        conversations.find(viewer, conversationId);
        var original = requiredTurn(viewer, conversationId, originalId);
        if (original.status() != TurnStatus.ACCEPTED)
            throw ChatValues.failure(CommonErrorCode.INVALID_ARGUMENT, "chat-regenerate");
        var execution = coordinator.find(viewer, original.executionId());
        if (!terminal(execution.status()) || execution.status() == ExecutionStatus.OUTCOME_UNKNOWN)
            throw ChatValues.failure(CommonErrorCode.BUSY, "chat-regenerate");
        return submit(viewer, conversationId, version, original.input(),
                options == null ? original.modelOptions() : normalize(options), originalId, key, consent);
    }

    private ChatTurnResult submit(ExecutionContext viewer, String conversationId, long version, List<Message> input,
                                  ModelOptions options, String original, String key, ModelConsentProvider consent) {
        ContractChecks.required(consent, "consent");
        ChatContractChecks.identifier(key, 128, "idempotencyKey");
        ChatContractChecks.positive(version, "conversationVersion");
        conversations.find(viewer, conversationId);
        reconcileActive(viewer, conversationId);
        var now = ChatValues.now(clock);
        var kind = original == null ? TurnKind.MESSAGE : TurnKind.REGENERATION;
        String operation = original == null ? "chat.turn.submit" : "chat.turn.regenerate";
        var digest = ChatValues.submission(conversationId, version, original, input, options);
        var draft = new Turn(UUID.randomUUID().toString(), conversationId, viewer.scope(), 1, version, 1, kind,
                TurnStatus.PREPARING, input, options, original, null, null, new IdempotencyKey(key, operation, digest), null, now, now, null);
        var claimed = store.claim(draft);
        // Commit the immutable context before any model acceptance transaction can begin.
        var prepared = store.withTurn(viewer.scope(), claimed.turnId(), turn -> {
            if (turn.status() != TurnStatus.PREPARING) return turn;
            try {
                var conversation = conversations.find(viewer, conversationId);
                return store.ready(turn, contexts.prepare(viewer, conversation, turn), ChatValues.now(clock));
            } catch (BaseException rejected) {
                if (!confirmed(rejected.error())) throw rejected;
                return store.reject(turn, rejected.error(), ChatValues.now(clock));
            }
        });
        if (prepared.status() == TurnStatus.READY) {
            prepared = store.withTurn(viewer.scope(), prepared.turnId(), turn -> drive(viewer, turn, consent));
        }
        if (prepared.status() == TurnStatus.REJECTED) throw new BaseException(prepared.rejectionError());
        return find(viewer, conversationId, prepared.turnId());
    }

    private Turn drive(ExecutionContext viewer, Turn turn, ModelConsentProvider consent) {
        if (turn.status() != TurnStatus.READY) return turn;
        var recovered = recover(viewer, turn);
        if (recovered.status() == TurnStatus.ACCEPTED) return recovered;
        var conversation = conversations.find(viewer, turn.conversationId());
        var snapshot = store.snapshot(viewer.scope(), turn.contextSnapshotId())
                .orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-context"));
        InvocationRequest<GenerationRequest> request;
        com.arte.base.model.resource.ResourceRef consentRef;
        try {
            contexts.recheck(viewer, snapshot, true);
            if (!snapshot.modelBindingRef().equals(conversation.modelBindingRef()) || snapshot.conversationVersion() != turn.conversationVersion())
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-binding");
            request = new InvocationRequest<>(capability, snapshot.modelBindingRef(),
                    new GenerationRequest(snapshot.messages(), turn.modelOptions(), List.of(), null), OPTIONS, viewer);
            var prepared = coordinator.prepare(request);
            consentRef = consent.confirm(coordinator.egressRequest(request, prepared, null));
        } catch (BaseException rejected) {
            if (!confirmed(rejected.error())) throw rejected;
            return store.reject(turn, rejected.error(), ChatValues.now(clock));
        }
        AcceptedExecution accepted;
        try {
            accepted = coordinator.submitModel(request, consentRef, ChatValues.modelKey(turn));
        } catch (RuntimeException failed) {
            // Even a confirmed dispatch rejection may follow a committed model acceptance.
            // A failed reconciliation must leave READY intact for later recovery.
            recovered = recover(viewer, turn);
            if (recovered.status() == TurnStatus.ACCEPTED) return recovered;
            if (failed instanceof BaseException known && confirmed(known.error()))
                return store.reject(turn, known.error(), ChatValues.now(clock));
            throw failed;
        }
        return store.accept(turn, accepted.executionId(), ChatValues.now(clock));
    }

    private Turn recover(ExecutionContext viewer, Turn turn) {
        if (turn.status() != TurnStatus.READY) return turn;
        var execution = coordinator.findIdempotent(viewer, ChatValues.modelKey(turn));
        if (execution.isEmpty()) return turn;
        var snapshot = store.snapshot(viewer.scope(), turn.contextSnapshotId()).orElseThrow();
        if (!snapshot.modelBindingRef().equals(execution.get().bindingRef()) || !capability.equals(execution.get().capabilityRef()))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-recovery");
        return store.accept(turn, execution.get().executionId(), ChatValues.now(clock));
    }

    public ChatTurnResult find(ExecutionContext viewer, String conversationId, String turnId) {
        conversations.find(viewer, conversationId);
        requiredTurn(viewer, conversationId, turnId);
        var turn = store.withTurn(viewer.scope(), turnId, current -> recover(viewer, current));
        if (turn.status() != TurnStatus.ACCEPTED) return new ChatTurnResult(turn, null);
        var execution = coordinator.find(viewer, turn.executionId());
        if (terminal(execution.status()) && turn.occupiesConversationSlot()) {
            turn = store.withTurn(viewer.scope(), turnId, current -> current.occupiesConversationSlot()
                    ? store.release(current, ChatValues.now(clock)) : current);
        }
        return new ChatTurnResult(turn, execution);
    }

    public List<ChatTurnResult> history(ExecutionContext viewer, String conversationId, long beforeSequence, int limit) {
        conversations.find(viewer, conversationId);
        if (beforeSequence < 1 || limit < 1 || limit > 100) throw new IllegalArgumentException("invalid history page");
        return store.turns(viewer.scope(), conversationId, beforeSequence, limit).stream()
                .map(turn -> find(viewer, conversationId, turn.turnId())).toList();
    }

    public CancellationStatus cancel(ExecutionContext viewer, String conversationId, String turnId) {
        var current = find(viewer, conversationId, turnId);
        if (current.execution() == null) throw ChatValues.failure(CommonErrorCode.BUSY, "chat-cancel");
        // A request to cancel does not release the serial slot.
        return coordinator.cancel(viewer, current.turn().executionId());
    }

    public void reconcileActive(ExecutionContext viewer, String conversationId) {
        store.activeTurn(viewer.scope(), conversationId).ifPresent(turn -> find(viewer, conversationId, turn.turnId()));
    }

    private Turn requiredTurn(ExecutionContext viewer, String conversation, String id) {
        var turn = store.turn(viewer.scope(), ContractChecks.identifier(id, "turnId"))
                .orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-turn"));
        if (!turn.conversationId().equals(conversation))
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-turn");
        return turn;
    }

    private ModelOptions normalize(ModelOptions options) {
        if (options == null) return new ModelOptions(null, outputTokens);
        int tokens = options.maxOutputTokens() == null ? outputTokens : options.maxOutputTokens();
        if (tokens > outputTokens) throw new IllegalArgumentException("maxOutputTokens exceeds model limit");
        return new ModelOptions(options.temperature(), tokens);
    }

    private static boolean confirmed(ExecutionError error) {
        return error.sideEffectStatus() == SideEffectStatus.NONE && error.resultCertainty() == ResultCertainty.CONFIRMED;
    }

    private static boolean terminal(ExecutionStatus status) {
        return status != ExecutionStatus.ACCEPTED && status != ExecutionStatus.RUNNING;
    }
}
