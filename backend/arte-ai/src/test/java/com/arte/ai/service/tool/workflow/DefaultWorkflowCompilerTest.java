package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolRegistry;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.common.enums.tool.WorkflowNodeTypeEnum;
import com.arte.ai.pojo.tool.*;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class DefaultWorkflowCompilerTest {

    private static final ToolSchema EMPTY_SCHEMA = new ToolSchema(
            "https://json-schema.org/draft/2020-12/schema", "{\"type\":\"object\",\"properties\":{}}");
    private static final ToolReference TOOL_REFERENCE = new ToolReference("article", "summarize", "1.0.0");

    @Test
    public void shouldCompilePinnedParallelDag() {
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultWorkflowValidator validator = new DefaultWorkflowValidator(registry(), objectMapper);
        DefaultWorkflowCompiler compiler = new DefaultWorkflowCompiler(validator, objectMapper);

        CompiledWorkflow compiled = compiler.compile(validWorkflow());

        assertEquals("start", compiled.entryNodeId());
        assertEquals(TOOL_REFERENCE, compiled.pinnedTools().get("a"));
        assertEquals(TOOL_REFERENCE, compiled.pinnedTools().get("b"));
        assertEquals(List.of("start"), compiled.parallelGroups().get(0));
        assertEquals(List.of("a", "b"), compiled.parallelGroups().get(1));
        assertEquals(Set.of("a", "b"), compiled.dependencies().get("join"));
        assertEquals("${a.result}", compiled.variableMappings().get("join").get("left"));
        assertEquals("${inputs.text}", compiled.variableMappings().get("b").get("text"));
        assertEquals(64, compiled.checksum().length());
    }

    @Test
    public void shouldReportCyclesAndUnreachableNodes() {
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultWorkflowValidator validator = new DefaultWorkflowValidator(registry(), objectMapper);
        List<WorkflowNode> nodes = List.of(
                new WorkflowNode.StartNode("start", "Start", Set.of(), Map.of()),
                configured("a"), configured("b"),
                new WorkflowNode.EndNode("end", "End", Map.of(), Map.of()),
                configured("orphan"));
        List<WorkflowEdge> edges = List.of(
                edge("e1", "start", "a"), edge("e2", "a", "b"),
                edge("e3", "b", "a"), edge("e4", "b", "end"));
        WorkflowDefinition definition = definition(nodes, edges);

        WorkflowValidationResult result = validator.validate(definition);

        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("WORKFLOW_CYCLE")));
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("UNREACHABLE_NODE")
                && "orphan".equals(issue.nodeId())));
    }

    private WorkflowDefinition validWorkflow() {
        List<WorkflowNode> nodes = List.of(
                new WorkflowNode.StartNode("start", "Start", Set.of("text"), Map.of()),
                toolNode("a"), toolNode("b"),
                new WorkflowNode.ConfiguredNode("join", "Join", WorkflowNodeTypeEnum.JOIN,
                        Map.of("left", "${a.result}", "right", "${b.result}"),
                        Set.of("result"), Map.of(), null),
                new WorkflowNode.EndNode("end", "End", Map.of("result", "${join.result}"), Map.of()));
        List<WorkflowEdge> edges = List.of(
                edge("e1", "start", "a"),
                new WorkflowEdge("e2", "start", "text", "b", "text", null),
                edge("e3", "a", "join"), edge("e4", "b", "join"), edge("e5", "join", "end"));
        return definition(nodes, edges);
    }

    private WorkflowNode toolNode(String id) {
        return new WorkflowNode.ToolNode(id, id.toUpperCase(), TOOL_REFERENCE,
                "b".equals(id) ? Map.of() : Map.of("text", "${inputs.text}"),
                Set.of("result"), Map.of("bindingId", "binding-1"), null);
    }

    private WorkflowNode configured(String id) {
        return new WorkflowNode.ConfiguredNode(id, id, WorkflowNodeTypeEnum.ROUTER,
                Map.of(), Set.of(), Map.of(), null);
    }

    private WorkflowEdge edge(String id, String source, String target) {
        return new WorkflowEdge(id, source, null, target, null, null);
    }

    private WorkflowDefinition definition(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        ToolSchema inputs = new ToolSchema(EMPTY_SCHEMA.dialect(),
                "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
        return new WorkflowDefinition("workflow-1", "1.0.0", "Article workflow", "test",
                inputs, EMPTY_SCHEMA, nodes, edges, Set.of("article"), WorkflowExecutionPolicy.defaults());
    }

    private ToolRegistry registry() {
        Tool<?, ?> tool = new Tool<DynamicToolRequest, DynamicToolResponse>() {
            @Override
            public ToolDefinition getDefinition() {
                ToolSchema toolInput = new ToolSchema(EMPTY_SCHEMA.dialect(),
                        "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                                + "\"required\":[\"text\"]}");
                ToolSchema toolOutput = new ToolSchema(EMPTY_SCHEMA.dialect(),
                        "{\"type\":\"object\",\"properties\":{\"result\":{\"type\":\"string\"}}}");
                return new ToolDefinition(TOOL_REFERENCE, "Summarize", "Summarize article text.",
                        toolInput, toolOutput,
                        new ToolCapabilities(false, Set.of(ToolExecutionModeEnum.BLOCKING),
                                false, false, Set.of("text"), Set.of("result")),
                        new ToolRiskProfile(ToolRiskLevelEnum.LOW, true, false,
                                true, true, false, Set.of(), Set.of()), Map.of(),
                        new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                                0, Duration.ZERO, 1024, false, true), Set.of(), false);
            }

            @Override
            public java.util.concurrent.CompletionStage<ToolResult<DynamicToolResponse>> execute(
                    ToolInvocation<DynamicToolRequest> invocation) {
                return CompletableFuture.completedFuture(null);
            }
        };
        return new ToolRegistry() {
            @Override
            public void register(Tool<?, ?> ignored) {
            }

            @Override
            public void unregister(ToolReference reference) {
            }

            @Override
            public void unregisterProvider(String providerId) {
            }

            @Override
            public Optional<Tool<?, ?>> resolve(ToolReference reference) {
                return TOOL_REFERENCE.equals(reference) ? Optional.of(tool) : Optional.empty();
            }

            @Override
            public List<ToolDefinition> search(ToolQuery query) {
                return List.of(tool.getDefinition());
            }

            @Override
            public void addListener(Listener listener) {
            }

            @Override
            public void removeListener(Listener listener) {
            }
        };
    }
}
