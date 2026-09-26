package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolInvocation;

import java.util.concurrent.CompletionStage;

/**
 * 强类型工具执行管道。
 *
 * <p>实现应按管道顺序执行参数校验、认证授权、Guardrail、审批、限流、
 * 超时/重试、工具调用、输出校验、追踪和审计。
 *
 * <p>对 BLOCKING/NON_BLOCKING 模式，CompletionStage 最终完成为工具结果；
 * 对 DEFERRED 模式，执行器应先通过 {@link ToolTaskManager} 持久化任务，
 * 然后完成为带 task handle 的
 * {@link com.arte.ai.common.enums.tool.ToolResultStatusEnum#ACCEPTED} 结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolExecutor {

    <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolResult<O>> execute(Tool<I, O> tool, ToolInvocation<I> invocation);
}
