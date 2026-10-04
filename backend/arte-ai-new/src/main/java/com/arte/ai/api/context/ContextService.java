package com.arte.ai.api.context;

import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.*;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.conversation.Turn;
import com.arte.ai.model.conversation.TurnKind;
import com.arte.ai.model.conversation.TurnStatus;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.spi.store.ChatStore;
import com.arte.ai.spi.strategy.TokenEstimator;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;

import java.time.Clock;
import java.time.Duration;
import java.util.*;

/**
 * 仅组装用户文本与已授权成功历史，不派发模型或读取业务资料。
 */
public class ContextService {
    private final ChatStore store;
    private final InvocationCoordinator coordinator;
    private final Clock clock;
    private final int byteLimit, historyPairs;
    private final Duration lifetime;
    private final TokenEstimator estimator;
    private final int contextWindow, safetyTokens;
    private ResourceContextService resourceContexts;

    public ContextService withResources(ResourceContextService resources) {
        this.resourceContexts = Objects.requireNonNull(resources);
        return this;
    }

    public ContextService(ChatStore store, InvocationCoordinator coordinator, Clock clock, int byteLimit,
                          int historyPairs, Duration lifetime) {
        this(store, coordinator, clock, byteLimit, historyPairs, lifetime, 0, 0, null);
    }

