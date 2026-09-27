package com.arte.ai.pojo.tool;

import java.util.Map;

/**
 * 助手实际可使用的固定版本工具定义。
 *
 * <p>modelDefinition 只包含允许暴露给模型的名称、描述和输入 Schema；凭据、配置和策略
 * 仅保留在受信执行侧。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ResolvedAssistantTool(
        String modelToolName,
        ToolReference tool,
        String bindingId,
        int sortOrder,
        org.springframework.ai.tool.definition.ToolDefinition modelDefinition,
        Map<String, Object> effectiveConfiguration,
        String credentialReference,
        ToolExecutionPolicy effectivePolicy
) {

    public ResolvedAssistantTool {
        effectiveConfiguration = effectiveConfiguration == null
                ? Map.of() : Map.copyOf(effectiveConfiguration);
    }
}
