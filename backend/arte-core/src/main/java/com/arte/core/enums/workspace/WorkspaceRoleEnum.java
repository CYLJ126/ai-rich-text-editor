package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 空间治理角色
 * <p>
 * 不隐式授予私有正文读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum WorkspaceRoleEnum implements IEnum<Integer>, MyEnum<Integer> {
    OWNER(0, "负责人"),
    ADMIN(1, "管理员"),
    MEMBER(2, "成员"),
    GUEST(3, "访客");

    private final int value;
    private final String description;

    WorkspaceRoleEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
