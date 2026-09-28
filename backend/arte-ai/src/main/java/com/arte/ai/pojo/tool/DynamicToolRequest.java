package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring AI、HTTP 和 MCP 动态工具请求
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public record DynamicToolRequest(Map<String, Object> arguments) implements ToolRequest {

    public DynamicToolRequest {
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
