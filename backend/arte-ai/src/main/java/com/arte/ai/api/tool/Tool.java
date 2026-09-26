package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolInvocation;

import java.util.concurrent.CompletionStage;

/**
 * AI 工具的顶层领域接口。
 *
 * <p>工具只负责定义能力和执行业务操作。参数校验、认证授权、Guardrail、审批、
 * 重试、限流、追踪和评估等横切能力由 {@link ToolExecutor} 的执行管道负责。
 * 这样本地 Java 工具、MCP 工具、HTTP 工具和 Agent-as-Tool 可共享同一调用流程。
 *
 * @param <I> 工具输入类型
 * @param <O> 工具输出类型
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 10:19 ✾
 */
public interface Tool<I extends ToolRequest, O extends ToolResponse> {

    /**
     * 返回不可变、可版本化的工具定义。
     */
    ToolDefinition getDefinition();

    /**
     * 执行一次工具调用。
     *
     * <p>顶层契约使用 {@link CompletionStage}，以便同时容纳同步实现和非阻塞实现。
     * {@link ToolExecutionModeEnum#DEFERRED} 由 {@link ToolExecutor} 通过 {@link ToolTaskManager}
     * 转换为持久化任务，不应仅靠本方法返回的 Future 维持长时间任务状态。
     * 具体框架适配器可将本契约桥接为同步调用。
     */
    CompletionStage<ToolResult<O>> execute(ToolInvocation<I> invocation);
}
