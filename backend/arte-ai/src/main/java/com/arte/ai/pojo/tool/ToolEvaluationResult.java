package com.arte.ai.pojo.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具评估结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolEvaluationResult(
        String suiteId,
        String caseId,
        String evaluatorName,
        boolean passed,
        Map<String, Double> scores,
        String feedback,
        List<String> evidence
) {

    public ToolEvaluationResult {
        requireText(suiteId, "suiteId");
        requireText(caseId, "caseId");
        requireText(evaluatorName, "evaluatorName");
        scores = scores == null ? Map.of() : Map.copyOf(scores);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (scores.values().stream().anyMatch(score -> score == null || !Double.isFinite(score))) {
            throw new IllegalArgumentException("scores must contain only finite values");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
