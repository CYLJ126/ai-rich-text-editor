package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.workflow.WorkflowNode;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 用户可在页面上编排的、可版本化工作流程 DSL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record WorkflowDefinition(
        String workflowId,
        String version,
        String name,
        String description,
        ToolSchema inputSchema,
        ToolSchema outputSchema,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        Set<String> tags
) {

    public WorkflowDefinition {
        requireText(workflowId, "workflowId");
        requireText(version, "version");
        requireText(name, "name");
        Objects.requireNonNull(inputSchema, "inputSchema must not be null");
        Objects.requireNonNull(outputSchema, "outputSchema must not be null");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        edges = edges == null ? List.of() : List.copyOf(edges);
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
