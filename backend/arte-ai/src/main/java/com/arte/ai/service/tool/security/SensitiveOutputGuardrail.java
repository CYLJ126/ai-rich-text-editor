package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.GuardrailActionEnum;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.pojo.tool.DynamicToolResponse;
import com.arte.ai.pojo.tool.GuardrailContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 对动态工具输出中的敏感字段做递归脱敏。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@Order(400)
public class SensitiveOutputGuardrail implements ToolGuardrail {
    private final ToolDataSanitizer sanitizer;

    public SensitiveOutputGuardrail(ToolDataSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    @Override
    public String getName() {
        return "sensitive-output";
    }

    @Override
    public Set<GuardrailPhaseEnum> getSupportedPhases() {
        return Set.of(GuardrailPhaseEnum.OUTPUT);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletionStage<GuardrailDecision> evaluate(GuardrailContext context) {
        if (!(context.result() instanceof ToolResult.Succeeded<?> succeeded)
                || !(succeeded.output() instanceof DynamicToolResponse response)
                || !(response.value() instanceof Map<?, ?> value)) {
            return CompletableFuture.completedFuture(new GuardrailDecision.Allowed("no structured sensitive output"));
        }
        Map<String, Object> source = new java.util.LinkedHashMap<>();
        value.forEach((key, item) -> source.put(String.valueOf(key), item));
        Map<String, Object> sanitized = sanitizer.sanitize(source);
        return CompletableFuture.completedFuture(source.equals(sanitized)
                ? new GuardrailDecision.Allowed("output contains no sensitive fields")
                : new GuardrailDecision.Changed(GuardrailActionEnum.REDACT,
                "sensitive output fields redacted", Map.of("value", sanitized)));
    }
}
