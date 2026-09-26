package com.arte.ai.pojo.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 一个可重复执行的不可变工具评估用例。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolEvaluationCase(
        String caseId,
        String name,
        ToolCallRequest call,
        Object expectedOutcome,
        Map<String, Object> criteria
) {

    public ToolEvaluationCase {
        requireText(caseId, "caseId");
        requireText(name, "name");
        Objects.requireNonNull(call, "call must not be null");
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
