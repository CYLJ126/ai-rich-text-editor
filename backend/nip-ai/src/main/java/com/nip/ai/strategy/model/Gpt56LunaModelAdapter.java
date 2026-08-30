package com.nip.ai.strategy.model;

import org.springframework.stereotype.Service;

/**
 * OpenAI GPT-5.6 Luna 模型适配器。
 */
@Service
public class Gpt56LunaModelAdapter extends OpenAiModelAdapter {

    @Override
    public String modelId() {
        return "gpt-5.6-luna";
    }
}
