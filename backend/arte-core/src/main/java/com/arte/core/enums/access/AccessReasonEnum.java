package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 授权判断原因
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum AccessReasonEnum implements IEnum<Integer>, MyEnum<Integer> {
    ALLOWED(0, "允许"),
    SUBJECT_INACTIVE(1, "主体失效"),
    MEMBERSHIP_REQUIRED(2, "缺少有效成员资格"),
    LIFECYCLE_DENIED(3, "状态不允许"),
    POLICY_DENIED(4, "政策禁止"),
    NOT_FOUND(5, "不存在或不可见"),
    ACTION_NOT_GRANTED(6, "动作未授权"),
    UNKNOWN_ACTION(7, "动作未登记"),
    DOMAIN_DENIED(8, "领域条件不满足"),
    AUTHORITY_UNAVAILABLE(9, "无法确认当前授权");

    private final int value;
    private final String description;

    AccessReasonEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }
}
