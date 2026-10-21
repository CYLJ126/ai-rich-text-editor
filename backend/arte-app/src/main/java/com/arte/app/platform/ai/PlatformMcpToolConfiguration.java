package com.arte.app.platform.ai;

import com.arte.ainew.api.control.ConnectionManager;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.HttpConnectionRuntime;
import com.arte.ainew.infrastructure.http.McpHealthToolAdapter;
import com.arte.ainew.infrastructure.http.McpStreamableHttpTransport;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.app.platform.ai.tool.McpHealthToolProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

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
        var definition = connection(properties);
        var transport = new NewAiGenerationProperties(false, properties.allowedOrigins(), List.of(), 8, 4, 8, Duration.ofSeconds(2), 65536, 1024);
        // 受理资格不冻结授权；获取连接及真正发送前再次复核当前平台身份、空间、AI 资格和工具使用权。
        ConnectionManager connections = (reference, context) -> platform.authorizeUse(context).flatMap(permit ->
                reference.equals(definition.definition()) && context.budgetRef() == null
                        ? Mono.just(definition) : Mono.error(new org.springframework.security.access.AccessDeniedException("MCP connection denied")));
        return new HttpConnectionRuntime(connections, authorization, (reference, context) -> Mono.error(new IllegalStateException("MCP tool credentials unsupported")), transport, clock);
    }

    @Bean
    McpHealthToolAdapter mcpHealthTool(McpHealthToolProperties properties, @Qualifier("newAiToolMcpRuntime") HttpConnectionRuntime runtime) {
        return new McpHealthToolAdapter(connection(properties), new McpStreamableHttpTransport(runtime));
    }
}
