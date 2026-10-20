package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

/**
 * 本地免费执行的结构下限；实际工具白名单、资源权限和领域防重仍由宿主验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:48 ✾
 */
public final class ToolExecutionRules {

    private ToolExecutionRules() {
    }

    public static boolean supported(ToolRiskProfile risk, ToolExecutionPolicy policy) {
        return !risk.destructive() && (risk.sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY
                || risk.sideEffect() == CapabilityDescriptor.SideEffect.RESOURCE_WRITE
                && risk.idempotency() == ToolRiskProfile.Idempotency.DOMAIN_OPERATION_KEY
                && policy.approvalMode().satisfies(ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION));
    }

    /**
     * 只读结果不得声称已写入；写入成功必须报告已确认副作用，未知事实不能被降为普通失败。
     */
    public static boolean resultAllowed(ToolRiskProfile risk, ToolResult result) {
        if (result.outcome() == ToolResult.Outcome.UNKNOWN) return true;
        if (risk.sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY)
            return result.sideEffect() == ExecutionError.SideEffect.NONE;
        if (risk.sideEffect() != CapabilityDescriptor.SideEffect.RESOURCE_WRITE) return false;
        return result.outcome() == ToolResult.Outcome.SUCCEEDED
                ? result.sideEffect() == ExecutionError.SideEffect.CONFIRMED
                : result.sideEffect() != ExecutionError.SideEffect.POSSIBLE;
    }
}
