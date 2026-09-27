package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.GuardrailActionEnum;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.pojo.tool.DynamicToolRequest;
import com.arte.ai.pojo.tool.GuardrailContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 按受信上下文开关对顶层字符串参数进行去除首尾空白转换。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@Order(200)
public class ParameterNormalizationGuardrail implements ToolGuardrail {
    @Override
    public String getName() {
        return "parameter-normalization";
    }

    @Override
    public Set<GuardrailPhaseEnum> getSupportedPhases() {
        return Set.of(GuardrailPhaseEnum.INPUT);
    }

    @Override
    public CompletionStage<GuardrailDecision> evaluate(GuardrailContext context) {
        boolean enabled = Boolean.TRUE.equals(context.invocation().context().attributes()
                .get("tool.normalize-strings"));
        if (!enabled || !(context.invocation().request() instanceof DynamicToolRequest request)) {
            return CompletableFuture.completedFuture(new GuardrailDecision.Allowed("normalization not requested"));
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        request.arguments().forEach((key, value) -> {
            if (value instanceof String text && !text.equals(text.trim())) changes.put(key, text.trim());
        });
        return CompletableFuture.completedFuture(changes.isEmpty()
                ? new GuardrailDecision.Allowed("arguments already normalized")
                : new GuardrailDecision.Changed(GuardrailActionEnum.TRANSFORM,
                "trimmed string arguments", changes));
    }
}
