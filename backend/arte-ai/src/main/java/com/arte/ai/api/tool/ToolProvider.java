package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 工具来源抽象。可对应本地 Bean、MCP Server、HTTP 服务或 Agent-as-Tool。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolProvider {

    String getProviderId();

    default String getName() {
        return getProviderId();
    }

    ToolProviderTypeEnum getProviderType();

    default Optional<String> getEndpoint() {
        return Optional.empty();
    }

    /**
     * 只返回可持久化的非敏感配置，不能包含 API Key、Token 或密码。
     */
    default Map<String, Object> getConfiguration() {
        return Map.of();
    }

    /**
     * 是否允许在应用启动完成后自动刷新。MCP 等动态远程提供者可关闭，改为按需刷新。
     */
    default boolean supportsStartupRefresh() {
        return true;
    }

    List<ToolDefinition> listDefinitions();

    Optional<Tool<?, ?>> resolve(ToolReference reference);

    CompletionStage<Void> refresh();
}
