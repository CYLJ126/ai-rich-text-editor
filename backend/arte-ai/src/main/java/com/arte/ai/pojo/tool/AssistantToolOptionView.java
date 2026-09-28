package com.arte.ai.pojo.tool;

/**
 * 当前用户可配置工具的 AI 助手精简视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record AssistantToolOptionView(
        Integer assistantId,
        String name,
        String description,
        String avatar,
        boolean enabled
) {
}
