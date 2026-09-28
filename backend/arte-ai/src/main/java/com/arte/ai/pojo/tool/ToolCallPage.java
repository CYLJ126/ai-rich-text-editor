package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 当前用户工具调用明细分页结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record ToolCallPage(
        List<ToolCallDetail> records,
        long total,
        int current,
        int size
) {
    public ToolCallPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
