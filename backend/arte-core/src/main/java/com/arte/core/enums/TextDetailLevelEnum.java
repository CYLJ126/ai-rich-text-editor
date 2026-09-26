package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 内容详细程度
 * 一般结合两个维度组合：内容格式{@link TextFormatEnum} + 详细程度{@link TextDetailLevelEnum}
 * 如 Agent 调用工具时可指定此参数，用于向智能体返回高信号的信息
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 10:22 ✾
 **/
@Getter
public enum TextDetailLevelEnum implements IEnum<String>, MyEnum<String> {
    MINIMAL("minimal", "最小信息，仅返回必要结果"),
    BRIEF("brief", "简要信息，返回核心结果及必要上下文"),
    STANDARD("standard", "标准信息，返回常规结果及上下文，默认级别"),
    DETAILED("detailed", "详细信息，返回更多相关细节"),
    FULL("full", "完整信息，尽可能返回全部可用内容");

    private final String value;
    private final String description;

    TextDetailLevelEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static TextDetailLevelEnum getDefaultLevel() {
        return STANDARD;
    }

}
