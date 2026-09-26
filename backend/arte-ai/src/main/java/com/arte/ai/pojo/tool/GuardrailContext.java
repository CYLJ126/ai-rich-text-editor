package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.api.tool.ToolResponse;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;

import java.util.Map;
import java.util.Objects;

/**
 * Guardrail 判定所需的不可变上下文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record GuardrailContext(
        GuardrailPhaseEnum phase,
        ToolDefinition definition,
        ToolInvocation<? extends ToolRequest> invocation,
        ToolResult<? extends ToolResponse> result,
        Map<String, Object> attributes
) {

    public GuardrailContext {
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(invocation, "invocation must not be null");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
