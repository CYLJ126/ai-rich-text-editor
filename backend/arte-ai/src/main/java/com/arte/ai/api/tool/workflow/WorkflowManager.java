package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.CompiledWorkflow;
import com.arte.ai.pojo.tool.ToolPrincipal;
import com.arte.ai.pojo.tool.WorkflowDefinition;
import com.arte.ai.pojo.tool.WorkflowValidationResult;

import java.util.List;
import java.util.Optional;

/**
 * 页面工作流定义和不可变版本的管理入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public interface WorkflowManager {

    default WorkflowDefinition saveDraft(String ownerId, WorkflowDefinition definition) {
        return saveDraft(new ToolPrincipal(ownerId, ownerId, java.util.Set.of(), java.util.Set.of()), definition);
    }

    WorkflowDefinition saveDraft(ToolPrincipal principal, WorkflowDefinition definition);

    default WorkflowDefinition saveDraft(ToolPrincipal principal, WorkflowDefinition definition,
                                         Long expectedRowVersion) {
        return saveDraft(principal, definition);
    }

    WorkflowValidationResult validate(ToolPrincipal principal, WorkflowDefinition definition);

    default CompiledWorkflow publish(String ownerId, String workflowId, String version,
                                     long expectedRowVersion) {
        return publish(new ToolPrincipal(ownerId, ownerId, java.util.Set.of(), java.util.Set.of()),
                workflowId, version, expectedRowVersion);
    }

    CompiledWorkflow publish(ToolPrincipal principal, String workflowId, String version,
                             long expectedRowVersion);

    Optional<WorkflowDefinition> find(String ownerId, String workflowId, String version);

    List<WorkflowDefinition> listVersions(String ownerId, String workflowId);
}
