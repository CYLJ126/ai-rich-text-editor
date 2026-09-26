package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.WorkflowCheckpoint;

import java.util.Optional;

/**
 * 工作流程检查点持久化端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface WorkflowCheckpointRepository {

    void save(WorkflowCheckpoint checkpoint);

    Optional<WorkflowCheckpoint> findLatest(String runId);
}
