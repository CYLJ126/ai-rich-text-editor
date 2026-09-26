package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolRequest;
import com.arte.ai.api.tool.ToolResponse;
import com.arte.ai.api.tool.ToolResult;

import java.util.Map;
import java.util.Objects;

/**
 * 同时包含调用轨迹和环境最终结果的不可变评估输入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolEvaluationContext(
        ToolDefinition definition,
        ToolInvocation<? extends ToolRequest> invocation,
        ToolResult<? extends ToolResponse> result,
        ToolExecutionTrace trace,
        Object expectedOutcome,
        Object actualOutcome,
        Map<String, Object> criteria,
        Map<String, Object> attributes
) {

    public ToolEvaluationContext {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(invocation, "invocation must not be null");
        Objects.requireNonNull(result, "result must not be null");
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
