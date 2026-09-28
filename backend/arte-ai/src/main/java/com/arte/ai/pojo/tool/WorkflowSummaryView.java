package com.arte.ai.pojo.tool;

import java.time.Instant;

/**
 * 当前用户工作流目录视图。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowSummaryView(String workflowId, String name, String description,
                                  String latestVersion, String lifecycleState,
                                  Instant createTime, Instant updateTime) {
}
