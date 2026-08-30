package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 推理力度
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 17:09 ✾
 **/
@Getter
public enum ReasoningEffortEnum implements IEnum<String>, MyEnum<String> {
    NONE("none", "无"),
    MINIMAL("minimal", "最小"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    MAX("max", "最大");

    private final String value;
    private final String description;

    ReasoningEffortEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
