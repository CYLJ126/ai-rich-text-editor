package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 模型最终输出快照
 * <p>
 * 工具调用仍是提议，不表示业务已执行。complete 仅描述模型输出完整性，不代替 Schema 校验、Invocation 终态或领域保存。来源由输入引用映射后核对，模型不能自授来源权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:18 ✾
 */
public record ModelResult(String resultId, ModelIdentity model, List<ChatMessage> outputs,
                          FinishReason finishReason, boolean complete, StructuredValue structuredOutput,
                          Usage usage, List<SourceRef> sources) implements Serializable {
    public enum FinishReason {STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, OTHER}

    /**
     * revision 允许未知；发布版本不保证供应商后台模型行为固定。
     */
    public record ModelIdentity(String providerId, String modelId, String revision) implements Serializable {
        public ModelIdentity {
            ContractChecks.id(providerId, "providerId");
            ContractChecks.id(modelId, "modelId");
            ContractChecks.optionalId(revision, "revision");
        }
    }

    public ModelResult {
        ContractChecks.id(resultId, "resultId");
        Objects.requireNonNull(model, "model");
        outputs = ContractChecks.list(outputs, "outputs", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(outputs.stream().map(ChatMessage::messageId).toList(), "output IDs");
        ContractChecks.require(outputs.stream().allMatch(message -> message.role() == ChatMessage.Role.ASSISTANT),
                "Model outputs must be assistant messages");
        ContractChecks.require(outputs.stream().mapToLong(ChatMessage::characterCount).sum()
                        + (structuredOutput == null ? 0 : structuredOutput.characterCount()) <= ContractChecks.MAX_TEXT_CHARS,
                "Model output exceeds character limit");
        ContractChecks.unique(outputs.stream().flatMap(message -> message.toolCalls().stream())
                .map(ToolCall::callId).toList(), "output tool call IDs");
        Objects.requireNonNull(finishReason, "finishReason");
        ContractChecks.require(!complete || finishReason == FinishReason.STOP || finishReason == FinishReason.TOOL_CALLS,
                "Truncated, filtered or unrecognized finish cannot be complete");
        ContractChecks.require(!complete || !outputs.isEmpty() || structuredOutput != null, "Complete output cannot be empty");
        ContractChecks.require(finishReason != FinishReason.TOOL_CALLS || outputs.stream().anyMatch(message -> !message.toolCalls().isEmpty()),
                "Tool-call finish requires tool proposals");
        Objects.requireNonNull(usage, "usage");
        sources = ContractChecks.list(sources, "sources", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(sources.stream().map(SourceRef::citationId).toList(), "source IDs");
    }
}
