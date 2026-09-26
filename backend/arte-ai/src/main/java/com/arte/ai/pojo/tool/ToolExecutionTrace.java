package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 一次工具调用的完整不可变轨迹快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolExecutionTrace(String traceId, String callId, List<ToolExecutionEvent> events) {

    public ToolExecutionTrace {
        requireText(traceId, "traceId");
        requireText(callId, "callId");
        events = events == null ? List.of() : List.copyOf(events);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
