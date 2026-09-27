package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 替换助手工具集合的命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record AssistantToolConfigurationCommand(
        String workspaceId,
        List<AssistantToolCommand> tools
) {

    public AssistantToolConfigurationCommand {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
