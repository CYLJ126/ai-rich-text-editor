package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具风险级别。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum ToolRiskLevelEnum implements IEnum<String>, MyEnum<String> {
    LOW("low", "低风险"),
    MEDIUM("medium", "中风险"),
    HIGH("high", "高风险"),
    CRITICAL("critical", "严重风险"),
    ;

    private final String value;
    private final String description;

    ToolRiskLevelEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
