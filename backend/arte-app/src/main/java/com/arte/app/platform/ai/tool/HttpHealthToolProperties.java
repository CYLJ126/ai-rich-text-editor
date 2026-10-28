package com.arte.app.platform.ai.tool;

import com.arte.ainew.config.NewAiGenerationProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;

/**
 * 首个 HTTP 工具默认关闭；固定完整地址和独立出口白名单仅由运维设置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-http", ignoreUnknownFields = false)
public record HttpHealthToolProperties(boolean enabled, String version, URI endpoint, List<URI> allowedOrigins) {

    public HttpHealthToolProperties {
        version = version == null ? "v1" : version;
        new com.arte.core.pojo.reference.DefinitionRef("connection", "arte-http-health", version);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (enabled && (endpoint == null || allowedOrigins.isEmpty()))
            throw new IllegalArgumentException("HTTP tool requires endpoint and explicit allowed origins");
        if (!allowedOrigins.isEmpty()) {
            var transport = new NewAiGenerationProperties(false, allowedOrigins, List.of(), 0, 0, 0, null, 0, 0);
            if (enabled) {
                new com.arte.ainew.pojo.control.ConnectionDefinition(new com.arte.core.pojo.reference.DefinitionRef("connection", "arte-http-health", version),
                        "arte-http-health", com.arte.ainew.infrastructure.http.HttpJsonGetTransport.PROTOCOL, endpoint, null,
                        com.arte.ainew.pojo.control.ConnectionDefinition.State.ENABLED, java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(5), 65536);
                new com.arte.ainew.infrastructure.http.HttpEgressPolicy(transport).validateOrigin(endpoint);
            }
        }
    }
}
