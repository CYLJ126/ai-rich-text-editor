package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.workflow.WorkflowCompiler;
import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.api.tool.workflow.WorkflowValidator;
import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.pojo.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 将合法 DAG 编译为固定版本、可并行调度的不可变执行计划。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class DefaultWorkflowCompiler implements WorkflowCompiler {

    private final WorkflowValidator validator;
    private final ObjectMapper objectMapper;

    public DefaultWorkflowCompiler(WorkflowValidator validator, ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Override
    public CompiledWorkflow compile(WorkflowDefinition definition) {
        WorkflowValidationResult validation = validator.validate(definition);
        if (!validation.valid()) {
            throw new IllegalArgumentException("workflow validation failed: " + validation.issues());
        }
        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        definition.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        String entry = definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeTypeEnum.START)
                .map(WorkflowNode::nodeId).findFirst().orElseThrow();

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> dependencies.put(id, new LinkedHashSet<>()));
        Map<String, List<String>> outgoing = new HashMap<>();
        definition.edges().forEach(edge -> {
            dependencies.get(edge.targetNodeId()).add(edge.sourceNodeId());
            outgoing.computeIfAbsent(edge.sourceNodeId(), ignored -> new ArrayList<>())
                    .add(edge.targetNodeId());
        });
        List<List<String>> groups = topologicalGroups(nodes.keySet(), dependencies, outgoing);
        List<String> order = groups.stream().flatMap(Collection::stream).toList();
        Map<String, ToolReference> pinned = new LinkedHashMap<>();
        Map<String, Map<String, String>> mappings = new LinkedHashMap<>();
        definition.nodes().forEach(node -> {
            mappings.put(node.nodeId(), new LinkedHashMap<>(node.inputBindings()));
            if (node instanceof WorkflowNode.ToolNode toolNode) {
                pinned.put(node.nodeId(), toolNode.tool());
            }
        });
        definition.edges().stream()
                .filter(edge -> edge.targetInput() != null && !edge.targetInput().isBlank())
                .forEach(edge -> mappings.get(edge.targetNodeId()).putIfAbsent(edge.targetInput(),
                        edgeExpression(edge, nodes.get(edge.sourceNodeId()))));
        Map<String, Map<String, String>> immutableMappings = new LinkedHashMap<>();
        mappings.forEach((nodeId, value) -> immutableMappings.put(nodeId, Map.copyOf(value)));
        return new CompiledWorkflow(definition, entry, nodes, definition.edges(), pinned,
                dependencies, order, groups, immutableMappings, checksum(definition));
    }

    private String edgeExpression(WorkflowEdge edge, WorkflowNode source) {
        String output = edge.sourceOutput();
        if (output == null || output.isBlank()) {
            output = source.outputNames().size() == 1
                    ? source.outputNames().iterator().next() : edge.targetInput();
        }
        return source.type() == WorkflowNodeTypeEnum.START
                ? "${inputs." + output + "}" : "${" + source.nodeId() + "." + output + "}";
    }

    private List<List<String>> topologicalGroups(Set<String> nodeIds,
                                                 Map<String, Set<String>> dependencies,
                                                 Map<String, List<String>> outgoing) {
        Map<String, Integer> degree = new LinkedHashMap<>();
        nodeIds.forEach(id -> degree.put(id, dependencies.get(id).size()));
        List<String> ready = degree.entrySet().stream().filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey).sorted().toList();
        List<List<String>> groups = new ArrayList<>();
        int processed = 0;
        while (!ready.isEmpty()) {
            groups.add(ready);
            processed += ready.size();
            List<String> next = new ArrayList<>();
            for (String source : ready) {
                for (String target : outgoing.getOrDefault(source, List.of())) {
                    int value = degree.compute(target, (ignored, current) -> current - 1);
                    if (value == 0) next.add(target);
                }
            }
            ready = next.stream().distinct().sorted().toList();
        }
        if (processed != nodeIds.size()) {
            throw new IllegalArgumentException("workflow contains a cycle");
        }
        return List.copyOf(groups);
    }

    private String checksum(WorkflowDefinition definition) {
        try {
            String json = objectMapper.writeValueAsString(definition);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to calculate workflow checksum", exception);
        }
    }
}
