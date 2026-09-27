package com.arte.ai.service.tool.evaluation;

import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.*;
import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BaselineToolEvaluatorTest {

    @Test
    public void shouldScoreSelectionArgumentsOutcomeLatencyTokensAndSecurity() {
        ToolDefinition definition = definition();
        Map<String, Object> arguments = Map.of("text", "hello");
        ToolExecutionContext execution = new ToolExecutionContext("run", null, null,
                "trace", null, new ToolPrincipal("owner", "owner", Set.of(), Set.of()),
                Instant.now().plusSeconds(30), NeverToolCancellation.INSTANCE,
                null, null, Map.of());
        ToolInvocation<DynamicToolRequest> invocation = new ToolInvocation<>("call",
                definition.reference(), new DynamicToolRequest(arguments), execution,
                definition.defaultPolicy());
        Instant started = Instant.now();
        ToolUsage usage = new ToolUsage(started, started.plusMillis(20), Duration.ofMillis(20),
                10L, 5L, null, null);
        ToolResult.Succeeded<DynamicToolResponse> result = new ToolResult.Succeeded<>(
                new DynamicToolResponse(Map.of("summary", "hello"), null),
                List.of(), List.of(), usage, Map.of());
        Map<String, Object> criteria = Map.of(
                "expectedTool", "article:summarize:1.0.0",
                "expectedArguments", arguments,
                "maximumLatencyMs", 100L,
                "maximumTokens", 20L,
                "expectSecurityRejection", false);
        ToolEvaluationContext context = new ToolEvaluationContext(definition, invocation, result,
                null, null, result.output(), criteria, Map.of("suiteId", "baseline-suite",
                "caseId", "success-case"));

        ToolEvaluationResult evaluation = new BaselineToolEvaluator().evaluate(context)
                .toCompletableFuture().join();

        assertTrue(evaluation.passed());
        assertEquals(1D, evaluation.scores().get("toolSelection"), 0D);
        assertEquals(1D, evaluation.scores().get("parameterCorrectness"), 0D);
        assertEquals(1D, evaluation.scores().get("tokenEfficiency"), 0D);
    }

    private ToolDefinition definition() {
        ToolExecutionPolicy policy = new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING,
                Duration.ofSeconds(30), 0, Duration.ZERO, 1024, false, false);
        return new ToolDefinition(new ToolReference("article", "summarize", "1.0.0"),
                "Article summary", "Summarize article content into structured output.",
                new ToolSchema("json-schema", "{\"type\":\"object\"}"),
                new ToolSchema("json-schema", "{\"type\":\"object\"}"),
                new ToolCapabilities(false, Set.of(ToolExecutionModeEnum.BLOCKING),
                        true, false, Set.of("structured"), Set.of("structured")),
                new ToolRiskProfile(ToolRiskLevelEnum.LOW, true, false, true,
                        true, false, Set.of(), Set.of()), Map.of(), policy,
                Set.of("article"), false);
    }
}
