package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 接受邀请结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum JoinOutcomeEnum implements IEnum<Integer>, MyEnum<Integer> {
    JOINED(0, "已加入"),
    ALREADY_MEMBER(1, "已经是成员");

    private final Integer value;
    private final String description;

    JoinOutcomeEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
