package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolInvocation;

import java.util.concurrent.CompletionStage;

/**
 * 工具执行管道拦截器。
 * <p>
 * 认证授权、Guardrail、审批、超时、重试、限流、缓存、Trace 等能力可以作为
 * 拦截器组合，避免侵入具体工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolExecutionInterceptor {

    <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolResult<O>> intercept(
            Tool<I, O> tool,
            ToolInvocation<I> invocation,
            Chain<I, O> chain
    );

    interface Chain<I extends ToolRequest, O extends ToolResponse> {

        CompletionStage<ToolResult<O>> proceed(Tool<I, O> tool, ToolInvocation<I> invocation);
    }
}
