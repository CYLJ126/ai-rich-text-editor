package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolCallRequest;
import com.arte.ai.pojo.tool.ToolTaskHandle;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 工具平台的统一动态入口。
 *
 * <p>典型流程：解析工具 -> 校验/反序列化 -> 构造调用上下文 ->
 * 交给 {@link ToolExecutor} -> 序列化统一结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolGateway {

    CompletionStage<ToolResult<? extends ToolResponse>> invoke(ToolCallRequest request);

    CompletionStage<ToolResult<? extends ToolResponse>> resume(String resumeToken);

    /**
     * 面向不受信入口的所有者作用域恢复。
     */
    CompletionStage<ToolResult<? extends ToolResponse>> resume(String resumeToken, String ownerId);

    CompletionStage<Boolean> cancel(String callId);

    /**
     * 面向不受信入口的所有者作用域取消。
     */
    CompletionStage<Boolean> cancel(String callId, String ownerId);

    /**
     * 查询持久化异步任务的当前状态。
     */
    CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId);

    CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId, String ownerId);

    /**
     * 查询持久化异步任务的最终结果。任务未进入终态时返回空。
     */
    CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findTaskResult(String taskId);

    CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findTaskResult(String taskId, String ownerId);
}
