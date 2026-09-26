package com.arte.ai.api.tool.security;

import com.arte.ai.common.enums.tool.GuardrailActionEnum;

import java.util.Map;
import java.util.Objects;

/**
 * Guardrail 决策。使用封闭结果类型避免无效的字段组合。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public sealed interface GuardrailDecision permits GuardrailDecision.Allowed,
        GuardrailDecision.Denied, GuardrailDecision.Changed, GuardrailDecision.ApprovalRequired {

    GuardrailActionEnum action();

    String reason();

    record Allowed(String reason) implements GuardrailDecision {

        @Override
        public GuardrailActionEnum action() {
            return GuardrailActionEnum.ALLOW;
        }
    }

    record Denied(String reason) implements GuardrailDecision {

        public Denied {
            Objects.requireNonNull(reason, "reason must not be null");
        }

        @Override
        public GuardrailActionEnum action() {
            return GuardrailActionEnum.DENY;
        }
    }

    /**
     * REDACT/TRANSFORM 后执行管道必须再次完成 Schema 校验。
     */
    record Changed(
            GuardrailActionEnum action,
            String reason,
            Map<String, Object> changes
    ) implements GuardrailDecision {

        public Changed {
            if (action != GuardrailActionEnum.REDACT && action != GuardrailActionEnum.TRANSFORM) {
                throw new IllegalArgumentException("changed action must be REDACT or TRANSFORM");
            }
            changes = Map.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
            if (changes.isEmpty()) {
                throw new IllegalArgumentException("changes must not be empty");
            }
        }
    }

    record ApprovalRequired(String reason, Map<String, Object> displayArguments) implements GuardrailDecision {

        public ApprovalRequired {
            Objects.requireNonNull(reason, "reason must not be null");
            displayArguments = displayArguments == null ? Map.of() : Map.copyOf(displayArguments);
        }

        @Override
        public GuardrailActionEnum action() {
            return GuardrailActionEnum.REQUIRE_APPROVAL;
        }
    }
}
