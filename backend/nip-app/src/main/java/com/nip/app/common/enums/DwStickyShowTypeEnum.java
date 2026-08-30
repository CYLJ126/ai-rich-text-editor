package com.nip.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 便笺展现形式
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/22 18:34 ✾
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
@Getter
public enum DwStickyShowTypeEnum implements IEnum<String>, MyEnum<String> {
    TEXT("text", "纯文本原生类型"),
    LIST("list", "列表类型");

    private final String value;
    private final String description;

    DwStickyShowTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
