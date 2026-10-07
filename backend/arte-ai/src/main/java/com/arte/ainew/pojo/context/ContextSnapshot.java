package com.arte.ainew.pojo.context;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.ChatMessage;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 服务端实际输入快照
 * <p>
 * 固定本次调用的用户消息、历史消息、模型绑定、Token 容量约束、估算值及有效期。
 * messages 是最终规范化消息（包括已编入的资料），fragments 是来源映射，不再次拼入输入。
 * history.turnIds 必须记录实际选入轮次，不再含模糊的最近历史选择。计数与摘要由受信组装器计算并核对。
 * contentDigest 覆盖规范化输入，不等于供应商协议正文摘要。
 * expiresAt 不删除字节、不授予授权；过期禁止新调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:08 ✾
 */
public record ContextSnapshot(String snapshotId, ContextRequest.HistorySelection history,
                              DefinitionRef modelBinding, List<ChatMessage> messages, List<Fragment> fragments,
                              ContextBudget budget, long inputTokens, boolean estimatedTokens, String tokenizerVersion,
                              List<Truncation> truncations, String contentDigest, Instant createdAt,
                              Instant expiresAt) implements Serializable {
    public enum Origin {USER_SELECTED, HISTORY, MEMORY, RETRIEVAL}

    public enum TruncationReason {CAPACITY, SELECTION_LIMIT, UNSUPPORTED_CONTENT}

    public record Fragment(Origin origin, SourceRef source, String text,
                           long originalCharacters) implements Serializable {
        public Fragment {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(source, "source");
            ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
            ContractChecks.range(originalCharacters, "originalCharacters", text.length(), Long.MAX_VALUE);
        }

        public boolean truncated() {
            return originalCharacters > text.length();
        }
    }

    /**
     * 包括完全未选入的资料；selectionId 为安全标识，不记录未授权全文。
     */
    public record Truncation(String selectionId, TruncationReason reason,
                             long omittedCharacters) implements Serializable {
        public Truncation {
            ContractChecks.id(selectionId, "selectionId");
            Objects.requireNonNull(reason, "reason");
            ContractChecks.range(omittedCharacters, "omittedCharacters", 1, Long.MAX_VALUE);
        }
    }

    public ContextSnapshot {
        ContractChecks.id(snapshotId, "snapshotId");
        if (history != null) {
            ContractChecks.require(!history.turnIds().isEmpty(), "Snapshot history must list actual turns; use null for none");
        }
        Objects.requireNonNull(modelBinding, "modelBinding").requireType("binding");
        messages = ContractChecks.list(messages, "messages", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(messages.stream().map(ChatMessage::messageId).toList(), "message IDs");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Snapshot messages exceed character limit");
        fragments = ContractChecks.list(fragments, "fragments", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(fragments.stream().map(fragment -> fragment.source().citationId()).toList(), "citation IDs");
        ContractChecks.require(fragments.stream().mapToLong(fragment -> fragment.text().length()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Snapshot fragments exceed character limit");
        Objects.requireNonNull(budget, "budget");
        ContractChecks.range(inputTokens, "inputTokens", 0, budget.maxInputTokens());
        ContractChecks.id(tokenizerVersion, "tokenizerVersion");
        truncations = ContractChecks.list(truncations, "truncations", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(truncations.stream().map(Truncation::selectionId).toList(), "truncation IDs");
        for (Fragment fragment : fragments) {
            if (fragment.truncated()) {
                final var explanations = truncations;
                ContractChecks.require(explanations.stream().anyMatch(truncation ->
                                truncation.selectionId().equals(fragment.source().citationId())
                                        && truncation.omittedCharacters() == fragment.originalCharacters() - fragment.text().length()),
                        "Truncated fragment requires matching coverage explanation");
            }
        }
        ContractChecks.digest(contentDigest, "contentDigest");
        ContractChecks.require(Objects.requireNonNull(expiresAt, "expiresAt")
                .isAfter(Objects.requireNonNull(createdAt, "createdAt")), "Snapshot expiry must follow creation");
    }
}
