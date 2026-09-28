package com.arte.ai.pojo.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流运行、节点记录和最近检查点组合视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowRunDetailView(WorkflowRunView run, List<WorkflowNodeRunView> nodes,
                                    long checkpointSequence, Map<String, Object> checkpointState) {
    public WorkflowRunDetailView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        checkpointState = checkpointState == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(checkpointState));
    }
}
