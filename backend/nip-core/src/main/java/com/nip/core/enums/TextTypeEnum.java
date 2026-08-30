package com.nip.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 文本格式
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 17:21 ✾
 */
@Getter
public enum TextTypeEnum implements IEnum<String>, MyEnum<String> {
    RAW("raw", "原始文本"), // 字符串形式的内容，比如带 markdown 格式的字符串
    MARKDOWN("markdown", "Markdown 格式"),
    JSON("json", "JSON 格式"),
    HTML("html", "HTML 格式"),
    PLAIN("plain", "纯文本"), // 正常人们阅读的文本，不会有格式符号
    ;

    private final String value;
    private final String description;

    TextTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

}
