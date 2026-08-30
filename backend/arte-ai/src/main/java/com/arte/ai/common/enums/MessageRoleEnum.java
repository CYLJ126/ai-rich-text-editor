package com.arte.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;
import org.springframework.ai.chat.messages.MessageType;

/**
 * 消息角色枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum MessageRoleEnum implements IEnum<String>, MyEnum<String> {
    USER("user", "用户"),
    ASSISTANT("assistant", "助手"),
    SYSTEM("system", "系统"),
    TOOL("tool", "工具");

    private final String value;
    private final String description;

    MessageRoleEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static MessageRoleEnum fromMessageType(MessageType messageType) {
        return switch (messageType) {
            case USER -> USER;
            case ASSISTANT -> ASSISTANT;
            case SYSTEM -> SYSTEM;
            case TOOL -> TOOL;
        };
    }
}
