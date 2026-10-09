package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 允许授权记录状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum GrantStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    ACTIVE(0, "有效"),
    REVOKED(1, "已撤销");

    private final Integer value;
    private final String description;

    GrantStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
