package com.nip.app.common.enums.richtext;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 文章类型枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:30 ✾
 **/
@Getter
public enum ArticleTypeEnum implements IEnum<String>, MyEnum<String> {
    NOTE("note", "笔记"),
    BLOG("blog", "博客"),
    NOVEL("novel", "小说"),
    ESSAY("essay", "散文"),
    GENERIC("generic", "通用类型");

    private final String value;
    private final String description;

    ArticleTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
