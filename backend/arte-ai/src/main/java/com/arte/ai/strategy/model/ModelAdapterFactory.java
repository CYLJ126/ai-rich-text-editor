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

    @PostConstruct
    public void init() {
        adapters.forEach(adapter -> {
            registry.put(adapter.modelKey(), adapter);
            log.info("注册模型适配器: {}", adapter.modelKey());
        });
    }

    /**
     * 获取指定 provider 的适配器
     */
    public ModelAdapter getAdapter(ModelProviderEnum provider, String modelId) {
        ModelAdapter adapter = registry.get(provider.getValue() + ":" + modelId);
        if (adapter == null) {
            throw new IllegalArgumentException(MessageUtils.get("error.ai.providerUnsupported", provider.getValue() + ":" + modelId));
        }
        return adapter;
    }
}

