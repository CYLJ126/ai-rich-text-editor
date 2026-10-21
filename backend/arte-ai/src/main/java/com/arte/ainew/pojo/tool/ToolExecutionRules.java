package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.control.CapabilityDescriptor;

/**
 * 本地及受控远端工具执行的结构下限；实际工具白名单、资源权限和领域防重仍由宿主验证。
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
     * 受控远端单次只读调用的结构约束；具体协议、认证及免费资格由适配器与宿主另行核验。
     */
    public static boolean remoteReadOnly(ToolDefinition tool, ToolExecutionPolicy policy) {
        return tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY && !tool.risk().destructive()
                && tool.risk().idempotency() == ToolRiskProfile.Idempotency.NONE
                && policy.approvalMode() == ToolExecutionPolicy.ApprovalMode.NONE && policy.maxAttempts() == 1
                && tool.risk().allowedNetworkOrigins().size() == 1
                && tool.capabilities().features().isEmpty() && !tool.capabilities().outcomeQuerySupported();
    }

    public static boolean supportedTarget(ToolBindingResolution binding, ToolExecutionPolicy policy) {
        var tool = binding.tool().tool();
        if (!binding.secrets().isEmpty()) return false;
        if (binding.tool().target() instanceof com.arte.ainew.pojo.control.ExecutionTarget.Local)
            return binding.networkOrigins().isEmpty() && tool.risk().allowedNetworkOrigins().isEmpty();
        return binding.tool().target() instanceof com.arte.ainew.pojo.control.ExecutionTarget.Remote
                && remoteReadOnly(tool, policy) && binding.networkOrigins().equals(tool.risk().allowedNetworkOrigins());
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
