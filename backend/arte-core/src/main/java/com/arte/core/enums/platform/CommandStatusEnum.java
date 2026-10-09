package com.arte.core.enums.platform;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 公共命令状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum CommandStatusEnum implements IEnum<Integer>, MyEnum<Integer> {
    PENDING(0, "已受理，待完成"),
    APPLIED(1, "已提交");

    private final Integer value;
    private final String description;

    CommandStatusEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
