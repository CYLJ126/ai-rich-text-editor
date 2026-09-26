package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具内容块类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum ToolContentTypeEnum implements IEnum<String>, MyEnum<String> {
    TEXT("text", "文本"),
    STRUCTURED("structured", "结构化数据"),
    IMAGE("image", "图像"),
    AUDIO("audio", "音频"),
    RESOURCE_LINK("resource-link", "资源链接"),
    ;

    private final String value;
    private final String description;

    ToolContentTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
