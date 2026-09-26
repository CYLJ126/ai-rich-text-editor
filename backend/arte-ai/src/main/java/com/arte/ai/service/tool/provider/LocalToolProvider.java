package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolProvider;
import com.arte.ai.common.enums.tool.ToolProviderTypeEnum;
import com.arte.ai.pojo.tool.ToolDefinition;
import com.arte.ai.pojo.tool.ToolReference;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 本地 Java 工具提供者
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Service
public class LocalToolProvider implements ToolProvider {

    public static final String PROVIDER_ID = "local-java";
    public static final String SPRING_AI_NAMESPACE = "local";
    public static final String DEFAULT_VERSION = "1.0.0";

    private final ObjectProvider<Tool<?, ?>> domainTools;
    private final ObjectProvider<ToolCallback> springAiCallbacks;
    private final SpringAiToolObjectLocator toolObjectLocator;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final String springAiNamespace;
    private final String springAiVersion;
    private volatile Map<ToolReference, Tool<?, ?>> tools = Map.of();

    public LocalToolProvider(ObjectProvider<Tool<?, ?>> domainTools,
                             ObjectProvider<ToolCallback> springAiCallbacks,
                             SpringAiToolObjectLocator toolObjectLocator,
                             ObjectMapper objectMapper,
                             @Qualifier("toolCallbackExecutor") Executor executor,
                             @Value("${arte.ai.tool.local.namespace:" + SPRING_AI_NAMESPACE + "}")
                             String springAiNamespace,
                             @Value("${arte.ai.tool.local.version:" + DEFAULT_VERSION + "}")
                             String springAiVersion) {
        this.domainTools = domainTools;
        this.springAiCallbacks = springAiCallbacks;
        this.toolObjectLocator = toolObjectLocator;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.springAiNamespace = springAiNamespace;
        this.springAiVersion = springAiVersion;
    }

    @Override
    public String getProviderId() {
        return PROVIDER_ID;
    }

    @Override
    public String getName() {
        return "Local Java Tools";
    }

    @Override
    public ToolProviderTypeEnum getProviderType() {
        return ToolProviderTypeEnum.LOCAL;
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
        Map<ToolReference, Tool<?, ?>> refreshed = new LinkedHashMap<>();
        domainTools.orderedStream().forEach(tool -> putUnique(refreshed, tool));
        springAiCallbacks.orderedStream()
                .map(this::adapt)
                .forEach(tool -> putUnique(refreshed, tool));
        List<Object> toolObjects = toolObjectLocator.findToolObjects();
        if (!toolObjects.isEmpty()) {
            Arrays.stream(MethodToolCallbackProvider.builder()
                            .toolObjects(toolObjects.toArray())
                            .build()
                            .getToolCallbacks())
                    .map(this::adapt)
                    .forEach(tool -> putUnique(refreshed, tool));
        }
        tools = Map.copyOf(refreshed);
        return CompletableFuture.completedFuture(null);
    }

    private SpringAiToolAdapter adapt(ToolCallback callback) {
        return new SpringAiToolAdapter(
                springAiNamespace,
                springAiVersion,
                callback,
                SpringAiToolAdapter.localRiskProfile(),
                SpringAiToolAdapter.defaultExecutionPolicy(),
                objectMapper,
                executor);
    }

    private void putUnique(Map<ToolReference, Tool<?, ?>> target, Tool<?, ?> tool) {
        ToolReference reference = tool.getDefinition().reference();
        if (target.putIfAbsent(reference, tool) != null) {
            throw new IllegalStateException("duplicate local tool: " + reference);
        }
    }
}
