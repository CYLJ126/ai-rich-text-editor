package com.arte.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 交互类型枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum InterActionTypeEnum implements IEnum<String>, MyEnum<String> {
    FRONTEND("frontend", "前端直连大模型，通过后端中转保存数据"),
    BACKEND("backend", "后端代理大模型，流式响应给前端");

    private final String value;
    private final String description;

    InterActionTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
