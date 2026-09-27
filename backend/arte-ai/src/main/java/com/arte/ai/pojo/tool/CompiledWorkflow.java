package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.workflow.WorkflowNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 已校验并固定依赖版本的不可变可执行计划。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record CompiledWorkflow(
        WorkflowDefinition source,
        String entryNodeId,
        Map<String, WorkflowNode> nodes,
        List<WorkflowEdge> edges,
        Map<String, ToolReference> pinnedTools,
        Map<String, Set<String>> dependencies,
        List<String> executionOrder,
        List<List<String>> parallelGroups,
        Map<String, Map<String, String>> variableMappings,
        String checksum
) {

    public CompiledWorkflow(WorkflowDefinition source, String entryNodeId,
                            Map<String, WorkflowNode> nodes, List<WorkflowEdge> edges,
                            Map<String, ToolReference> pinnedTools, String checksum) {
        this(source, entryNodeId, nodes, edges, pinnedTools, Map.of(), List.of(), List.of(), Map.of(), checksum);
    }

    public CompiledWorkflow {
        Objects.requireNonNull(source, "source must not be null");
        requireText(entryNodeId, "entryNodeId");
        nodes = Map.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        edges = edges == null ? List.of() : List.copyOf(edges);
        pinnedTools = pinnedTools == null ? Map.of() : Map.copyOf(pinnedTools);
        dependencies = dependencies == null ? Map.of() : dependencies.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
        executionOrder = executionOrder == null ? List.of() : List.copyOf(executionOrder);
        parallelGroups = parallelGroups == null ? List.of() : parallelGroups.stream().map(List::copyOf).toList();
        variableMappings = variableMappings == null ? Map.of() : variableMappings.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Map.copyOf(entry.getValue())));
        requireText(checksum, "checksum");
        if (!nodes.containsKey(entryNodeId)) {
            throw new IllegalArgumentException("entryNodeId must exist in nodes");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
