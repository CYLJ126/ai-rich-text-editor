package com.arte.core.enums.access;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 授权来源
 * <p>
 * 不同来源独立保存。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum GrantSourceEnum implements IEnum<Integer>, MyEnum<Integer> {
    CREATION(0, "资源创建"),
    SHARE(1, "分享"),
    MANAGEMENT(2, "资源授权管理"),
    GOVERNANCE(3, "治理委派"),
    COLLECTION(4, "集合批量授权");

    private final Integer value;
    private final String description;

    GrantSourceEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