    public ContextService(ChatStore store, InvocationCoordinator coordinator, Clock clock, int byteLimit,
                          int historyPairs, Duration lifetime, int contextWindow, int safetyTokens, TokenEstimator estimator) {
        if (byteLimit < 1 || byteLimit > 1048576 || historyPairs < 0 || historyPairs > 32
                || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("invalid context limits");
        this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.clock = Objects.requireNonNull(clock);
        this.byteLimit = byteLimit;
        this.historyPairs = historyPairs;
        this.lifetime = lifetime;
        if (estimator != null && (contextWindow < 256 || contextWindow > 2097152 || safetyTokens < 0 || safetyTokens >= contextWindow))
            throw new IllegalArgumentException("invalid context token limits");
        this.estimator = estimator;
        this.contextWindow = contextWindow;
        this.safetyTokens = safetyTokens;
    }

    /**
     * 准备上下文。
     *
     * @param viewer       执行上下文
     * @param conversation 会话
     * @param draft        草稿转录
     * @return 上下文快照
     */
    public ContextSnapshot prepare(ExecutionContext viewer, Conversation conversation, Turn draft) {
        if (!draft.scope().equals(viewer.scope()) || !conversation.scope().equals(viewer.scope())
                || !draft.conversationId().equals(conversation.conversationId()))
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-context");
        List<Message> messages;
        List<ContextHistoryRef> history;
        var fragments = new ArrayList<ContextFragment>();
        String selectionDigest = null;
        if (draft.resourceContext() != null) {
            if (resourceContexts == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-resources");
            resourceContexts.recheck(viewer, draft.resourceContext(), false);
            if (!clock.instant().isBefore(draft.resourceContext().expiresAt()))
                throw ChatValues.failure(CommonErrorCode.DEADLINE_EXCEEDED, "rag-preview-expired");
            if (!conversation.modelBindingRef().equals(draft.resourceContext().bindingRef()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-binding");
            fragments.addAll(draft.resourceContext().fragments());
            selectionDigest = draft.resourceContext().selectionDigest();
        }
        if (draft.kind() == TurnKind.REGENERATION) {
            var original = requiredTurn(viewer, draft.regeneratesTurnId(), draft.conversationId());
            var previous = store.snapshot(viewer.scope(), original.contextSnapshotId())
                    .orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-context"));
            if (!previous.modelBindingRef().equals(conversation.modelBindingRef()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-binding");
            // Re-generation uses the original input context, never its previous answer or later questions.
            recheck(viewer, previous, false);
            messages = previous.messages();
            history = previous.history();
            fragments.clear(); fragments.addAll(previous.fragments());
            selectionDigest = previous.resourceContext() == null ? null : previous.resourceContext().selectionDigest();
        } else {
            var selected = new ArrayList<HistoryPair>();
            var roots = new HashSet<String>();
            if (historyPairs > 0)
                for (var turn : store.turns(viewer.scope(), draft.conversationId(), draft.sequence(), 256)) {
                    if (turn.status() != TurnStatus.ACCEPTED) continue;
                    var root = root(viewer, turn);
                    if (roots.contains(root.turnId())) continue;
                    var execution = coordinator.find(viewer, turn.executionId());
                    if (execution.status() != ExecutionStatus.SUCCEEDED || execution.result() == null) continue;
                    if (!execution.result().toolCalls().isEmpty() || execution.result().structuredOutput() != null
                            || execution.result().output().isEmpty())
                        throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-history");
                    var pair = new ArrayList<>(turn.input());
                    pair.add(new Message(MessageRole.ASSISTANT, execution.result().output()));
                    ChatValues.bytes(pair);
                    selected.add(new HistoryPair(root.sequence(), turn, List.copyOf(pair), execution.resourceContext()));
                    roots.add(root.turnId());
                    if (selected.size() == historyPairs) break;
                }
            selected.sort(Comparator.comparingLong(HistoryPair::sequence));
            var currentInput = draft.resourceContext() == null ? draft.input() : draft.resourceContext().messages();
            var prepared = new ArrayList<>(currentInput);
            if (!fits(prepared, draft.modelOptions().maxOutputTokens()))
                throw ChatValues.failure(CommonErrorCode.INVALID_ARGUMENT, "chat-capacity");
            // Keep whole question/answer pairs, trimming the oldest selected pairs first.
            while (!selected.isEmpty()) {
                var candidate = new ArrayList<Message>();
                selected.forEach(pair -> candidate.addAll(pair.messages()));
                candidate.addAll(currentInput);
                if (fits(candidate, draft.modelOptions().maxOutputTokens())) {
                    prepared = candidate;
                    break;
                }
                selected.removeFirst();
            }
            int historicalCitation = 0;
            var sourceRefs = new HashSet<ResourceRef>();
            fragments.forEach(fragment -> sourceRefs.add(fragment.source().resource()));
            for (var pair : selected) {
                if (pair.resources() == null) continue;
                String answer = pair.messages().getLast().parts().stream().map(part -> ((TextPart) part).text()).collect(java.util.stream.Collectors.joining("\n"));
                for (var source : pair.resources().fragments()) {
                    if (!sourceRefs.add(source.source().resource())) continue;
                    String citation = "history-" + (++historicalCitation);
                    fragments.add(new ContextFragment(citation,
                            new SourceRef(source.source().resource(), citation), answer, true,
                            "历史回答的来源；历史轮次 " + pair.turn().turnId() + "；本轮使用派生回答，并非原文全文"));
                }
            }
            if (selectionDigest == null && !fragments.isEmpty()) selectionDigest = ResourceContextValues.textDigest("chat-history-provenance");
            messages = List.copyOf(prepared);
            history = selected.stream().map(pair -> new ContextHistoryRef(pair.turn().turnId(), pair.turn().version(), pair.turn().executionId())).toList();
        }
        int bytes = ChatValues.bytes(messages);
        if (bytes > byteLimit) throw ChatValues.failure(CommonErrorCode.INVALID_ARGUMENT, "chat-capacity");
        int tokens = draft.modelOptions().maxOutputTokens();
        if (!fits(messages, tokens)) throw ChatValues.failure(CommonErrorCode.INVALID_ARGUMENT, "chat-token-capacity");
        var budget = estimator == null ? new ContextBudget(byteLimit, bytes, tokens)
                : new ContextBudget(byteLimit, bytes, tokens, contextWindow, contextWindow - tokens - safetyTokens, estimator.estimate(messages), safetyTokens, estimator.version());
        var now = ChatValues.now(clock);
        ResourceContextSnapshot resources = null;
        if (fragments.size() > 512) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-source-capacity");
        if (!fragments.isEmpty()) {
            if (resourceContexts == null || estimator == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-resources");
            resources = new ResourceContextSnapshot(UUID.randomUUID().toString(), viewer.scope(),
                    conversation.modelBindingRef(), messages, fragments, budget,
                    ResourceContextValues.digest(viewer.scope(), conversation.modelBindingRef(), messages, fragments, budget, selectionDigest),
                    selectionDigest, now, now.plus(lifetime));
        }
        var digest = ChatValues.context(draft.conversationId(), draft.conversationVersion(), conversation.modelBindingRef(), messages, history, budget, resources);
        return new ContextSnapshot(UUID.randomUUID().toString(), draft.conversationId(), viewer.scope(), draft.conversationVersion(),
                conversation.modelBindingRef(), messages, fragments, history, budget, digest, now, now.plus(lifetime), resources);
    }

    public void recheckResources(ExecutionContext viewer, ResourceContextSnapshot resources) {
        if (resources == null) return;
        if (resourceContexts == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-resources");
        resourceContexts.recheck(viewer, resources, false);
    }

    public void recheck(ExecutionContext viewer, ContextSnapshot snapshot, boolean requireFresh) {
        if (!snapshot.scope().equals(viewer.scope()))
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-context");
        ChatValues.verify(snapshot);
        if (snapshot.resourceContext() != null) {
            if (resourceContexts == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-resources");
            resourceContexts.recheck(viewer, snapshot.resourceContext(), requireFresh);
        }
        if (!fits(snapshot.messages(), snapshot.budget().outputTokenReserve())
                || snapshot.budget().estimatorVersion() != null && estimator != null
                && (!estimator.version().equals(snapshot.budget().estimatorVersion()) || estimator.estimate(snapshot.messages()) != snapshot.budget().estimatedInputTokens()))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-token-capacity");
        if (requireFresh && snapshot.isExpiredAt(clock.instant()))
            throw ChatValues.failure(CommonErrorCode.DEADLINE_EXCEEDED, "chat-context");
        if (snapshot.messages().size() > 128 || snapshot.messages().stream().anyMatch(m -> m.role() != MessageRole.USER && m.role() != MessageRole.ASSISTANT))
            throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-context");
        for (var ref : snapshot.history()) {
            var turn = requiredTurn(viewer, ref.turnId(), snapshot.conversationId());
            if (turn.version() != ref.turnVersion() || !Objects.equals(turn.executionId(), ref.executionId()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-history");
            if (coordinator.find(viewer, ref.executionId()).status() != ExecutionStatus.SUCCEEDED)
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-history");
        }
    }

    public Turn root(ExecutionContext viewer, Turn turn) {
        for (int depth = 0; depth < 128; depth++) {
            if (turn.kind() == TurnKind.MESSAGE) return turn;
            var previous = requiredTurn(viewer, turn.regeneratesTurnId(), turn.conversationId());
            if (previous.sequence() >= turn.sequence())
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "chat-history");
            turn = previous;
        }
        throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "chat-history-depth");
    }

    private Turn requiredTurn(ExecutionContext viewer, String id, String conversation) {
        var turn = store.turn(viewer.scope(), id).orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-history"));
        if (!turn.conversationId().equals(conversation))
            throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "chat-history");
        return turn;
    }

    private record HistoryPair(long sequence, Turn turn, List<Message> messages, ResourceContextSnapshot resources) { }

    private boolean fits(List<Message> messages, int outputTokens) {
        return messages.size() <= 128 && ChatValues.bytes(messages) <= byteLimit
                && (estimator == null || (long) estimator.estimate(messages) + outputTokens + safetyTokens <= contextWindow);
    }
}
