package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 场景类型枚举
 * 标识会话是在什么地方创建的，例如聊天页面、基础写作侧边栏等
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/25 12:09 ✾
 **/
@Getter
public enum SceneTypeEnum implements IEnum<String>, MyEnum<String> {
    CHAT_PAGE("chat_management", "聊天页面"),
    BASIC_WRITING_CHAT("basic_writing_chat", "基础写作侧边栏问答"),
    WRITING_SUMMARY("writing_summary", "文章总结或总结润色"),
    WRITING_PROMPT("writing_prompt", "文章补全或续写");

    private final String value;
    private final String description;

    SceneTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
