package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.pojo.tool.GuardrailContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 在输出转换前确认工具返回了结构有效的成功结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@Order(350)
public class PostExecutionIntegrityGuardrail implements ToolGuardrail {
    @Override
    public String getName() {
        return "post-execution-integrity";
    }

    @Override
    public Set<GuardrailPhaseEnum> getSupportedPhases() {
        return Set.of(GuardrailPhaseEnum.POST_EXECUTION);
    }

    @Override
    public CompletionStage<GuardrailDecision> evaluate(GuardrailContext context) {
        boolean valid = context.result() instanceof ToolResult.Succeeded<?> succeeded
                && succeeded.output() != null;
        return CompletableFuture.completedFuture(valid
                ? new GuardrailDecision.Allowed("tool returned a valid success envelope")
                : new GuardrailDecision.Denied("tool returned an invalid success envelope"));
    }
}
