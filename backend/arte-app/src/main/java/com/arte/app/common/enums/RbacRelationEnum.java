package com.arte.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * RBAC 关系类型
 *
 * @author zhangsc
 * @since 2025/2/25 20:33
 */
@Getter
public enum RbacRelationEnum implements IEnum<String>, MyEnum<String> {
    USER_TO_ROLE("user_to_role", "用户与角色的对应关系"),
    USER_TO_MENU("user_to_menu", "用户与菜单的对应关系"),
    USER_TO_OPERATION("user_to_operation", "用户与操作的对应关系"),
    ROLE_TO_MENU("role_to_menu", "角色与菜单的对应关系"),
    ROLE_TO_OPERATION("role_to_operation", "角色与操作的对应关系"),
    USER_TO_DEPARTMENT("user_to_department", "用户与部门的对应关系"),
    USER_TO_GROUP("user_to_group", "用户与用户组的对应关系");

    private final String value;
    private final String description;

    RbacRelationEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
