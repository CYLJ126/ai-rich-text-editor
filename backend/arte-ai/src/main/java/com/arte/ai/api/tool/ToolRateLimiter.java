package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolInvocation;

/**
 * 工具调用限流端口。实现必须在多节点间共享配额。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public interface ToolRateLimiter {

    boolean tryAcquire(ToolInvocation<? extends ToolRequest> invocation);
}
