package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.WorkflowDefinition;
import com.arte.ai.pojo.tool.WorkflowRun;

import java.util.List;
import java.util.Optional;

/**
 * 工作流程定义、版本和运行记录的持久化端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface WorkflowRepository {

    void saveDefinition(WorkflowDefinition definition);

    Optional<WorkflowDefinition> findDefinition(String workflowId, String version);

    List<WorkflowDefinition> listVersions(String workflowId);

    void saveRun(WorkflowRun run);

    Optional<WorkflowRun> findRun(String runId);
}
