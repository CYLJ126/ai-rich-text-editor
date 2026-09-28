package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 当前用户工具审批分页结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record ToolApprovalPage(
        List<ToolApprovalView> records,
        long total,
        int current,
        int size
) {
    public ToolApprovalPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
