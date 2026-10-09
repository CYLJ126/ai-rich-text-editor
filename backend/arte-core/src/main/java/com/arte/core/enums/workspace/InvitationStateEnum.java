package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 邀请状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum InvitationStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    PENDING(0, "待接受"),
    ACCEPTED(1, "已接受"),
    REVOKED(2, "已撤销"),
    EXPIRED(3, "已过期");

    private final Integer value;
    private final String description;

    InvitationStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
