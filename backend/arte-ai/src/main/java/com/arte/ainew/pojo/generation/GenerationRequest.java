package com.arte.ainew.pojo.generation;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.CapabilityInput;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 类型化生成输入
 * <p>
 * 固定实际消息、生成选项、允许工具描述及输出格式；工具描述不携带可执行回调。
 * 发布配置及能力校验决定可用模态／特性，不默默忽略参数；消息角色、来源与工具响应须由可信组装器核对。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:18 ✾
 */
public record GenerationRequest(List<ChatMessage> messages, GenerationOptions options,
                                 List<ToolDefinition> tools, OutputFormat outputFormat) implements CapabilityInput {
    public sealed interface OutputFormat extends Serializable permits TextOutput, StructuredOutput { }
    public record TextOutput() implements OutputFormat { }
    public record StructuredOutput(DefinitionRef schema) implements OutputFormat {
        public StructuredOutput { Objects.requireNonNull(schema, "schema").requireType("schema"); }
    }
    public record ToolDefinition(DefinitionRef tool, String description, DefinitionRef inputSchema,
                                 CapabilityDescriptor.SideEffect sideEffect) implements Serializable {
        public ToolDefinition {
            Objects.requireNonNull(tool, "tool").requireType("tool");
            ContractChecks.text(description, "description", 4096);
            Objects.requireNonNull(inputSchema, "inputSchema").requireType("schema");
            Objects.requireNonNull(sideEffect, "sideEffect");
        }
    }

    public GenerationRequest {
        messages = ContractChecks.list(messages, "messages", 1, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(messages.stream().map(ChatMessage::messageId).toList(), "message IDs");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Generation input exceeds character limit");
        Objects.requireNonNull(options, "options");
        tools = ContractChecks.list(tools, "tools", 0, ContractChecks.MAX_TOOLS);
        ContractChecks.unique(tools.stream().map(ToolDefinition::tool).toList(), "tools");
        ContractChecks.unique(tools.stream().map(tool -> tool.tool().id()).toList(), "tool names");
        ContractChecks.require(messages.stream().mapToLong(ChatMessage::characterCount).sum()
                + tools.stream().mapToLong(tool -> tool.description().length()).sum() <= ContractChecks.MAX_TEXT_CHARS,
                "Messages and tool descriptions exceed input character limit");
        Objects.requireNonNull(outputFormat, "outputFormat");
    }
    @Override public CapabilityDescriptor.Kind kind() { return CapabilityDescriptor.Kind.GENERATION; }
}
