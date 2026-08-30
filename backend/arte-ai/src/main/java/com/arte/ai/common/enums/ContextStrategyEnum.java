package com.arte.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 上下文策略类型枚举
 * TODO 窗口和 token 下可选择是否开启自动摘要压缩
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum ContextStrategyEnum implements IEnum<String>, MyEnum<String> {
    WINDOW("window", "滑动窗口"),
    SUMMARY("summary", "自动摘要压缩"),
    TOKEN("token", "按 token 切分"),
    FULL("full", "全量上下文");

    private final String value;
    private final String description;

    ContextStrategyEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
