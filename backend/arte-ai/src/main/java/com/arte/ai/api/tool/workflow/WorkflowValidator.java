package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.WorkflowDefinition;
import com.arte.ai.pojo.tool.WorkflowValidationResult;

/**
 * 发布前检查 Schema 连线、不可达节点、非法循环、权限、风险、预算和工具版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface WorkflowValidator {

    WorkflowValidationResult validate(WorkflowDefinition definition);
}
