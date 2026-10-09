package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 租户治理角色
 * <p>
 * 不隐式继承到空间。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum TenantRoleEnum implements IEnum<Integer>, MyEnum<Integer> {
    OWNER(0, "负责人"),
    ADMIN(1, "管理员"),
    MEMBER(2, "成员"),
    GUEST(3, "访客");

    private final Integer value;
    private final String description;

    TenantRoleEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
