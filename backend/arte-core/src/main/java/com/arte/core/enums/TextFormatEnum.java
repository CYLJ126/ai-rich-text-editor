package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 文本格式
 * <p>
 * 用于指定文本内容的表示格式，可与 {@link TextDetailLevelEnum} 组合使用：文本格式决定“如何返回”，详细程度决定“返回多少”。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/17 17:21 ✾
 */
@Getter
public enum TextFormatEnum implements IEnum<String>, MyEnum<String> {
    RAW("raw", "原始文本，不进行格式转换或处理"), // 字符串形式的内容，比如带 markdown 格式的字符串
    PLAIN("plain", "纯文本，不包含格式标记"), // 正常人们阅读的文本，不会有格式符号
    MARKDOWN("markdown", "Markdown 格式"),
    JSON("json", "Json 格式"),
    HTML("html", "Html 格式"),
    ;

    private final String value;
    private final String description;

    TextFormatEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

}
