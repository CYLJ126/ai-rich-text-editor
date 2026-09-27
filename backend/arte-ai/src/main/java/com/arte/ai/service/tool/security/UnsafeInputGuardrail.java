package com.arte.ai.service.tool.security;

import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.GuardrailPhaseEnum;
import com.arte.ai.pojo.tool.GuardrailContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 拒绝明显的脚本、JNDI 和路径穿越攻击载荷。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@Order(100)
public class UnsafeInputGuardrail implements ToolGuardrail {
    private final ObjectMapper objectMapper;

    public UnsafeInputGuardrail(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "unsafe-input";
    }

    @Override
    public Set<GuardrailPhaseEnum> getSupportedPhases() {
        return Set.of(GuardrailPhaseEnum.INPUT);
    }

    @Override
    public CompletionStage<GuardrailDecision> evaluate(GuardrailContext context) {
        String value;
        try {
            value = objectMapper.writeValueAsString(context.invocation().request()).toLowerCase();
        } catch (Exception exception) {
            return CompletableFuture.completedFuture(new GuardrailDecision.Denied("input is not serializable"));
        }
        boolean unsafe = value.contains("${jndi:") || value.contains("<script")
                || value.contains("../") || value.contains("..\\");
        return CompletableFuture.completedFuture(unsafe
                ? new GuardrailDecision.Denied("unsafe input pattern detected")
                : new GuardrailDecision.Allowed("input passed fixed security rules"));
    }
}
