package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolExecutionPolicy;
import com.arte.ai.pojo.tool.ToolReference;
import com.arte.ai.pojo.tool.ToolRiskProfile;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Spring AI ToolCallbackProvider 适配器，可用于 MCP 工具提供者
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public class SpringAiToolCallbackProviderAdapter implements ToolProvider {

    private final String providerId;
    private final String name;
    private final String namespace;
    private final String version;
    private final String endpoint;
    private final ToolCallbackProvider callbackProvider;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private volatile Map<ToolReference, Tool<?, ?>> tools = Map.of();

    public SpringAiToolCallbackProviderAdapter(String providerId, String name, String namespace,
                                               String version, String endpoint,
                                               ToolCallbackProvider callbackProvider,
                                               ObjectMapper objectMapper, Executor executor) {
        this.providerId = requireText(providerId, "providerId");
        this.name = requireText(name, "name");
        this.namespace = requireText(namespace, "namespace");
        this.version = requireText(version, "version");
        this.endpoint = requireText(endpoint, "endpoint");
        this.callbackProvider = callbackProvider;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    public String getProviderId() {
        return providerId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ToolProviderTypeEnum getProviderType() {
        return ToolProviderTypeEnum.MCP;
    }

    @Override
    public Optional<String> getEndpoint() {
        return Optional.of(endpoint);
    }

    @Override
    public boolean supportsStartupRefresh() {
        return false;
    }

    @Override
    public List<ToolDefinition> listDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .sorted(Comparator.comparing(definition -> definition.reference().toString()))
                .toList();
    }

    @Override
    public Optional<Tool<?, ?>> resolve(ToolReference reference) {
        return Optional.ofNullable(tools.get(reference));
    }

    @Override
    public CompletionStage<Void> refresh() {
        return CompletableFuture.runAsync(() -> {
            Map<ToolReference, Tool<?, ?>> refreshed = new LinkedHashMap<>();
            Arrays.stream(callbackProvider.getToolCallbacks())
                    .map(this::adapt)
                    .forEach(tool -> {
                        ToolReference reference = tool.getDefinition().reference();
                        if (refreshed.putIfAbsent(reference, tool) != null) {
                            throw new IllegalStateException("duplicate MCP tool: " + reference);
                        }
                    });
            tools = Map.copyOf(refreshed);
        }, executor);
    }

    private SpringAiToolAdapter adapt(ToolCallback callback) {
        ToolRiskProfile risk = new ToolRiskProfile(ToolRiskLevelEnum.MEDIUM,
                false, false, false, false, true, Set.of(), Set.of(endpoint));
        ToolExecutionPolicy policy = new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING,
                Duration.ofSeconds(30), 0, Duration.ZERO, 4096, false, false);
        return new SpringAiToolAdapter(namespace, version, callback, risk, policy, objectMapper, executor);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
