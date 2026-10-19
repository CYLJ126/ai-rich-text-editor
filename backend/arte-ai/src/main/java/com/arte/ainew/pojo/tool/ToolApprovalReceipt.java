package com.arte.ainew.pojo.tool;

import com.arte.ainew.pojo.execution.Invocation;

import java.util.Objects;

/**
 * 重放返回原决定和原排队／完成快照，不因后续执行状态变化重写历史回执。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public record ToolApprovalReceipt(ToolApproval approval, Invocation invocation) {

    public ToolApprovalReceipt {
        Objects.requireNonNull(approval);
        Objects.requireNonNull(invocation);
        if (!approval.invocationId().equals(invocation.request().context().executionId()))
            throw new IllegalArgumentException("Approval receipt invocation mismatch");
    }
}
