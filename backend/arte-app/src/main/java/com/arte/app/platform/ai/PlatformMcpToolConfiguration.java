package com.arte.app.platform.ai;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.http.McpHealthToolAdapter;
import com.arte.ainew.infrastructure.http.McpStreamableHttpTransport;
import com.arte.ainew.infrastructure.http.ModelScopeBingToolAdapter;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.app.platform.ai.tool.McpHealthToolProperties;
import com.arte.core.pojo.reference.DefinitionRef;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具隔离装配；无启动外联、不自动发现／发布／建绑，不借用模型预算或模型连接资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 12:50 ✾
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpHealthToolProperties.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-tool-mcp.enabled"}, havingValue = "true")
public class PlatformMcpToolConfiguration {

    private static ConnectionDefinition connection(McpHealthToolProperties properties) {
        return new ConnectionDefinition(new DefinitionRef("connection", "arte-mcp-health", properties.version()), "arte-mcp-health", com.arte.ainew.pojo.tool.ToolDefinitionRevision.MCP_PROTOCOL,
                properties.endpoint(), null, ConnectionDefinition.State.ENABLED, Duration.ofSeconds(3), Duration.ofSeconds(5), 65536);
    }

    @Bean(name = "newAiToolMcpRuntime", destroyMethod = "close")
    HttpConnectionRuntime runtime(McpHealthToolProperties properties, PlatformToolManagementAccess platform,
                                  AdmissionAuthorization authorization, @Qualifier("newAiClock") Clock clock) {
        var health = connection(properties);
        var bing = ModelScopeBingToolAdapter.connectionDefinition();
        var definitions = Map.of(health.definition(), health, bing.definition(), bing);
        ConnectionManager connections = (reference, context) ->
                platform.authorizeUse(context).flatMap(permit -> {
                    ConnectionDefinition selected = definitions.get(reference);
                    if (selected == null || context.budgetRef() != null) {
                        return Mono.error(
                                new AccessDeniedException("MCP connection denied"));
                    }
                    return Mono.just(selected);
                });
        var transport = new NewAiGenerationProperties(false, properties.allowedOrigins(), List.of(), 8,
                4, 8, Duration.ofSeconds(2), 65536, 1024);
        return new HttpConnectionRuntime(connections, authorization, (reference, context) ->
                Mono.error(new IllegalStateException("MCP tool credentials unsupported")), transport, clock);
    }

    @Bean
    McpHealthToolAdapter mcpHealthTool(McpHealthToolProperties properties, @Qualifier("newAiToolMcpRuntime") HttpConnectionRuntime runtime) {
        return new McpHealthToolAdapter(connection(properties), new McpStreamableHttpTransport(runtime));
    }

    @Bean
    ModelScopeBingToolAdapter modelScopeBingToolAdapter(@Qualifier("newAiToolMcpRuntime") HttpConnectionRuntime runtime) {
        return new ModelScopeBingToolAdapter(runtime);
    }
}
