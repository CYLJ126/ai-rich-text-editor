package com.nip.ai.strategy.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * deepseek-v4-pro 模型适配器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/20 22:55 ✾
 **/
@Slf4j
@Service
public class DeepSeekV4ProModelAdapter extends DeepSeekModelAdapter {

    @Override
    public String modelId() {
        return "deepseek-v4-pro";
    }
}
