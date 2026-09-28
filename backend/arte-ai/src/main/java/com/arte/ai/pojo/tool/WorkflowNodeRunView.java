package com.arte.ai.pojo.tool;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流节点执行记录的安全视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowNodeRunView(String nodeRunId, String nodeId, String nodeType,
                                  int attempt, String status, String callId,
                                  Map<String, Object> inputs, Map<String, Object> outputs,
                                  Map<String, Object> errorInfo, Instant startedAt,
                                  Instant completedAt, Long latencyMs) {
    public WorkflowNodeRunView {
        inputs = copy(inputs);
        outputs = copy(outputs);
        errorInfo = copy(errorInfo);
    }

    private static Map<String, Object> copy(Map<String, Object> value) {
        return value == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
