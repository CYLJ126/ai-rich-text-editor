package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.CompiledWorkflow;
import com.arte.ai.pojo.tool.WorkflowDefinition;

/**
 * 将页面保存的工作流程 DSL 编译为可执行计划。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface WorkflowCompiler {

    CompiledWorkflow compile(WorkflowDefinition definition);
}
