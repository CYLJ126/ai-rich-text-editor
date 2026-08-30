package com.arte.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 时间维度
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/22 18:34 ✾
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
@Getter
public enum DwStatisticsEnum implements IEnum<String>, MyEnum<String> {
    DAILY("daily", "每日"),
    WEEKLY("weekly", "每周"),
    MONTHLY("monthly", "每月"),
    QUARTERLY("quarterly", "每季"),
    SEMIANNUAL("semiannual", "每半年"),
    ANNUAL("annual", "每年");

    private final String value;
    private final String description;

    DwStatisticsEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
