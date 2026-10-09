package com.arte.core.enums.platform;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 主体当前状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum PrincipalStatusEnum implements IEnum<Integer>, MyEnum<Integer> {
    ACTIVE(0, "有效"),
    SUSPENDED(1, "停用"),
    DELETED(2, "注销");

    private final int value;
    private final String description;

    PrincipalStatusEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
