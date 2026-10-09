package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 通用资源归属
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum ResourceOwnershipTypeEnum implements IEnum<Integer>, MyEnum<Integer> {
    TENANT(0, "租户"),
    WORKSPACE(1, "工作空间");

    private final Integer value;
    private final String description;

    ResourceOwnershipTypeEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
