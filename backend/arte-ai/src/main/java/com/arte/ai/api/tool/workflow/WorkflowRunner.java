package com.arte.ai.api.tool.workflow;

import com.arte.ai.pojo.tool.CompiledWorkflow;
import com.arte.ai.pojo.tool.WorkflowExecutionContext;
import com.arte.ai.pojo.tool.WorkflowRun;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 工作流程运行时顶层接口。
 * <p>
 * 工作流程内的 TOOL 节点必须通过 ToolGateway/ToolExecutor 执行，不应绕过工具安全、审批和追踪管道。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface WorkflowRunner {

    CompletionStage<WorkflowRun> start(CompiledWorkflow workflow, WorkflowExecutionContext context);

    CompletionStage<WorkflowRun> resume(String resumeToken);

    CompletionStage<WorkflowRun> resume(String resumeToken, String ownerId);

    CompletionStage<WorkflowRun> cancel(String runId);

    CompletionStage<WorkflowRun> cancel(String runId, String ownerId);

    CompletionStage<Optional<WorkflowRun>> findRun(String runId, String ownerId);
}
