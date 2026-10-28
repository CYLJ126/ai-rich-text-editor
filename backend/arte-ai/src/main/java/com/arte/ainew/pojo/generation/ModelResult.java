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
 * @param resultId         本次模型输出的逻辑结果标识，不能为空，用于关联和核对结果；与持久化存储分配的 ResultRef.resultId 分别表达逻辑结果与存储定位。
 * @param model            本次实际返回结果的模型身份，不能为空，包含供应商标识、模型标识及可选的模型修订信息，不以请求中的显示名称代替实际模型事实。
 * @param outputs          模型返回的有序助手消息列表，保存为不可变列表，消息角色必须为 ASSISTANT，消息 ID 及跨消息的工具 callId 不重复；可包含文本或工具调用提议。
 * @param finishReason     模型停止输出的原因，不能为空，区分正常结束、工具调用、长度限制、内容过滤及其他情况；TOOL_CALLS 时 outputs 必须包含工具调用提议。
 * @param complete         模型输出是否完整；仅 STOP 或 TOOL_CALLS 可为 true，且 outputs 或 structuredOutput 至少一项有内容；不表示工具已执行、调用已成功结算或业务已保存。
 * @param structuredOutput 可选的结构化生成内容，未提供时为空；仍须按请求指定的 Schema 及领域规则校验，与 outputs 共同受输出内容大小约束。
 * @param usage            本次模型执行的用量记录，不能为空，可明确标记未知、估算或供应商报告；单项为空表示未知，费用由预算账本另行核对。
 * @param sources          本次结果关联的来源引用列表，保存为不可变列表，citationId 不重复，无来源时使用空列表；须与可信输入来源核对，展示及再次使用仍需重新授权。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:18 ✾
 */
public record ModelResult(String resultId, ModelIdentity model, List<ChatMessage> outputs,
                          FinishReason finishReason, boolean complete, StructuredValue structuredOutput,
                          Usage usage, List<SourceRef> sources) implements Serializable {

    public enum FinishReason {STOP, TOOL_CALLS, LENGTH, CONTENT_FILTER, OTHER}

    /**
     * 本次生成结果对应的模型身份，用于记录供应商、模型及可获知的修订信息。
     * revision 允许未知；发布版本不保证供应商后台模型行为固定。
     *
     * @param providerId 提供本次模型调用的供应商标识，不能为空白且不超过 256 个 UTF-16 单元；不是连接地址或凭据标识。
     * @param modelId    本次结果对应的模型标识，不能为空白且不超过 256 个 UTF-16 单元；通常来自供应商响应，部分结果保留场景使用已解析绑定的远端模型名，不是平台 binding ID。
     * @param revision   可选的模型修订或版本信息，未知时为空，非空时必须为非空白且不超过 256 个 UTF-16 单元的标识；不等同于平台能力、绑定或发布配置的版本。
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
