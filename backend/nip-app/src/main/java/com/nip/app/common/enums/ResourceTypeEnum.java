package com.nip.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 分享资源类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/24
 */
@Getter
public enum ResourceTypeEnum implements IEnum<String>, MyEnum<String> {
    CATALOG("CATALOG", "目录"),
    ARTICLE("ARTICLE", "文章");

    private final String value;
    private final String description;

    ResourceTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static ResourceTypeEnum of(String value) {
        for (ResourceTypeEnum item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return null;
    }

    public static boolean isValid(String value) {
        return of(value) != null;
    }
}
