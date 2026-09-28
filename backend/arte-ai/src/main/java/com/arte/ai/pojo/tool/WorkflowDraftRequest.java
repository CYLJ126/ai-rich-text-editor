package com.arte.ai.pojo.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 页面保存的工作流 DSL 请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record WorkflowDraftRequest(
        String workflowId,
        String version,
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        Set<String> tags,
        Map<String, Object> executionPolicy,
        Long expectedRowVersion
) {
    public WorkflowDraftRequest {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
