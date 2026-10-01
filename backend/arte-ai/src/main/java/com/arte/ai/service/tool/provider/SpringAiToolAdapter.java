package com.arte.ai.service.tool.provider;

import com.arte.ai.api.tool.Tool;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Spring AI ToolCallback 到工具领域接口的适配器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
public class SpringAiToolAdapter implements Tool<DynamicToolRequest, DynamicToolResponse> {

    public static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";
    private static final String DEFAULT_OUTPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "value": {"description": "Parsed tool result"},
                "rawContent": {"type": "string", "description": "Original tool result text"}
              }
            }
            """;

    private final ToolCallback callback;
    private final ToolDefinition definition;
    private final ObjectMapper objectMapper;
    private final Executor executor;
    private final Set<String> compatibleVersions;

    public SpringAiToolAdapter(String namespace, String version, ToolCallback callback,
                               ToolRiskProfile riskProfile, ToolExecutionPolicy executionPolicy,
                               ObjectMapper objectMapper, Executor executor) {
        this(namespace, version, callback, riskProfile, executionPolicy, objectMapper, executor, Set.of());
    }

    public SpringAiToolAdapter(String namespace, String version, ToolCallback callback,
                               ToolRiskProfile riskProfile, ToolExecutionPolicy executionPolicy,
                               ObjectMapper objectMapper, Executor executor, Set<String> compatibleVersions) {
        this.compatibleVersions = Set.copyOf(compatibleVersions);
        this.callback = callback;
        this.objectMapper = objectMapper;
        this.executor = executor;
        org.springframework.ai.tool.definition.ToolDefinition springDefinition = callback.getToolDefinition();
        String description = springDefinition.description();
        if (description.isBlank()) {
            description = "Invoke the Spring AI tool named " + springDefinition.name();
        }
        this.definition = new ToolDefinition(
                new ToolReference(namespace, springDefinition.name(), version),
                springDefinition.name(),
                description,
                new ToolSchema(JSON_SCHEMA_DIALECT, springDefinition.inputSchema()),
                new ToolSchema(JSON_SCHEMA_DIALECT, DEFAULT_OUTPUT_SCHEMA),
                new ToolCapabilities(false,
                        Set.of(ToolExecutionModeEnum.BLOCKING, ToolExecutionModeEnum.NON_BLOCKING),
                        false, false, Set.of("structured"), Set.of("structured", "text")),
                riskProfile,
                Map.of(),
                executionPolicy,
                Set.of("spring-ai"),
                false
        );
    }

    public static ToolRiskProfile localRiskProfile() {
        return new ToolRiskProfile(ToolRiskLevelEnum.MEDIUM,
                false, false, false, false, false, Set.of(), Set.of());
    }

    public static ToolExecutionPolicy defaultExecutionPolicy() {
        return new ToolExecutionPolicy(ToolExecutionModeEnum.BLOCKING, Duration.ofSeconds(30),
                0, Duration.ZERO, 4096, false, false);
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    public Set<String> getCompatibleVersions() {
        return compatibleVersions;
    }

    @Override
    public CompletionStage<ToolResult<DynamicToolResponse>> execute(ToolInvocation<DynamicToolRequest> invocation) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String input = objectMapper.writeValueAsString(invocation.request().arguments());
                String rawOutput = callback.call(input, new ToolContext(toSpringContext(invocation)));
                Object output = parseOutput(rawOutput);
                return new ToolResult.Succeeded<>(
                        new DynamicToolResponse(output, rawOutput),
                        List.of(), List.of(), null,
                        Map.of("springAiTool", callback.getToolDefinition().name())
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Spring AI tool execution failed: "
                        + callback.getToolDefinition().name(), exception);
            }
        }, executor);
    }

    ToolCallback getCallback() {
        return callback;
    }

    private Map<String, Object> toSpringContext(ToolInvocation<DynamicToolRequest> invocation) {
        Map<String, Object> context = new LinkedHashMap<>(invocation.context().attributes());
        context.put("callId", invocation.callId());
        context.put("traceId", invocation.context().traceId());
        context.put("ownerId", invocation.context().principal().ownerId());
        context.put("subjectId", invocation.context().principal().subjectId());
        return Map.copyOf(context);
    }

    private Object parseOutput(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(rawOutput);
        } catch (Exception ignored) {
            return rawOutput;
        }
    }
}
