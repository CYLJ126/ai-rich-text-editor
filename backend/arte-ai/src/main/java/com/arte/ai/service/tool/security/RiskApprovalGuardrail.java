package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;
import com.arte.ai.pojo.tool.GuardrailContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 对高风险、破坏性或有副作用的工具强制要求人工审批。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@Order(300)
public class RiskApprovalGuardrail implements ToolGuardrail {
    @Override
    public String getName() {
        return "risk-approval";
    }

    @Override
    public Set<GuardrailPhaseEnum> getSupportedPhases() {
        return Set.of(GuardrailPhaseEnum.PRE_EXECUTION);
    }

    @Override
    public CompletionStage<GuardrailDecision> evaluate(GuardrailContext context) {
        var risk = context.definition().riskProfile();
        boolean required = risk.destructive() || !risk.readOnly()
                || risk.level().ordinal() >= ToolRiskLevelEnum.HIGH.ordinal();
        return CompletableFuture.completedFuture(required
                ? new GuardrailDecision.ApprovalRequired("risk profile requires approval", Map.of())
                : new GuardrailDecision.Allowed("risk profile permits automatic execution"));
    }
}
