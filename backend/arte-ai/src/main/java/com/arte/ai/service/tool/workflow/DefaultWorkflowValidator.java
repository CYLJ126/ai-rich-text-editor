package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.ToolBindingManager;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.api.tool.workflow.WorkflowValidator;
import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.pojo.tool.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;

/**
 * 页面工作流 DSL 的发布前静态校验器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class DefaultWorkflowValidator implements WorkflowValidator {

    private static final Set<WorkflowNodeTypeEnum> PHASE_ONE_TYPES = Set.of(
            WorkflowNodeTypeEnum.START, WorkflowNodeTypeEnum.END, WorkflowNodeTypeEnum.TOOL,
            WorkflowNodeTypeEnum.ROUTER, WorkflowNodeTypeEnum.PARALLEL, WorkflowNodeTypeEnum.JOIN);
    private static final int SERVER_MAX_STEPS = 10_000;
    private static final int SERVER_MAX_PARALLELISM = 64;
    private static final Duration SERVER_MAX_TIMEOUT = Duration.ofHours(24);

    private final ToolRegistry toolRegistry;
    private final ToolBindingManager bindingManager;
    private final ToolExecutionProperties executionProperties;
    private final ObjectMapper objectMapper;

    public DefaultWorkflowValidator(ToolRegistry toolRegistry, ToolBindingManager bindingManager,
                                    ToolExecutionProperties executionProperties,
                                    ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.bindingManager = bindingManager;
        this.executionProperties = executionProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public WorkflowValidationResult validate(WorkflowDefinition definition) {
        return validate(definition, null);
    }

    @Override
    public WorkflowValidationResult validate(WorkflowDefinition definition, ToolPrincipal principal) {
        List<WorkflowValidationResult.Issue> issues = new ArrayList<>();
        if (definition == null) {
            error(issues, "WORKFLOW_REQUIRED", null, "workflow definition is required");
            return new WorkflowValidationResult(issues);
        }
        validateSchemas(definition, issues);
        validateBudget(definition.executionPolicy(), issues);

        Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (nodes.putIfAbsent(node.nodeId(), node) != null) {
                error(issues, "DUPLICATE_NODE", node.nodeId(), "duplicate node id: " + node.nodeId());
            }
            if (!PHASE_ONE_TYPES.contains(node.type())) {
                error(issues, "UNSUPPORTED_NODE_TYPE", node.nodeId(),
                        "node type is not supported in phase one: " + node.type());
            }
        }
        List<WorkflowNode> starts = definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeTypeEnum.START).toList();
        List<WorkflowNode> ends = definition.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeTypeEnum.END).toList();
        if (starts.size() != 1) {
            error(issues, "START_NODE_COUNT", null, "workflow must contain exactly one START node");
        }
        if (ends.isEmpty()) {
            error(issues, "END_NODE_REQUIRED", null, "workflow must contain at least one END node");
        }
        if (definition.executionPolicy().maximumSteps() < definition.nodes().size()) {
            error(issues, "STEP_BUDGET_TOO_SMALL", null,
                    "maximumSteps must be at least the number of nodes");
        }

        Map<String, List<WorkflowEdge>> outgoing = new HashMap<>();
        Map<String, List<WorkflowEdge>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        Set<String> targetPorts = new HashSet<>();
        for (WorkflowEdge edge : definition.edges()) {
            if (!edgeIds.add(edge.edgeId())) {
                error(issues, "DUPLICATE_EDGE", null, "duplicate edge id: " + edge.edgeId());
            }
            WorkflowNode source = nodes.get(edge.sourceNodeId());
            WorkflowNode target = nodes.get(edge.targetNodeId());
            if (source == null || target == null) {
                error(issues, "ILLEGAL_EDGE", null, "edge references a missing node: " + edge.edgeId());
                continue;
            }
            if (source.type() == WorkflowNodeTypeEnum.END) {
                error(issues, "END_HAS_OUTGOING", source.nodeId(), "END nodes cannot have outgoing edges");
            }
            if (target.type() == WorkflowNodeTypeEnum.START) {
                error(issues, "START_HAS_INCOMING", target.nodeId(), "START nodes cannot have incoming edges");
            }
            if (edge.sourceOutput() != null && !edge.sourceOutput().isBlank()
                    && !source.outputNames().contains(edge.sourceOutput())) {
                error(issues, "UNKNOWN_SOURCE_OUTPUT", source.nodeId(),
                        "edge references undeclared output: " + edge.sourceOutput());
            }
            if (edge.targetInput() != null && !edge.targetInput().isBlank()) {
                String port = edge.targetNodeId() + ":" + edge.targetInput();
                if (!targetPorts.add(port)) {
                    error(issues, "DUPLICATE_TARGET_INPUT", target.nodeId(),
                            "multiple edges target input: " + edge.targetInput());
                }
                if (target.inputBindings().containsKey(edge.targetInput())) {
                    error(issues, "AMBIGUOUS_TARGET_INPUT", target.nodeId(),
                            "input is bound by both node configuration and an edge: " + edge.targetInput());
                }
            }
            outgoing.computeIfAbsent(source.nodeId(), ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(target.nodeId(), ignored -> new ArrayList<>()).add(edge);
        }

        if (starts.size() == 1) {
            Set<String> reachable = reachable(starts.getFirst().nodeId(), outgoing);
            nodes.keySet().stream().filter(nodeId -> !reachable.contains(nodeId)).forEach(nodeId ->
                    error(issues, "UNREACHABLE_NODE", nodeId, "node is unreachable from START"));
        }
        detectCycle(nodes.keySet(), outgoing, incoming, issues);
        validateBindings(definition, nodes, incoming, issues);
        validateTools(definition, principal, issues);
        return new WorkflowValidationResult(issues);
    }

    private void validateTools(WorkflowDefinition definition, ToolPrincipal principal,
                               List<WorkflowValidationResult.Issue> issues) {
        for (WorkflowNode node : definition.nodes()) {
            if (!(node instanceof WorkflowNode.ToolNode toolNode)) continue;
            var resolved = toolRegistry.resolve(toolNode.tool());
            if (resolved.isEmpty()) {
                error(issues, "TOOL_VERSION_UNAVAILABLE", node.nodeId(),
                        "published tool version is unavailable: " + toolNode.tool());
                continue;
            }
            ToolDefinition tool = resolved.get().getDefinition();
            validateToolBinding(toolNode, principal, issues);
            Set<String> declaredInputs = schemaProperties(tool.inputSchema());
            Set<String> requiredInputs = schemaRequired(tool.inputSchema());
            Set<String> effectiveInputs = effectiveBindings(toolNode, definition.edges(), definition.nodes())
                    .keySet();
            if (!effectiveInputs.containsAll(requiredInputs)) {
                Set<String> missing = new LinkedHashSet<>(requiredInputs);
                missing.removeAll(effectiveInputs);
                error(issues, "TOOL_REQUIRED_INPUT_MISSING", node.nodeId(),
                        "required tool inputs are not bound: " + missing);
            }
            if (!declaredInputs.isEmpty()) {
                effectiveInputs.stream().filter(input -> !declaredInputs.contains(input)).forEach(input ->
                        error(issues, "TOOL_INPUT_UNDECLARED", node.nodeId(),
                                "tool input is not declared by its schema: " + input));
            }
            Set<String> declaredOutputs = schemaProperties(tool.outputSchema());
            if (!declaredOutputs.isEmpty()) {
                toolNode.outputNames().stream().filter(output -> !declaredOutputs.contains(output))
                        .forEach(output -> error(issues, "TOOL_OUTPUT_UNDECLARED", node.nodeId(),
                                "tool output is not declared by its schema: " + output));
            }
            if (toolNode.executionPolicy() != null
                    && !tool.capabilities().executionModes()
                    .contains(toolNode.executionPolicy().executionMode())) {
                error(issues, "TOOL_MODE_UNSUPPORTED", node.nodeId(),
                        "tool does not support configured execution mode");
            }
            if (principal != null && !principal.scopes().containsAll(tool.riskProfile().requiredScopes())) {
                error(issues, "TOOL_PERMISSION_DENIED", node.nodeId(),
                        "workflow owner lacks required tool scopes");
            }
        }
    }

    private void validateToolBinding(WorkflowNode.ToolNode toolNode, ToolPrincipal principal,
                                     List<WorkflowValidationResult.Issue> issues) {
        if (principal == null) return;
        String bindingId = configurationText(toolNode.configuration().get("bindingId"));
        if (bindingId == null) {
            if (executionProperties.isBindingRequired()) {
                error(issues, "TOOL_BINDING_REQUIRED", toolNode.nodeId(),
                        "a user/workspace tool binding is required");
            }
            return;
        }
        String workspaceId = configurationText(toolNode.configuration().get("workspaceId"));
        Optional<ResolvedToolBinding> binding;
        try {
            binding = bindingManager.resolve(principal.ownerId(), workspaceId, bindingId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            binding = Optional.empty();
        }
        if (binding.isEmpty()) {
            error(issues, "TOOL_BINDING_UNAVAILABLE", toolNode.nodeId(),
                    "tool binding is unavailable for the workflow owner and workspace");
        } else if (!binding.get().tool().equals(toolNode.tool())) {
            error(issues, "TOOL_BINDING_TOOL_MISMATCH", toolNode.nodeId(),
                    "tool binding does not grant the configured tool version");
        }
    }

    private void validateBindings(WorkflowDefinition definition, Map<String, WorkflowNode> nodes,
                                  Map<String, List<WorkflowEdge>> incoming,
                                  List<WorkflowValidationResult.Issue> issues) {
        Set<String> inputProperties = schemaProperties(definition.inputSchema());
        for (WorkflowNode node : definition.nodes()) {
            Set<String> ancestors = ancestors(node.nodeId(), incoming);
            effectiveBindings(node, definition.edges(), definition.nodes()).forEach((input, expression) -> {
                if (!validExpressionSyntax(expression)) {
                    error(issues, "INVALID_INPUT_BINDING", node.nodeId(),
                            "binding has a malformed variable expression: " + expression);
                    return;
                }
                String normalized = normalizeExpression(expression);
                if (normalized.startsWith("inputs.")) {
                    String name = normalized.substring("inputs.".length()).split("\\.")[0];
                    if (!inputProperties.contains(name)) {
                        error(issues, "UNKNOWN_WORKFLOW_INPUT", node.nodeId(),
                                "input binding references unknown workflow input: " + name);
                    }
                    return;
                }
                int separator = normalized.indexOf('.');
                if (separator <= 0) {
                    error(issues, "INVALID_INPUT_BINDING", node.nodeId(),
                            "binding must reference inputs.x or nodeId.output: " + expression);
                    return;
                }
                String sourceId = normalized.substring(0, separator);
                String output = normalized.substring(separator + 1).split("\\.")[0];
                WorkflowNode source = nodes.get(sourceId);
                if (source == null || !source.outputNames().contains(output)) {
                    error(issues, "UNKNOWN_NODE_OUTPUT", node.nodeId(),
                            "binding references unknown node output: " + expression);
                } else if (!ancestors.contains(sourceId)) {
                    error(issues, "NON_UPSTREAM_BINDING", node.nodeId(),
                            "binding source is not an upstream node: " + sourceId);
                }
            });
        }
    }

    private void validateSchemas(WorkflowDefinition definition,
                                 List<WorkflowValidationResult.Issue> issues) {
        validateSchema("input", definition.inputSchema(), issues);
        validateSchema("output", definition.outputSchema(), issues);
    }

    private void validateSchema(String name, ToolSchema schema,
                                List<WorkflowValidationResult.Issue> issues) {
        try {
            JsonNode root = objectMapper.readTree(schema.schema());
            if (root == null || !root.isObject() || !"object".equals(root.path("type").asText())) {
                error(issues, "INVALID_" + name.toUpperCase() + "_SCHEMA", null,
                        name + " schema root type must be object");
            }
        } catch (Exception exception) {
            error(issues, "INVALID_" + name.toUpperCase() + "_SCHEMA", null,
                    name + " schema is invalid JSON");
        }
    }

    private void validateBudget(WorkflowExecutionPolicy policy,
                                List<WorkflowValidationResult.Issue> issues) {
        if (policy.maximumSteps() > SERVER_MAX_STEPS) {
            error(issues, "MAX_STEPS_EXCEEDED", null, "maximumSteps exceeds server limit");
        }
        if (policy.maximumParallelism() > SERVER_MAX_PARALLELISM) {
            error(issues, "MAX_PARALLELISM_EXCEEDED", null, "maximumParallelism exceeds server limit");
        }
        if (policy.timeout().compareTo(SERVER_MAX_TIMEOUT) > 0) {
            error(issues, "TIME_BUDGET_EXCEEDED", null, "workflow timeout exceeds server limit");
        }
    }

    private void detectCycle(Set<String> nodeIds, Map<String, List<WorkflowEdge>> outgoing,
                             Map<String, List<WorkflowEdge>> incoming,
                             List<WorkflowValidationResult.Issue> issues) {
        Map<String, Integer> degree = new HashMap<>();
        nodeIds.forEach(id -> degree.put(id, incoming.getOrDefault(id, List.of()).size()));
        Deque<String> ready = new ArrayDeque<>();
        degree.forEach((id, value) -> {
            if (value == 0) ready.add(id);
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            visited++;
            for (WorkflowEdge edge : outgoing.getOrDefault(id, List.of())) {
                if (degree.compute(edge.targetNodeId(), (key, value) -> value - 1) == 0) {
                    ready.add(edge.targetNodeId());
                }
            }
        }
        if (visited != nodeIds.size()) {
            error(issues, "WORKFLOW_CYCLE", null, "workflow contains a cycle");
        }
    }

    private Set<String> reachable(String start, Map<String, List<WorkflowEdge>> outgoing) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (result.add(id)) {
                outgoing.getOrDefault(id, List.of()).forEach(edge -> queue.add(edge.targetNodeId()));
            }
        }
        return result;
    }

    private Set<String> ancestors(String nodeId, Map<String, List<WorkflowEdge>> incoming) {
        Set<String> result = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            for (WorkflowEdge edge : incoming.getOrDefault(queue.removeFirst(), List.of())) {
                if (result.add(edge.sourceNodeId())) queue.add(edge.sourceNodeId());
            }
        }
        return result;
    }

    private Set<String> schemaProperties(ToolSchema schema) {
        try {
            Set<String> result = new HashSet<>();
            objectMapper.readTree(schema.schema()).path("properties").properties()
                    .forEach(entry -> result.add(entry.getKey()));
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Set<String> schemaRequired(ToolSchema schema) {
        try {
            Set<String> result = new LinkedHashSet<>();
            objectMapper.readTree(schema.schema()).path("required").forEach(node -> result.add(node.asText()));
            return result;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Map<String, String> effectiveBindings(WorkflowNode target, List<WorkflowEdge> edges,
                                                  List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> byId = new HashMap<>();
        nodes.forEach(node -> byId.put(node.nodeId(), node));
        Map<String, String> bindings = new LinkedHashMap<>(target.inputBindings());
        edges.stream().filter(edge -> edge.targetNodeId().equals(target.nodeId()))
                .filter(edge -> edge.targetInput() != null && !edge.targetInput().isBlank())
                .forEach(edge -> {
                    WorkflowNode source = byId.get(edge.sourceNodeId());
                    if (source != null) bindings.putIfAbsent(edge.targetInput(), edgeExpression(edge, source));
                });
        return bindings;
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

    private String normalizeExpression(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.startsWith("${") && result.endsWith("}")) {
            result = result.substring(2, result.length() - 1);
        }
        return result.startsWith("$") ? result.substring(1) : result;
    }

    private boolean validExpressionSyntax(String value) {
        if (value == null) return true;
        String expression = value.trim();
        return expression.startsWith("${") == expression.endsWith("}");
    }

    private String configurationText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return null;
        return text.trim();
    }

    private void error(List<WorkflowValidationResult.Issue> issues, String code,
                       String nodeId, String message) {
        issues.add(new WorkflowValidationResult.Issue(code,
                WorkflowValidationResult.Issue.Severity.ERROR, nodeId, message));
    }
}
