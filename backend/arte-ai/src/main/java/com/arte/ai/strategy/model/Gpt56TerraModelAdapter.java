package com.arte.ai.strategy.model;

import org.springframework.stereotype.Service;

/**
 * OpenAI GPT-5.6 Terra 模型适配器。
 */
@Service
public class Gpt56TerraModelAdapter extends OpenAiModelAdapter {

    @Override
    public String modelId() {
        return "gpt-5.6-terra";
    }
}
