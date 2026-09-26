package com.arte.ai.pojo.tool;

import java.util.Objects;
import java.util.Set;

/**
 * 不可变、可版本化的工具定义。
 * <p>
 * 该定义是领域模型，不直接依赖 Spring AI、MCP 或具体模型 SDK。
 * 基础设施适配器负责将其转换为对应框架的工具定义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolDefinition(
        ToolReference reference,
        String title,
        String description,
        ToolSchema inputSchema,
        ToolSchema outputSchema,
        ToolCapabilities capabilities,
        ToolRiskProfile riskProfile,
        ToolExecutionPolicy defaultPolicy,
        Set<String> tags,
        boolean deprecated
) {

    public ToolDefinition {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(outputSchema, "outputSchema");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(riskProfile, "riskProfile");
        Objects.requireNonNull(defaultPolicy, "defaultPolicy");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (!capabilities.executionModes().contains(defaultPolicy.executionMode())) {
            throw new IllegalArgumentException("default execution mode is not supported by the tool");
        }
    }
}
