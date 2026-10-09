package com.arte.core.enums.platform;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 可信主体类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum PrincipalKindEnum implements IEnum<Integer>, MyEnum<Integer> {
    USER(0, "用户"),
    SERVICE(1, "系统服务");

    private final Integer value;
    private final String description;

    PrincipalKindEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
