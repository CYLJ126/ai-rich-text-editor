package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 场景类型枚举
 * 标识会话是在什么地方创建的，例如聊天页面、基础写作侧边栏等
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/28 15:59 ✾
 **/
@Getter
public enum GenerateTypeEnum implements IEnum<String>, MyEnum<String> {
    CHAT("chat", "聊天"),
    CONTINUATION("continuation", "补全/续写"), // 有光标位置则在光标位置补全，无光标位置则在末尾续写
    SUMMARY("summary", "总结/摘要"),
    POLISH("polish", "润色"),
    TRANSLATE("translate", "翻译");

    private final String value;
    private final String description;

    GenerateTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
