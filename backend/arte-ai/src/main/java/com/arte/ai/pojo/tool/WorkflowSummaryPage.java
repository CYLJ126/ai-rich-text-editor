package com.arte.ai.pojo.tool;

import java.util.List;

/**
 * 当前用户工作流目录分页结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowSummaryPage(List<WorkflowSummaryView> records, long total,
                                  int current, int size) {
    public WorkflowSummaryPage {
        records = records == null ? List.of() : List.copyOf(records);
    }
}
