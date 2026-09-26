package com.arte.ai.api.tool.security;

import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.pojo.tool.GuardrailContext;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 可组合的工具 Guardrail。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public interface ToolGuardrail {

    String getName();

    Set<GuardrailPhaseEnum> getSupportedPhases();

    CompletionStage<GuardrailDecision> evaluate(GuardrailContext context);
}
