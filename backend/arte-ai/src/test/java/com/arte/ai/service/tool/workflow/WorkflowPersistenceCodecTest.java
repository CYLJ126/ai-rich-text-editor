package com.arte.ai.service.tool.workflow;

import com.arte.ai.api.tool.workflow.WorkflowNode;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.WorkflowVersionPo;
import org.junit.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class WorkflowPersistenceCodecTest {

    @Test
    public void shouldRoundTripPageDslWithoutLosingToolPolicy() {
        WorkflowPersistenceCodec codec = new WorkflowPersistenceCodec(new ObjectMapper());
        ToolExecutionPolicy policy = new ToolExecutionPolicy(ToolExecutionModeEnum.DEFERRED,
                Duration.ofMinutes(2), 2, Duration.ofSeconds(1), 2048, true, false);
        WorkflowNode.ToolNode node = new WorkflowNode.ToolNode("tool", "Tool",
                new ToolReference("article", "rewrite", "2.1.0"),
                Map.of("text", "${inputs.text}"), Set.of("result"),
                Map.of("bindingId", "binding-1"), policy);
        WorkflowEdge edge = new WorkflowEdge("edge", "start", "out", "tool", "text", "true");

        WorkflowNode decodedNode = codec.decodeNodes(codec.encodeNodes(List.of(node))).getFirst();
        WorkflowEdge decodedEdge = codec.decodeEdges(codec.encodeEdges(List.of(edge))).getFirst();

        assertTrue(decodedNode instanceof WorkflowNode.ToolNode);
        WorkflowNode.ToolNode decodedTool = (WorkflowNode.ToolNode) decodedNode;
        assertEquals(node.tool(), decodedTool.tool());
        assertEquals(policy, decodedTool.executionPolicy());
        assertEquals(node.configuration(), decodedTool.configuration());
        assertEquals(edge, decodedEdge);
    }

    @Test
    public void shouldRestorePublishedCompiledPlanSnapshot() {
        WorkflowPersistenceCodec codec = new WorkflowPersistenceCodec(new ObjectMapper());
        WorkflowNode.StartNode start = new WorkflowNode.StartNode("start", "Start", Set.of(), Map.of());
        WorkflowNode.EndNode end = new WorkflowNode.EndNode("end", "End", Map.of(), Map.of());
        WorkflowEdge edge = new WorkflowEdge("edge", "start", null, "end", null, null);
        ToolSchema schema = new ToolSchema("https://json-schema.org/draft/2020-12/schema",
                "{\"type\":\"object\",\"properties\":{}}");
        WorkflowDefinition definition = new WorkflowDefinition("wf", "1", "Workflow", null,
                schema, schema, List.of(start, end), List.of(edge), Set.of());
        String checksum = codec.checksum(definition);
        CompiledWorkflow source = new CompiledWorkflow(definition, "start",
                Map.of("start", start, "end", end), List.of(edge), Map.of(),
                Map.of("start", Set.of(), "end", Set.of("start")), List.of("start", "end"),
                List.of(List.of("start"), List.of("end")), Map.of("start", Map.of(), "end", Map.of()), checksum);
        WorkflowVersionPo stored = new WorkflowVersionPo().setEntryNodeId("start").setChecksum(checksum)
                .setCompiledPlan(codec.encodePlan(source)).setPinnedTools(Map.of());

        CompiledWorkflow restored = codec.decodeCompiled(definition, stored);

        assertEquals(source.dependencies(), restored.dependencies());
        assertEquals(source.executionOrder(), restored.executionOrder());
        assertEquals(source.parallelGroups(), restored.parallelGroups());
        assertEquals(checksum, restored.checksum());
    }

    @Test
    public void checksumIsStableAcrossJvmProcesses() throws Exception {
        String expected = new WorkflowPersistenceCodec(new ObjectMapper()).checksum(sampleDefinition(false));
        for (int attempt = 0; attempt < 2; attempt++) {
            Process process = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp",
                    System.getProperty("java.class.path"), ChecksumProbe.class.getName()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertEquals(output, 0, process.waitFor());
            assertEquals(expected, output);
        }
    }

    @Test
    public void checksumSurvivesJsonPersistenceAndSchemaKeyOrdering() {
        var codec = new WorkflowPersistenceCodec(new ObjectMapper());
        WorkflowDefinition original = sampleDefinition(false);
        WorkflowDefinition restored = new WorkflowDefinition(original.workflowId(), original.version(), original.name(), original.description(),
                original.inputSchema(), original.outputSchema(), codec.decodeNodes(codec.encodeNodes(original.nodes())),
                codec.decodeEdges(codec.encodeEdges(original.edges())), original.tags(), original.executionPolicy());
        assertEquals(codec.checksum(original), codec.checksum(restored));
        assertEquals(codec.checksum(original), codec.checksum(sampleDefinition(true)));
        var changed = new WorkflowDefinition(original.workflowId(), original.version(), "Changed", original.description(),
                original.inputSchema(), original.outputSchema(), original.nodes(), original.edges(), original.tags(), original.executionPolicy());
        assertNotEquals(codec.checksum(original), codec.checksum(changed));
    }

    private static WorkflowDefinition sampleDefinition(boolean reverse) {
        var schema = new ToolSchema("https://json-schema.org/draft/2020-12/schema", reverse
                ? "{ \"properties\": {}, \"type\": \"object\" }" : "{\"type\":\"object\",\"properties\":{}}");
        Map<String, Object> nested = new java.util.LinkedHashMap<>();
        if (reverse) {
            nested.put("beta", 2);
            nested.put("alpha", 1);
        } else {
            nested.put("alpha", 1);
            nested.put("beta", 2);
        }
        var start = new WorkflowNode.StartNode("start", "Start", Set.of("a", "b", "c", "d"), Map.of("nested", nested));
        var end = new WorkflowNode.EndNode("end", "End", Map.of(), Map.of());
        return new WorkflowDefinition("wf", "1", "Workflow", null, schema, schema, List.of(start, end),
                List.of(new WorkflowEdge("edge", "start", null, "end", null, null)), Set.of("a", "b", "c", "d", "e", "f"));
    }

    public static class ChecksumProbe {
        public static void main(String[] args) {
            System.out.print(new WorkflowPersistenceCodec(new ObjectMapper()).checksum(sampleDefinition(false)));
        }
    }

}
