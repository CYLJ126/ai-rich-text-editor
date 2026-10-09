package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 资源访问模式
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum ResourceVisibilityEnum implements IEnum<Integer>, MyEnum<Integer> {
    EXPLICIT(0, "按具体授权"),
    SPACE_SHARED(1, "按明确空间共享授权");

    private final Integer value;
    private final String description;

    ResourceVisibilityEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
