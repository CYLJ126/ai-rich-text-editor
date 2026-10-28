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
 *
 * @param snapshotId       本次实际输入快照的唯一标识，用于受理记录关联、持久化及后续核对。
 * @param history          实际选入的历史来源，未选入历史时为空；非空时须固定会话版本并记录实际选入的非空轮次 ID 列表。
 * @param modelBinding     本次上下文对应的固定模型绑定引用，不能为空且类型必须为 binding，用于核对模型配置及上下文容量。
 * @param messages         最终规范化的有序输入消息，至少一条且消息 ID 不重复；已包含实际编入的历史和资料内容，不应再重复拼接 fragments。
 * @param fragments        实际选入资料的内容与来源映射列表，来源 citationId 不重复；无资料片段时为空列表，当前文本组装链路仅生成空列表。
 * @param budget           本次输入适用的 Token 容量约束，不能为空，包含模型窗口、输入上限及输出和工具预留，不是费用预算。
 * @param inputTokens      最终输入消息的 Token 计数，范围为 0 至 budget.maxInputTokens；由可信组装器计算并核对，是否为估算值由 estimatedTokens 标明。
 * @param estimatedTokens  inputTokens 是否为估算值；为 true 时不能将该数值当作供应商实际计费用量。
 * @param tokenizerVersion 本次 Token 计数器或估算规则的版本标识，不能为空，用于核对计数依据及结果一致性。
 * @param truncations      本次资料裁剪或完全省略的说明列表，selectionId 不重复；被裁剪片段须有标识及省略字符数相匹配的说明，当前文本组装链路仅生成空列表。
 * @param contentDigest    最终规范化输入的 SHA-256 摘要，格式为 64 位小写十六进制，用于内容一致性核验，不等同于供应商协议请求正文的摘要。
 * @param createdAt        本快照的创建时间，不能为空，由服务端记录。
 * @param expiresAt        本快照作为新调用输入的有效期截止时间，不能为空且必须晚于 createdAt；过期禁止新调用；到期不代表自动删除持久化内容，也不代替当前授权检查。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:08 ✾
 */
public record ContextSnapshot(String snapshotId, ContextRequest.HistorySelection history,
                              DefinitionRef modelBinding, List<ChatMessage> messages, List<Fragment> fragments,
                              ContextBudget budget, long inputTokens, boolean estimatedTokens, String tokenizerVersion,
                              List<Truncation> truncations, String contentDigest, Instant createdAt,
                              Instant expiresAt) implements Serializable {

    /**
     * 实际上下文片段的来源类别，用于来源追踪，不表示已获读取或外发权限。
     */
    public enum Origin {
        /**
         * 用户显式选入的资料。
         */
        USER_SELECTED,
        /**
         * 从所选会话历史中取得的内容。
         */
        HISTORY,
        /**
         * 从显式选择的记忆中取得的内容。
         */
        MEMORY,
        /**
         * 通过检索取得并实际选入的内容。
         */
        RETRIEVAL
    }

    /**
     * 资料被裁剪或完全省略的原因，用于解释实际输入与原选择之间的差异。
     */
    public enum TruncationReason {
        /**
         * 受上下文容量约束，无法完整选入。
         */
        CAPACITY,
        /**
         * 受本次资料选择数量或范围上限约束，未完整选入。
         */
        SELECTION_LIMIT,
        /**
         * 内容类型或格式不受当前处理链路支持，未选入。
         */
        UNSUPPORTED_CONTENT
    }

    /**
     * 实际选入资料的文本片段及来源映射
     * <p>
     * 文本已编入 messages，不再次作为输入拼接。
     *
     * @param origin             片段的来源类别，不能为空，区分用户选入、历史、记忆及检索来源。
     * @param source             片段的来源引用，不能为空，关联 citationId、固定资源版本或草稿及实际摘录摘要；内容与来源的一致性由可信组装器核对。
     * @param text               实际选入的非空白文本，长度不超过 ContractChecks.MAX_TEXT_CHARS；计数使用 String.length() 的 UTF-16 单元，不是 Token 数。
     * @param originalCharacters 裁剪前文本的字符计数，按 UTF-16 单元计算，不得小于 text.length()；大于时表示已裁剪，差值须由对应 Truncation 记录。
     */
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
     * 本次资料裁剪或完全省略的说明列表
     * <p>
     * 包括完全未选入的资料；selectionId 为安全标识，不记录未授权全文。
     *
     * @param selectionId       被裁剪或省略资料的安全选择标识，在本快照的裁剪列表内唯一；对应已选入片段时须与其 source.citationId 一致。
     * @param reason            本次裁剪或省略的原因，不能为空，用于解释容量、选择上限或内容兼容性限制。
     * @param omittedCharacters 本次省略的字符计数，按 UTF-16 单元计算，至少为 1；对应已裁剪片段时须等于 originalCharacters 减去 text.length()，不是省略的 Token 数。
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
