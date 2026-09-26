package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolReference;

import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * HTTP 或 OpenAPI 工具提供者适配框架
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public class HttpToolProviderAdapter implements ToolProvider {

    private final String providerId;
    private final String name;
    private final ToolProviderTypeEnum providerType;
    private final String endpoint;
    private final Map<String, Object> configuration;
    private final RemoteToolLoader loader;
    private volatile Map<ToolReference, Tool<?, ?>> tools = Map.of();

    public HttpToolProviderAdapter(String providerId, String name, ToolProviderTypeEnum providerType,
                                   String endpoint, Map<String, Object> configuration,
                                   RemoteToolLoader loader) {
        if (providerType != ToolProviderTypeEnum.HTTP && providerType != ToolProviderTypeEnum.OPENAPI) {
            throw new IllegalArgumentException("providerType must be HTTP or OPENAPI");
        }
        this.providerId = requireText(providerId, "providerId");
        this.name = requireText(name, "name");
        this.providerType = providerType;
        this.endpoint = requireText(endpoint, "endpoint");
        this.configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
        this.loader = loader;
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
        return providerType;
    }

    @Override
    public Optional<String> getEndpoint() {
        return Optional.of(endpoint);
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return configuration;
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
        return loader.load(endpoint, configuration).thenAccept(loaded -> tools = index(loaded));
    }

    private Map<ToolReference, Tool<?, ?>> index(List<Tool<?, ?>> loaded) {
        Map<ToolReference, Tool<?, ?>> result = new LinkedHashMap<>();
        if (loaded == null) {
            return Map.of();
        }
        for (Tool<?, ?> tool : loaded) {
            ToolReference reference = tool.getDefinition().reference();
            if (result.putIfAbsent(reference, tool) != null) {
                throw new IllegalStateException("duplicate remote tool: " + reference);
            }
        }
        return Map.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
