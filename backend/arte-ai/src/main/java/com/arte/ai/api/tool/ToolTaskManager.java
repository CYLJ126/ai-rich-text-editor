package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.ToolInvocation;
import com.arte.ai.pojo.tool.ToolTaskHandle;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * {@link ToolExecutionModeEnum#DEFERRED} 工具任务的统一管理端口。
 * <p>
 * 提交操作在任务记录已持久化后才能完成，从而保证调用方收到 taskId 后
 * 任务不会因当前进程退出而丢失。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolTaskManager {

    <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolTaskHandle> submit(Tool<I, O> tool, ToolInvocation<I> invocation);

    /**
     * 持久化因审批或外部信号而暂停的调用，并返回携带明文恢复令牌的句柄。
     */
    <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolTaskHandle> suspend(
            Tool<I, O> tool, ToolInvocation<I> invocation, String approvalRequestId);

    CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId);

    CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findResult(String taskId);

    CompletionStage<Boolean> cancel(String taskId);

    CompletionStage<Boolean> resume(String resumeToken);

    /**
     * Worker 或工具实现报告持久化任务进度。
     */
    CompletionStage<Boolean> updateProgress(String taskId, double progress, String message);

    /**
     * 审批服务确认通过后，无需再次暴露恢复令牌即可恢复原调用。
     */
    CompletionStage<Boolean> resumeApproved(String taskId);

    /**
     * 审批拒绝或过期时终止原调用。
     */
    CompletionStage<Boolean> terminateApproval(String taskId, String reason);
}
