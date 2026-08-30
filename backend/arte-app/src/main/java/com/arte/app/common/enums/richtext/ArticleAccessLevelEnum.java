package com.arte.app.common.enums.richtext;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 文章访问等级枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:30 ✾
 **/
@Getter
public enum ArticleAccessLevelEnum implements IEnum<String>, MyEnum<String> {
    PUBLIC("public", "公开"),
    PRIVATE("private", "私有"),
    TEAM("team", "团队");

    private final String value;
    private final String description;

    ArticleAccessLevelEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
