package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 受限收尾授权状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum CleanupAuthorizationStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    ACTIVE(0, "有效"),
    REVOKED(1, "已撤销");

    private final Integer value;
    private final String description;

    CleanupAuthorizationStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
