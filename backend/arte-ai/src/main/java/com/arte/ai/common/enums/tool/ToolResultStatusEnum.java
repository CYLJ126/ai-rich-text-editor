package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具执行结果状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum ToolResultStatusEnum implements IEnum<String>, MyEnum<String> {
    ACCEPTED("accepted", "已接受"),
    SUCCEEDED("succeeded", "执行成功"),
    FAILED("failed", "执行失败"),
    DENIED("denied", "已拒绝"),
    REQUIRES_APPROVAL("requires-approval", "等待审批"),
    PAUSED("paused", "已暂停"),
    CANCELLED("cancelled", "已取消"),
    TIMED_OUT("timed-out", "已超时"),
    ;

    private final String value;
    private final String description;

    ToolResultStatusEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
