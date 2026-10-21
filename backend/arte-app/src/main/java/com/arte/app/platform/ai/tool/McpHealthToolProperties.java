package com.arte.app.platform.ai.tool;

import com.arte.ainew.config.NewAiGenerationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * 首个 MCP 工具默认关闭；固定完整地址和独立出口白名单仅由运维设置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-mcp", ignoreUnknownFields = false)
public record McpHealthToolProperties(boolean enabled, String version, URI endpoint, List<URI> allowedOrigins) {

    public McpHealthToolProperties {
        version = version == null ? "v1" : version;
        new com.arte.ainew.common.reference.DefinitionRef("connection", "arte-mcp-health", version);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (enabled && (endpoint == null || allowedOrigins.isEmpty()))
            throw new IllegalArgumentException("MCP tool requires endpoint and explicit allowed origins");
        if (!allowedOrigins.isEmpty()) {
            var transport = new NewAiGenerationProperties(false, allowedOrigins, List.of(), 0, 0, 0, null, 0, 0);
            if (enabled) {
                new com.arte.ainew.pojo.control.ConnectionDefinition(new com.arte.ainew.common.reference.DefinitionRef("connection", "arte-mcp-health", version),
                        "arte-mcp-health", com.arte.ainew.pojo.tool.ToolDefinitionRevision.MCP_PROTOCOL, endpoint, null,
                        com.arte.ainew.pojo.control.ConnectionDefinition.State.ENABLED, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(5), 65536);
                new com.arte.ainew.infrastructure.http.HttpEgressPolicy(transport).validateOrigin(endpoint);
            }
        }
    }
}
