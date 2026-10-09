package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 空间状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum WorkspaceStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    ACTIVE(0, "正常"),
    ARCHIVED(1, "已归档"),
    CLOSING(2, "关闭处理中"),
    DELETED(3, "已软删除");

    private final Integer value;
    private final String description;

    WorkspaceStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
