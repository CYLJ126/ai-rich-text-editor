package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.ToolLifecycleStateEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.mapper.tool.ToolMapper;
import com.arte.ai.mapper.tool.ToolVersionMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import com.arte.ai.service.tool.provider.SpringAiToolAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletionStage;

/**
 * 只用明确声明且通过契约检查的 Spring AI 执行器承接历史版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolRuntimeVersionLoader {
    private final ToolMapper toolMapper;
    private final ToolVersionMapper versionMapper;
    private final ToolAvailabilityService availability;
    private final ToolVersionCompatibilityService compatibility;
    private final ToolPolicyMerger policyMerger;
    private final ObjectMapper objectMapper;

    public List<Tool<?, ?>> load(ToolProvider provider) {
        Map<ToolReference, Tool<?, ?>> result = new LinkedHashMap<>();
        List<ToolDefinition> definitions = provider.listDefinitions();
        // 原生工具实现优先；不会用新版执行器覆盖仍然存在的旧版实现。
        for (ToolDefinition definition : definitions) {
            if (availability.isAvailable(definition.reference())) {
                provider.resolve(definition.reference()).ifPresent(tool -> result.put(definition.reference(), tool));
            }
        }
        for (ToolDefinition definition : definitions) {
            Tool<?, ?> raw = provider.resolve(definition.reference()).orElse(null);
            if (!(raw instanceof SpringAiToolAdapter current)) continue;
            var catalog = toolMapper.selectByIdentity(definition.reference().namespace(), definition.reference().name());
            if (catalog.isEmpty()) continue;
            List<ToolVersionPo> versions = versionMapper.selectVersions(catalog.get().getToolId());
            ToolVersionPo currentVersion = versions.stream()
                    .filter(version -> version.getVersion().equals(definition.reference().version())).findFirst().orElse(null);
            if (currentVersion == null || currentVersion.getLifecycleState() == ToolLifecycleStateEnum.DISABLED
                    || currentVersion.getLifecycleState() == ToolLifecycleStateEnum.DEPRECATED) continue;
            ToolVersionPo runtimeVersion = runtimeSnapshot(catalog.get().getToolId(), definition);
            if (!compatibility.problems(currentVersion, runtimeVersion).isEmpty()
                    || !compatibility.problems(runtimeVersion, currentVersion).isEmpty()) {
                throw new IllegalStateException("运行时定义与版本快照不一致，请同步提供者并使用新版本号：" + definition.reference());
            }
            for (ToolVersionPo old : versions) {
                ToolReference reference = new ToolReference(definition.reference().namespace(), definition.reference().name(), old.getVersion());
                boolean declared = current.getCompatibleVersions().contains(old.getVersion())
                        || (currentVersion.getLifecycleState() == ToolLifecycleStateEnum.PUBLISHED
                        && compatibility.follows(old.getVersion(), currentVersion, versions));
                if (!declared || result.containsKey(reference) || !availability.isAvailable(reference)
                        || !compatibility.problems(old, currentVersion).isEmpty()) continue;
                ToolDefinition snapshot = snapshot(reference, old, definition);
                result.put(reference, new HistoricalTool(snapshot, current));
            }
        }
        return List.copyOf(result.values());
    }

    @SuppressWarnings("unchecked")
    private ToolVersionPo runtimeSnapshot(String toolId, ToolDefinition definition) {
        return new ToolVersionPo().setToolId(toolId)
                .setInputSchema(objectMapper.readValue(definition.inputSchema().schema(), Map.class))
                .setOutputSchema(objectMapper.readValue(definition.outputSchema().schema(), Map.class))
                .setCapabilities(objectMapper.convertValue(definition.capabilities(), Map.class))
                .setDefaultConfiguration(definition.defaultConfiguration())
                .setDefaultPolicy(objectMapper.convertValue(definition.defaultPolicy(), Map.class))
                .setRiskProfile(objectMapper.convertValue(definition.riskProfile(), Map.class));
    }

    private ToolDefinition snapshot(ToolReference reference, ToolVersionPo old, ToolDefinition current) {
        Map<String, Object> risk = old.getRiskProfile();
        ToolRiskLevelEnum level = Arrays.stream(ToolRiskLevelEnum.values())
                .filter(value -> value.getValue().equals(String.valueOf(risk.get("level"))) || value.name().equals(String.valueOf(risk.get("level"))))
                .findFirst().orElseThrow();
        return new ToolDefinition(reference, old.getTitle(), old.getDescription(),
                new ToolSchema(current.inputSchema().dialect(), objectMapper.writeValueAsString(old.getInputSchema())),
                new ToolSchema(current.outputSchema().dialect(), objectMapper.writeValueAsString(old.getOutputSchema())),
                current.capabilities(), new ToolRiskProfile(level, bool(risk, "readOnly"), bool(risk, "destructive"),
                bool(risk, "reversible"), bool(risk, "idempotent"), bool(risk, "openWorld"),
                strings(risk.get("requiredScopes")), strings(risk.get("allowedNetworkTargets"))),
                old.getDefaultConfiguration(), policyMerger.decodePolicy(old.getDefaultPolicy()), old.getTags(), current.deprecated());
    }

    private boolean bool(Map<String, Object> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return Set.of();
        Set<String> result = new HashSet<>();
        values.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }

    private record HistoricalTool(ToolDefinition definition, SpringAiToolAdapter executor)
            implements Tool<DynamicToolRequest, DynamicToolResponse> {
        @Override
        public ToolDefinition getDefinition() {
            return definition;
        }

        @Override
        public CompletionStage<ToolResult<DynamicToolResponse>> execute(ToolInvocation<DynamicToolRequest> invocation) {
            return executor.execute(invocation);
        }
    }
}
