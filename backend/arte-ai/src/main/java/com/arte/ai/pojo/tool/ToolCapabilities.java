package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolExecutionModeEnum;

import java.util.Set;

/**
 * 工具支持的运行能力，用于工具选择、页面展示和编排前校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolCapabilities(
        boolean supportsStreaming,
        Set<ToolExecutionModeEnum> executionModes,
        boolean supportsCancellation,
        boolean supportsDryRun,
        Set<String> inputModes,
        Set<String> outputModes
) {

    public ToolCapabilities {
        executionModes = executionModes == null ? Set.of() : Set.copyOf(executionModes);
        inputModes = inputModes == null ? Set.of() : Set.copyOf(inputModes);
        outputModes = outputModes == null ? Set.of() : Set.copyOf(outputModes);
        if (executionModes.isEmpty()) {
            throw new IllegalArgumentException("executionModes must not be empty");
        }
    }
}
