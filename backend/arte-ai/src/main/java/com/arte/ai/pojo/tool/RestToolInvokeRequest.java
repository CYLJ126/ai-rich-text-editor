package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST 工具调用请求；用户身份由服务端上下文注入，不接受客户端伪造。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record RestToolInvokeRequest(
        ToolReference tool,
        Map<String, Object> arguments,
        String bindingId,
        String workspaceId,
        String idempotencyKey,
        ToolExecutionModeEnum executionMode,
        Duration timeout,
        Integer maxRetries
) {
    public RestToolInvokeRequest {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
