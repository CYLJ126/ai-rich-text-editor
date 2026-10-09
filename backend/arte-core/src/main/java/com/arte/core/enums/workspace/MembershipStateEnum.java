package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 成员状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum MembershipStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    ACTIVE(0, "有效"),
    REVOKED(1, "已撤销");

    private final int value;
    private final String description;

    MembershipStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
