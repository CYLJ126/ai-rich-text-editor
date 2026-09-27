package com.arte.ai.pojo.tool;

/**
 * AI 助手工具关联配置命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record AssistantToolCommand(
        String bindingId,
        boolean enabled,
        int sortOrder,
        ToolPolicyOverride policyOverride
) {

    public AssistantToolCommand {
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalArgumentException("bindingId must not be blank");
        }
        if (sortOrder < 0) {
            throw new IllegalArgumentException("sortOrder must not be negative");
        }
    }
}
