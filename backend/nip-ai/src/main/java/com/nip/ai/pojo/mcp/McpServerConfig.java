package com.nip.ai.pojo.mcp;

import java.util.Map;

/**
 * MCP 服务端参数配置实体对象
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/28 22:23 ✾
 **/
public record McpServerConfig(
        String name,
        String url,
        String endpoint,
        Map<String, String> headers
) {
}