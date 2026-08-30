package com.nip.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 标签类型枚举
 * 前后端代码修改均需保持一致
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/12/20 12:34 ✾
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
@Getter
public enum TagTypeEnum implements IEnum<String>, MyEnum<String> {
    DAILY_WORK("daily_work", "日课"),
    TRACE("trace", "时刻留痕"),
    NEWS("news", "新闻资讯"),
    STICKY("sticky", "便笺"),
    ARTICLE("article", "文章"),
    OTHER("other", "其他");

    private final String value;
    private final String description;

    TagTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
