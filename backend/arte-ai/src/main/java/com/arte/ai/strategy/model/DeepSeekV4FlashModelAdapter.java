package com.arte.ai.strategy.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * deepseek-v4-flash 模型适配器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/20 22:55 ✾
 **/
@Slf4j
@Service
public class DeepSeekV4FlashModelAdapter extends DeepSeekModelAdapter {

    @Override
    public String modelId() {
        return "deepseek-v4-flash";
    }
}
