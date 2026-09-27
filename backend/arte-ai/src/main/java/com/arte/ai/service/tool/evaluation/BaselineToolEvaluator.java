package com.arte.ai.service.tool.evaluation;

import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.evaluation.ToolEvaluator;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.pojo.tool.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 一期确定性评分器：工具选择、参数、成功、延迟、Token、错误和安全拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
public class BaselineToolEvaluator implements ToolEvaluator {
    @Override
    public String getName() {
        return "baseline";
    }

    @Override
    public CompletionStage<ToolEvaluationResult> evaluate(ToolEvaluationContext context) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Object expectedTool = context.criteria().get("expectedTool");
        String actualTool = context.invocation().tool().namespace() + ":"
                + context.invocation().tool().name() + ":" + context.invocation().tool().version();
        scores.put("toolSelection", expectedTool == null
                || expectedTool.equals(context.invocation().tool())
                || expectedTool.equals(actualTool) ? 1D : 0D);
        Object expectedArguments = context.criteria().get("expectedArguments");
        Object actualArguments = context.invocation().request() instanceof DynamicToolRequest dynamic
                ? dynamic.arguments() : context.invocation().request();
        scores.put("parameterCorrectness", expectedArguments == null
                || Objects.deepEquals(expectedArguments, actualArguments) ? 1D : 0D);
        scores.put("outcomeCorrectness", context.expectedOutcome() == null
                || Objects.deepEquals(context.expectedOutcome(), context.actualOutcome()) ? 1D : 0D);
        boolean success = context.result() instanceof ToolResult.Succeeded<?>;
        scores.put("success", success ? 1D : 0D);
        scores.put("successRate", success ? 1D : 0D);
        scores.put("errorRate", success ? 0D : 1D);
        long latency = latency(context.trace());
        long maximumLatency = number(context.criteria().get("maximumLatencyMs"), Long.MAX_VALUE);
        scores.put("latency", latency <= maximumLatency ? 1D : 0D);
        ToolUsage usage = context.result() instanceof ToolResult.Succeeded<?> succeeded ? succeeded.usage()
                : context.result() instanceof ToolResult.Unsuccessful<?> failed ? failed.usage() : null;
        long tokens = usage == null ? 0L : nullable(usage.inputTokens()) + nullable(usage.outputTokens());
        long maximumTokens = number(context.criteria().get("maximumTokens"), Long.MAX_VALUE);
        scores.put("tokenEfficiency", tokens <= maximumTokens ? 1D : 0D);
        boolean expectRejection = Boolean.TRUE.equals(context.criteria().get("expectSecurityRejection"));
        boolean rejected = context.result().status() == ToolResultStatusEnum.DENIED;
        scores.put("securityRejectionAccuracy", expectRejection == rejected ? 1D : 0D);
        double maximumErrorRate = decimal(context.criteria().get("maximumErrorRate"), 0D);
        boolean passed = scores.entrySet().stream()
                .filter(entry -> !"errorRate".equals(entry.getKey()))
                .allMatch(entry -> entry.getValue() >= 1D)
                && scores.get("errorRate") <= maximumErrorRate;
        return CompletableFuture.completedFuture(new ToolEvaluationResult(
                String.valueOf(context.attributes().get("suiteId")),
                String.valueOf(context.attributes().get("caseId")), getName(), passed, scores,
                passed ? "all baseline criteria passed" : "one or more baseline criteria failed",
                List.of("status=" + context.result().status(), "latencyMs=" + latency, "tokens=" + tokens)));
    }

    private long latency(ToolExecutionTrace trace) {
        if (trace == null || trace.events().size() < 2) return 0L;
        return Duration.between(trace.events().getFirst().occurredAt(),
                trace.events().getLast().occurredAt()).toMillis();
    }

    private long nullable(Long value) {
        return value == null ? 0L : value;
    }

    private long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private double decimal(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
