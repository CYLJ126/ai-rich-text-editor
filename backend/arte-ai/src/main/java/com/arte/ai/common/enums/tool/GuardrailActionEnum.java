package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * Guardrail 决策动作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum GuardrailActionEnum implements IEnum<String>, MyEnum<String> {
    ALLOW("allow", "允许"),
    DENY("deny", "拒绝"),
    REDACT("redact", "脱敏"),
    TRANSFORM("transform", "转换"),
    REQUIRE_APPROVAL("require-approval", "需要审批"),
    ;

    private final String value;
    private final String description;

    GuardrailActionEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
