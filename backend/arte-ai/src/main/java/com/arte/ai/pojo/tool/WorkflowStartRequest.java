package com.arte.ai.pojo.tool;

import java.util.Map;

/**
 * 启动已发布工作流版本的请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record WorkflowStartRequest(
        String workflowId,
        String version,
        Map<String, Object> inputs,
        Map<String, Object> variables,
        Integer maximumSteps
) {
    public WorkflowStartRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
