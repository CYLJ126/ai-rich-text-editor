package com.arte.ai.pojo.tool;

import com.arte.ai.api.tool.ToolResponse;

/**
 * Spring AI、HTTP 和 MCP 动态工具响应
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public record DynamicToolResponse(Object value, String rawContent) implements ToolResponse {
}
