package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.evaluation.ToolEvaluator;

import java.util.List;

/**
 * 工具评估数据集与评分器组合。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolEvaluationSuite(
        String suiteId,
        String version,
        List<ToolEvaluationCase> cases,
        List<ToolEvaluator> evaluators
) {

    public ToolEvaluationSuite {
        requireText(suiteId, "suiteId");
        requireText(version, "version");
        cases = cases == null ? List.of() : List.copyOf(cases);
        evaluators = evaluators == null ? List.of() : List.copyOf(evaluators);
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("cases must not be empty");
        }
        if (evaluators.isEmpty()) {
            throw new IllegalArgumentException("evaluators must not be empty");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
