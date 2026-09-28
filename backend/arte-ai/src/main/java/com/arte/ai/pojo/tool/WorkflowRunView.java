package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 当前用户工作流运行的安全视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowRunView(String runId, String workflowId, String workflowVersion,
                              String traceId, String status, Map<String, Object> inputs,
                              Map<String, Object> variables, Set<String> activeNodeIds,
                              Map<String, Object> outputs, Map<String, Object> errorInfo,
                              int maximumSteps, int currentSteps, Instant startedAt,
                              Instant completedAt, Instant deadlineAt, long rowVersion,
                              Instant createTime, Instant updateTime) {
    public WorkflowRunView {
        inputs = copy(inputs);
        variables = copy(variables);
        activeNodeIds = activeNodeIds == null ? Set.of() : Set.copyOf(activeNodeIds);
        outputs = copy(outputs);
        errorInfo = copy(errorInfo);
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
