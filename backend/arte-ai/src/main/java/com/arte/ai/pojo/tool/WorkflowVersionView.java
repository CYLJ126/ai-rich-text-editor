package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流不可变版本及页面 DSL 视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowVersionView(String workflowId, String version, String name,
                                  String description, Set<String> tags,
                                  Map<String, Object> inputSchema,
                                  Map<String, Object> outputSchema,
                                  Map<String, Object> executionPolicy,
                                  List<Map<String, Object>> nodes,
                                  List<Map<String, Object>> edges,
                                  Map<String, Object> compiledPlan,
                                  Map<String, Object> pinnedTools,
                                  String entryNodeId, String checksum,
                                  String lifecycleState, Instant publishedAt,
                                  long rowVersion, Instant createTime, Instant updateTime) {
    public WorkflowVersionView {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        outputSchema = outputSchema == null ? Map.of() : Map.copyOf(outputSchema);
        executionPolicy = executionPolicy == null ? Map.of() : Map.copyOf(executionPolicy);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        compiledPlan = compiledPlan == null ? Map.of() : Map.copyOf(compiledPlan);
        pinnedTools = pinnedTools == null ? Map.of() : Map.copyOf(pinnedTools);
    }
}
