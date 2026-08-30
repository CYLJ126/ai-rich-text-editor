package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 会话状态枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum ConversationStatusEnum implements IEnum<String>, MyEnum<String> {
    ACTIVE("active", "活跃"),
    ARCHIVED("archived", "已归档"),
    DELETED("deleted", "已删除");

    private final String value;
    private final String description;

    ConversationStatusEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
