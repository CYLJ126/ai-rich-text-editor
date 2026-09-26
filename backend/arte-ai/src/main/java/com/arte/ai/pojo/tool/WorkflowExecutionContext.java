package com.arte.ai.pojo.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工作流程执行上下文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowExecutionContext(
        ToolExecutionContext toolContext,
        Map<String, Object> inputs,
        Map<String, Object> variables,
        int maximumSteps
) {

    public WorkflowExecutionContext {
        Objects.requireNonNull(toolContext, "toolContext must not be null");
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        if (maximumSteps <= 0) {
            throw new IllegalArgumentException("maximumSteps must be positive");
        }
    }
}
