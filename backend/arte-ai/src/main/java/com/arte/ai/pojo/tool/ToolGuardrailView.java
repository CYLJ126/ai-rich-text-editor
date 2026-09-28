package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 当前执行管道中的服务端 Guardrail 只读视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record ToolGuardrailView(
        String name,
        int order,
        List<String> phases,
        String implementation,
        boolean serverEnforced
) {
    public ToolGuardrailView {
        phases = phases == null ? List.of() : List.copyOf(phases);
    }
}
