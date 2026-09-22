package com.arte.ai.strategy.model;

import com.arte.core.i18n.MessageUtils;

import com.arte.ai.api.ModelAdapter;
import com.arte.ai.common.enums.ModelProviderEnum;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型适配器注册中心
 * 自动收集所有 AiModelAdapter 实现，按 provider 索引
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:10 ✾
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelAdapterFactory {
    private final List<ModelAdapter> adapters;
    private final Map<String, ModelAdapter> registry = new ConcurrentHashMap<>();
    private final Map<ModelProviderEnum, ModelAdapter> providerFallbacks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        adapters.forEach(adapter -> {
            registry.put(adapter.modelKey(), adapter);
            providerFallbacks.putIfAbsent(adapter.provider(), adapter);
            log.info("注册模型适配器: {}", adapter.modelKey());
        });

        // 千问、Claude、Mistral 和 OpenRouter 均提供 OpenAI 兼容的对话端点。
        // 未为单个模型声明专用适配器时，复用通用 OpenAI 适配器。
        ModelAdapter openAiFallback = providerFallbacks.get(ModelProviderEnum.OPENAI);
        if (openAiFallback != null) {
            providerFallbacks.putIfAbsent(ModelProviderEnum.QIAN_WEN, openAiFallback);
            providerFallbacks.putIfAbsent(ModelProviderEnum.CLAUDE, openAiFallback);
            providerFallbacks.putIfAbsent(ModelProviderEnum.MISTRAL, openAiFallback);
            providerFallbacks.putIfAbsent(ModelProviderEnum.OPEN_ROUTER, openAiFallback);
        }
    }

    /**
     * 获取指定 provider 的适配器
     */
    public ModelAdapter getAdapter(ModelProviderEnum provider, String modelId) {
        if (provider == null) {
            throw new IllegalArgumentException(MessageUtils.get("error.ai.providerUnsupported", "null:" + modelId));
        }
        ModelAdapter adapter = registry.get(provider.getValue() + ":" + modelId);
        if (adapter == null) {
            adapter = providerFallbacks.get(provider);
        }
        if (adapter == null) {
            throw new IllegalArgumentException(MessageUtils.get("error.ai.providerUnsupported", provider.getValue() + ":" + modelId));
        }
        return adapter;
    }
}
