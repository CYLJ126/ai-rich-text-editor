package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * 工作流 sealed DSL 与数据库 JSON 快照之间的显式稳定编码。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class WorkflowPersistenceCodec {

    private final ObjectMapper objectMapper;

    public WorkflowPersistenceCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> encodeNodes(List<WorkflowNode> nodes) {
        return nodes.stream().map(this::encodeNode).toList();
    }

    public List<WorkflowNode> decodeNodes(List<Map<String, Object>> values) {
        return values == null ? List.of() : values.stream().map(this::decodeNode).toList();
    }

    public List<Map<String, Object>> encodeEdges(List<WorkflowEdge> edges) {
        return edges.stream().map(edge -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("edgeId", edge.edgeId());
            value.put("sourceNodeId", edge.sourceNodeId());
            put(value, "sourceOutput", edge.sourceOutput());
            value.put("targetNodeId", edge.targetNodeId());
            put(value, "targetInput", edge.targetInput());
            put(value, "conditionExpression", edge.conditionExpression());
            return Map.copyOf(value);
        }).toList();
    }

    public List<WorkflowEdge> decodeEdges(List<Map<String, Object>> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> new WorkflowEdge(text(value, "edgeId"),
                text(value, "sourceNodeId"), optionalText(value, "sourceOutput"),
                text(value, "targetNodeId"), optionalText(value, "targetInput"),
                optionalText(value, "conditionExpression"))).toList();
    }

    public Map<String, Object> encodeSchema(ToolSchema schema) {
        return Map.of("dialect", schema.dialect(), "schema", schema.schema());
    }

    public ToolSchema decodeSchema(Map<String, Object> value) {
        return new ToolSchema(text(value, "dialect"), text(value, "schema"));
    }

    public Map<String, Object> encodePolicy(WorkflowExecutionPolicy policy) {
        return objectMapper.convertValue(policy, Map.class);
    }

    public WorkflowExecutionPolicy decodePolicy(Map<String, Object> value) {
        return value == null || value.isEmpty() ? WorkflowExecutionPolicy.defaults()
                : objectMapper.convertValue(value, WorkflowExecutionPolicy.class);
    }

    public Map<String, Object> encodePlan(CompiledWorkflow plan) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("dependencies", plan.dependencies());
        value.put("executionOrder", plan.executionOrder());
        value.put("parallelGroups", plan.parallelGroups());
        value.put("variableMappings", plan.variableMappings());
        return Map.copyOf(value);
    }

    public Map<String, Object> encodePinnedTools(Map<String, ToolReference> tools) {
        Map<String, Object> value = new LinkedHashMap<>();
        tools.forEach((nodeId, reference) -> value.put(nodeId, Map.of(
                "namespace", reference.namespace(), "name", reference.name(), "version", reference.version())));
        return Map.copyOf(value);
    }

    public CompiledWorkflow decodeCompiled(WorkflowDefinition source, WorkflowVersionPo version) {
        Map<String, Object> plan = objectMap(version.getCompiledPlan());
        if (plan.isEmpty() || version.getEntryNodeId() == null || version.getChecksum() == null) {
            throw new IllegalStateException("published workflow execution plan is missing");
        }
        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        source.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        objectMap(plan.get("dependencies")).forEach((nodeId, value) ->
                dependencies.put(nodeId, stringSet(value)));
        List<String> executionOrder = stringList(plan.get("executionOrder"));
        List<List<String>> parallelGroups = new ArrayList<>();
        if (plan.get("parallelGroups") instanceof Collection<?> groups) {
            groups.forEach(group -> parallelGroups.add(stringList(group)));
        }
        Map<String, Map<String, String>> variableMappings = new LinkedHashMap<>();
        objectMap(plan.get("variableMappings")).forEach((nodeId, value) ->
                variableMappings.put(nodeId, stringMap(value)));
        return new CompiledWorkflow(source, version.getEntryNodeId(), nodes, source.edges(),
                decodePinnedTools(version.getPinnedTools()), dependencies, executionOrder,
                parallelGroups, variableMappings, version.getChecksum());
    }

    private Map<String, ToolReference> decodePinnedTools(Map<String, Object> value) {
        Map<String, ToolReference> result = new LinkedHashMap<>();
        objectMap(value).forEach((nodeId, item) -> {
            Map<String, Object> reference = objectMap(item);
            result.put(nodeId, new ToolReference(text(reference, "namespace"),
                    text(reference, "name"), text(reference, "version")));
        });
        return result;
    }

    public String checksum(WorkflowDefinition definition) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(definition).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to calculate workflow checksum", exception);
        }
    }

    private Map<String, Object> encodeNode(WorkflowNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nodeId", node.nodeId());
        value.put("name", node.name());
        value.put("type", node.type().getValue());
        value.put("inputBindings", node.inputBindings());
        value.put("outputNames", node.outputNames());
        value.put("configuration", node.configuration());
        if (node instanceof WorkflowNode.ToolNode toolNode) {
            value.put("tool", Map.of("namespace", toolNode.tool().namespace(),
                    "name", toolNode.tool().name(), "version", toolNode.tool().version()));
            if (toolNode.executionPolicy() != null) {
                value.put("executionPolicy", objectMapper.convertValue(toolNode.executionPolicy(), Map.class));
            }
        } else if (node instanceof WorkflowNode.ConfiguredNode configured
                && configured.executionPolicy() != null) {
            value.put("executionPolicy", objectMapper.convertValue(configured.executionPolicy(), Map.class));
        }
        return Map.copyOf(value);
    }

    @SuppressWarnings("unchecked")
    private WorkflowNode decodeNode(Map<String, Object> value) {
        WorkflowNodeTypeEnum type = nodeType(text(value, "type"));
        String nodeId = text(value, "nodeId");
        String name = text(value, "name");
        Map<String, String> bindings = stringMap(value.get("inputBindings"));
        Set<String> outputs = stringSet(value.get("outputNames"));
        Map<String, Object> configuration = objectMap(value.get("configuration"));
        return switch (type) {
            case START -> new WorkflowNode.StartNode(nodeId, name, outputs, configuration);
            case END -> new WorkflowNode.EndNode(nodeId, name, bindings, configuration);
            case TOOL -> {
                Map<String, Object> tool = objectMap(value.get("tool"));
                ToolExecutionPolicy policy = value.get("executionPolicy") == null ? null
                        : objectMapper.convertValue(value.get("executionPolicy"), ToolExecutionPolicy.class);
                yield new WorkflowNode.ToolNode(nodeId, name,
                        new ToolReference(text(tool, "namespace"), text(tool, "name"), text(tool, "version")),
                        bindings, outputs, configuration, policy);
            }
            default -> new WorkflowNode.ConfiguredNode(nodeId, name, type, bindings, outputs,
                    configuration, value.get("executionPolicy") == null ? null
                    : objectMapper.convertValue(value.get("executionPolicy"), ToolExecutionPolicy.class));
        };
    }

    private WorkflowNodeTypeEnum nodeType(String value) {
        for (WorkflowNodeTypeEnum type : WorkflowNodeTypeEnum.values()) {
            if (type.name().equalsIgnoreCase(value) || type.getValue().equalsIgnoreCase(value)) return type;
        }
        throw new IllegalArgumentException("unknown workflow node type: " + value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        objectMap(value).forEach((key, item) -> result.put(key, String.valueOf(item)));
        return Map.copyOf(result);
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Collection<?> collection)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        collection.forEach(item -> result.add(String.valueOf(item)));
        return Set.copyOf(result);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) return List.of();
        return collection.stream().map(String::valueOf).toList();
    }

    private String text(Map<String, Object> value, String key) {
        String result = optionalText(value, key);
        if (result == null) throw new IllegalArgumentException(key + " is required");
        return result;
    }

    private String optionalText(Map<String, Object> value, String key) {
        Object result = value.get(key);
        return result == null || String.valueOf(result).isBlank() ? null : String.valueOf(result);
    }

    private void put(Map<String, Object> value, String key, Object item) {
        if (item != null) value.put(key, item);
    }
}
