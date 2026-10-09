package com.arte.core.enums.platform;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 命令回执返回方式
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum CommandDeliveryEnum implements IEnum<Integer>, MyEnum<Integer> {
    APPLIED(0, "首次提交"),
    REPLAYED(1, "返回原回执");

    private final int value;
    private final String description;

    CommandDeliveryEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
