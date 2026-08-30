package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 消息状态枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum MessageStatusEnum implements IEnum<String>, MyEnum<String> {
    PENDING("pending", "等待中"),
    STREAMING("streaming", "流式输出中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    STOPPED("stopped", "已停止");

    private final String value;
    private final String description;

    MessageStatusEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
