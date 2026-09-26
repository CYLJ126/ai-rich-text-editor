package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolRequest;

/**
 * 已完成动态参数反序列化后的强类型工具调用。
 *
 * @param <I> 工具输入类型
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolInvocation<I extends ToolRequest>(
        String callId,
        ToolReference tool,
        I request,
        ToolExecutionContext context,
        ToolExecutionPolicy effectivePolicy
) {

    public ToolInvocation {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (tool == null || request == null || context == null || effectivePolicy == null) {
            throw new IllegalArgumentException("tool, request, context and effectivePolicy are required");
        }
    }
}
