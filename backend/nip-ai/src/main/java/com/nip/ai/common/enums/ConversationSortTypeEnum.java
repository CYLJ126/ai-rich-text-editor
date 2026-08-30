package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 会话排序类型枚举
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 12:09 ✾
 **/
@Getter
public enum ConversationSortTypeEnum implements IEnum<String>, MyEnum<String> {
    LAST_MESSAGE_TIME("lastMessageTime", "最后消息时间"),
    CREATE_TIME("createTime", "创建时间"),
    MANUAL("manual", "手动排序");

    private final String value;
    private final String description;

    ConversationSortTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
