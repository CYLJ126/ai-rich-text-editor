package com.arte.core.enums.workspace;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 关闭操作及工作项状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum ClosureWorkStateEnum implements IEnum<Integer>, MyEnum<Integer> {
    PENDING(0, "待处理"),
    RUNNING(1, "处理中"),
    WAITING_EVIDENCE(2, "等待证据"),
    DONE(3, "已完成"),
    FAILED(4, "处理失败");

    private final Integer value;
    private final String description;

    ClosureWorkStateEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
