package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 允许授权的目标类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum GrantTargetKindEnum implements IEnum<Integer>, MyEnum<Integer> {
    SUBJECT(0, "具体主体"),
    SPACE_ROLE(1, "空间角色"),
    WORKSPACE(2, "目标空间");

    private final int value;
    private final String description;

    GrantTargetKindEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
