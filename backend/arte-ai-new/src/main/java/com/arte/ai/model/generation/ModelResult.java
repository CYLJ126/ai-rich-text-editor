package com.arte.ai.model.generation;
import com.arte.ai.model.budget.Usage;
import com.arte.ai.model.message.ContentPart;
import com.arte.ai.model.tool.StructuredValue;
import com.arte.ai.model.tool.ToolCall;
import com.arte.base.validation.ContractChecks;
import java.util.List;

/**
 * 模型输出快照；缺失用量必须显式保持未知。
 */
public record ModelResult(List<ContentPart> output, List<ToolCall> toolCalls,
                          StructuredValue structuredOutput, Usage usage) {
    public ModelResult {
        output = List.copyOf(ContractChecks.required(output, "output"));
        toolCalls = List.copyOf(ContractChecks.required(toolCalls, "toolCalls"));
        usage = ContractChecks.required(usage, "usage");
    }
}
