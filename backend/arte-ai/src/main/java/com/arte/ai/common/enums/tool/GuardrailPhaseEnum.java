package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * Guardrail 执行阶段。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum GuardrailPhaseEnum implements IEnum<String>, MyEnum<String> {
    DISCOVERY("discovery", "发现阶段"),
    INPUT("input", "输入阶段"),
    PRE_EXECUTION("pre-execution", "执行前阶段"),
    POST_EXECUTION("post-execution", "执行后阶段"),
    OUTPUT("output", "输出阶段");


    private final String value;
    private final String description;

    GuardrailPhaseEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
