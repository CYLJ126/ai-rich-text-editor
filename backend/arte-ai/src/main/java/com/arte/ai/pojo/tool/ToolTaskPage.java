package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 当前用户的异步工具任务分页结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record ToolTaskPage(
        List<ToolTaskHandle> records,
        long total,
        int current,
        int size
) {
    public ToolTaskPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
