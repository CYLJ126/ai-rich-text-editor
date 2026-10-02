package com.arte.ai.model.generation;
import com.arte.ai.model.capability.CapabilityDescriptor;
import com.arte.ai.model.message.Message;
import com.arte.base.model.schema.SchemaRef;
import com.arte.base.validation.ContractChecks;
import java.util.List;

/**
 * 类型化生成输入；集合固定，工具与多模态支持由网关明确声明。
 */
public record GenerationRequest(List<Message> messages, ModelOptions options,
                                List<CapabilityDescriptor> tools, SchemaRef outputSchema) {
    public GenerationRequest {
        messages = List.copyOf(ContractChecks.required(messages, "messages"));
        options = ContractChecks.required(options, "options");
        tools = List.copyOf(ContractChecks.required(tools, "tools"));
        if (messages.isEmpty()) throw new IllegalArgumentException("messages must not be empty");
    }
}
