package com.nip.ai.strategy.model;

import org.springframework.stereotype.Service;

/**
 * OpenAI GPT-5.6 Sol 模型适配器。
 */
@Service
public class Gpt56SolModelAdapter extends OpenAiModelAdapter {

    @Override
    public String modelId() {
        return "gpt-5.6-sol";
    }
}
