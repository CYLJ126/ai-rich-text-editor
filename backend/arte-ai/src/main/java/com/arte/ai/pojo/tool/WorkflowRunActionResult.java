package com.arte.ai.pojo.tool;

/**
 * 工作流启动、取消或恢复操作结果；恢复令牌只在首次暂停时返回。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/28 ✾
 */
public record WorkflowRunActionResult(WorkflowRunView run, String resumeToken) {
}
