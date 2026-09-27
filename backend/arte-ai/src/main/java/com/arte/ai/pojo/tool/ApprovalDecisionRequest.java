package com.arte.ai.pojo.tool;

/**
 * 人工审批操作请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public record ApprovalDecisionRequest(boolean approved, String reason) {
}
