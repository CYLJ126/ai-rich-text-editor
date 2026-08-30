package com.nip.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.nip.core.enums.MyEnum;
import lombok.Getter;

/**
 * 模型供应商
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:42 ✾
 **/
@Getter
public enum ModelProviderEnum implements IEnum<String>, MyEnum<String> {
    DEEPSEEK("deepseek", "DeepSeek", "DeepSeekFilled"),
    QIAN_WEN("qianwen", "TongyiQwen", "QwenFilled"),
    OPENAI("openai", "OpenAI", "OpenAIFilled"),
    CLAUDE("claude", "Claude", "ClaudeFilled"),
    MISTRAL("mistral", "Mistral", "MistralFilled"),
    OPEN_ROUTER("openrouter", "OpenRouter", "ApiOutlined");

    private final String value;
    private final String description;
    // 与前端 src/components/DynamicIcon/iconMap.ts 中的图标保持一致
    private final String icon;

    ModelProviderEnum(String value, String description, String icon) {
        this.value = value;
        this.description = description;
        this.icon = icon;
    }
}
