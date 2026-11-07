package com.arte.app.platform.ai.tool;

import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.ainew.infrastructure.http.HttpJsonGetTransport;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * HTTP Health 工具属性
 * <p>
 * 首个 HTTP 工具默认关闭；固定完整地址和独立出口白名单仅由运维设置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@ConfigurationProperties(prefix = "arte.ai-new-tool-http", ignoreUnknownFields = false)
public record HttpHealthToolProperties(boolean enabled, String version, URI endpoint, List<URI> allowedOrigins) {

    public HttpHealthToolProperties {
        version = version == null ? "v1" : version;
        new DefinitionRef("connection", "arte-http-health", version);
        allowedOrigins = HttpEgressPolicy.validateOrigins(allowedOrigins);
        if (enabled && (endpoint == null || allowedOrigins.isEmpty()))
            throw new IllegalArgumentException("HTTP tool requires endpoint and explicit allowed origins");
        if (!allowedOrigins.isEmpty()) {
            List<URI> origins = allowedOrigins;
            if (enabled) {
                new ConnectionDefinition(new DefinitionRef("connection", "arte-http-health", version),
                        "arte-http-health", HttpJsonGetTransport.PROTOCOL, endpoint, null,
                        ConnectionDefinition.State.ENABLED, Duration.ofSeconds(3), Duration.ofSeconds(5), 65536);
                new HttpEgressPolicy(() -> origins).validateOrigin(endpoint);
            }
        }
    }
}
