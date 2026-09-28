package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolGateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 来自 LLM、REST、MCP 或工作流程的动态工具调用请求。
 *
 * <p>{@link ToolGateway} 将参数按工具输入 Schema 校验并转换为强类型
 * {@link ToolInvocation}。调用方提供的 policy override 只是偏好，执行器必须使其受限于
 * 服务端安全上限，不能用它关闭审批、授权或 Guardrail。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolCallRequest(
        String callId,
        ToolReference tool,
        Map<String, Object> arguments,
        ToolExecutionContext context,
        ToolPolicyOverride policyOverride
) {

    public ToolCallRequest {
        if (callId == null || callId.isBlank()) {
            callId = UUID.randomUUID().toString();
        }
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
