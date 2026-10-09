package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 租户类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum TenantTypeEnum implements IEnum<Integer>, MyEnum<Integer> {
    PERSONAL(0, "个人托管"),
    ORGANIZATION(1, "组织");

    private final Integer value;
    private final String description;

    TenantTypeEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
