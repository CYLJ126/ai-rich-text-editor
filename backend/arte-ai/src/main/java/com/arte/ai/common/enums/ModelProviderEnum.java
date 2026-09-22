package com.arte.ai.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 模型供应商
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/13 17:42 ✾
 **/
@Getter
public enum ModelProviderEnum implements IEnum<String>, MyEnum<String> {
    DEEPSEEK("deepseek", "DeepSeek", "DeepSeekFilled", "https://api.deepseek.com/v1"),
    QIAN_WEN("qianwen", "TongyiQwen", "QwenFilled", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    OPENAI("openai", "OpenAI", "OpenAIFilled", "https://api.openai.com/v1"),
    CLAUDE("claude", "Claude", "ClaudeFilled", "https://api.anthropic.com/v1"),
    MISTRAL("mistral", "Mistral", "MistralFilled", "https://api.mistral.ai/v1"),
    OPEN_ROUTER("openrouter", "OpenRouter", "ApiOutlined", "https://openrouter.ai/api/v1");

    private final String value;
    private final String description;
    // 与前端 src/components/DynamicIcon/iconMap.ts 中的图标保持一致
    private final String icon;
    private final String defaultApiBaseUrl;

    ModelProviderEnum(String value, String description, String icon, String defaultApiBaseUrl) {
        this.value = value;
        this.description = description;
        this.icon = icon;
        this.defaultApiBaseUrl = defaultApiBaseUrl;
    }
}
