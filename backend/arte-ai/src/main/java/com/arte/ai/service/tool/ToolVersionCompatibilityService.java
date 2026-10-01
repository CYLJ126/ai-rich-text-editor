package com.arte.ai.service.tool;

import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.po.ToolVersionPo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * 显式兼容声明仍须通过契约和权限检查，版本号本身不代表兼容。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
@Service
@RequiredArgsConstructor
public class ToolVersionCompatibilityService {
    private final ObjectMapper objectMapper;

    public List<String> problems(ToolVersionPo base, ToolVersionPo next) {
        List<String> problems = new ArrayList<>();
        if (!Objects.equals(base.getToolId(), next.getToolId())) problems.add("工具身份不同");
        if (!same(base.getInputSchema(), next.getInputSchema())) problems.add("输入参数契约发生变化");
        if (!same(base.getOutputSchema(), next.getOutputSchema())) problems.add("输出契约发生变化");
        if (!same(base.getDefaultConfiguration(), next.getDefaultConfiguration())) problems.add("默认配置发生变化");
        if (!same(normalizedCapabilities(base.getCapabilities()), normalizedCapabilities(next.getCapabilities())))
            problems.add("工具能力发生变化");
        Map<String, Object> oldPolicy = normalizedPolicy(base.getDefaultPolicy());
        Map<String, Object> newPolicy = normalizedPolicy(next.getDefaultPolicy());
        if (!Boolean.TRUE.equals(oldPolicy.get("requiresApproval"))
                && Boolean.TRUE.equals(newPolicy.get("requiresApproval"))) problems.add("新增人工审批要求");
        Map<String, Object> oldExecution = new HashMap<>(oldPolicy);
        Map<String, Object> newExecution = new HashMap<>(newPolicy);
        oldExecution.remove("requiresApproval");
        newExecution.remove("requiresApproval");
        if (!same(oldExecution, newExecution)) problems.add("执行策略发生变化");
        Map<String, Object> oldRisk = map(base.getRiskProfile());
        Map<String, Object> newRisk = map(next.getRiskProfile());
        if (level(newRisk.get("level")) > level(oldRisk.get("level"))) problems.add("风险级别提高");
        for (String flag : List.of("destructive", "openWorld")) {
            if (!Boolean.TRUE.equals(oldRisk.get(flag)) && Boolean.TRUE.equals(newRisk.get(flag))) {
                problems.add("新增风险能力：" + flag);
            }
        }
        for (String flag : List.of("readOnly", "reversible", "idempotent")) {
            if (Boolean.TRUE.equals(oldRisk.get(flag)) && !Boolean.TRUE.equals(newRisk.get(flag))) {
                problems.add("降低行为保证：" + flag);
            }
        }
        for (String scope : List.of("requiredScopes", "allowedNetworkTargets")) {
            if (!strings(oldRisk.get(scope)).containsAll(strings(newRisk.get(scope)))) {
                problems.add("扩大权限或网络范围：" + scope);
            }
        }
        return List.copyOf(problems);
    }

    public void requireReleaseBaseline(ToolVersionPo base, ToolVersionPo next) {
        if (base.getLifecycleState() != com.arte.ai.common.enums.tool.ToolLifecycleStateEnum.PUBLISHED
                || Objects.equals(base.getVersion(), next.getVersion())) {
            throw new IllegalArgumentException("兼容基准必须是另一个已发布版本");
        }
        if (next.getLifecycleState() == com.arte.ai.common.enums.tool.ToolLifecycleStateEnum.PUBLISHED) {
            if (base.getPublishedAt() == null || next.getPublishedAt() == null
                    || base.getPublishedAt().isAfter(next.getPublishedAt())
                    || (base.getPublishedAt().equals(next.getPublishedAt())
                    && (base.getId() == null || next.getId() == null || base.getId() >= next.getId()))) {
                throw new IllegalArgumentException("已发布版本只能兼容更早发布的基准，不能建立反向或循环升级关系");
            }
        }
    }

    public void requireCompatible(ToolVersionPo base, ToolVersionPo next) {
        List<String> problems = problems(base, next);
        if (!problems.isEmpty())
            throw new IllegalArgumentException("无法作为兼容升级发布：" + String.join("；", problems));
    }

    public boolean follows(String baseline, ToolVersionPo candidate, List<ToolVersionPo> versions) {
        Map<String, ToolVersionPo> byVersion = new HashMap<>();
        versions.forEach(version -> byVersion.put(version.getVersion(), version));
        Set<String> visited = new HashSet<>();
        ToolVersionPo current = candidate;
        while (current != null && visited.add(current.getVersion())) {
            if (baseline.equals(current.getVersion())) return true;
            ToolVersionPo parent = byVersion.get(current.getCompatibilityBaseVersion());
            if (parent == null || !problems(parent, current).isEmpty()) return false;
            current = parent;
        }
        return false;
    }

    public Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizedPolicy(Map<String, Object> value) {
        // 历史数据可以使用 ISO Duration 或毫秒字段，两种表示具有相同语义。
        return objectMapper.convertValue(new DefaultToolPolicyMerger().decodePolicy(value), Map.class);
    }

    private Map<String, Object> normalizedCapabilities(Map<String, Object> value) {
        Map<String, Object> result = new HashMap<>(map(value));
        for (String key : List.of("executionModes", "inputModes", "outputModes")) {
            if (result.get(key) instanceof Collection<?>) result.put(key, new TreeSet<>(strings(result.get(key))));
        }
        return result;
    }

    private boolean same(Object first, Object second) {
        return objectMapper.valueToTree(first == null ? Map.of() : first)
                .equals(objectMapper.valueToTree(second == null ? Map.of() : second));
    }

    private int level(Object value) {
        for (ToolRiskLevelEnum level : ToolRiskLevelEnum.values()) {
            if (level.getValue().equalsIgnoreCase(String.valueOf(value))
                    || level.name().equalsIgnoreCase(String.valueOf(value))) return level.ordinal();
        }
        throw new IllegalArgumentException("unknown risk level: " + value);
    }

    private Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> values)) return Set.of();
        Set<String> result = new HashSet<>();
        values.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
