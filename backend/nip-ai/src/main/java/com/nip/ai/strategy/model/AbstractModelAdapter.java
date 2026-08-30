package com.nip.ai.strategy.model;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.ReflectUtil;
import com.nip.ai.advisor.AbstractAdvisor;
import com.nip.ai.api.ModelAdapter;
import com.nip.ai.api.ModelConfigService;
import com.nip.ai.pojo.chat.ChatRequestDto;
import com.nip.ai.pojo.model.ModelConfigDto;
import com.nip.core.cache.Cache;
import com.nip.core.cache.CacheManager;
import com.nip.core.serialize.SerializerFactory;
import com.nip.core.utils.crypto.Sm2Util;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型适配器抽象基类
 * 提供模型配置加载、消息转换等公共能力
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:07 ✾
 **/
@Slf4j
public abstract class AbstractModelAdapter implements ModelAdapter {

    @Value("${security.sm2.privateKey}")
    protected String privateKeyText;

    protected ECPrivateKeyParameters privateKey;

    @Resource
    protected ModelConfigService modelConfigService;

    @Resource
    protected CacheManager cacheManager;

    protected final JsonMapper jsonMapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    protected volatile Cache<String, ModelConfigDto> cache;

    @PostConstruct
    public void initCache() {
        this.privateKey = Sm2Util.createPrivateKey(privateKeyText);
        if (cache == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = cacheManager.createCache("modelConfigCache");
                }
            }
        }
        // 加载官方模型配置 TODO 加锁同步缓存，避免多服务实例同时加载配置
        String jsonConfig = ResourceUtil.readUtf8Str("modelDefaultConfig/" + modelId() + ".json");
        ModelConfigDto config = jsonMapper.readValue(jsonConfig, ModelConfigDto.class);
        cache.put(modelKey(), config);
    }

    @Override
    public ModelConfigDto getOfficialModelConfig() {
        if (cache.exists(modelKey())) {
            return cache.get(modelKey());
        }
        // 如果需要更新，删除缓存中的旧配置，会重新自动加载
        initCache();
        return cache.get(modelKey());
    }

    @Override
    public boolean supportsCapability(String capability) {
        Field field = ReflectUtil.getField(ModelConfigDto.class, capability);
        if (field == null) {
            return false;
        }
        ModelConfigDto config = getOfficialModelConfig();
        Object fieldValue = ReflectUtil.getFieldValue(config, field);
        if (fieldValue == null) {
            return false;
        }
        if (fieldValue instanceof Boolean) {
            return (Boolean) fieldValue;
        }
        return true;
    }

    @Override
    public List<Message> handleResponse(ChatResponse chatResponse, Map<String, Object> context) {
        return chatResponse.getResults()
                .stream()
                .map(Generation::getOutput)
                .map(item -> {
                    Map<String, Object> metadata = new HashMap<>(item.getMetadata());
                    metadata.put("requestId", metadata.get("id"));
                    ChatRequestDto chatRequestDto = (ChatRequestDto) context.get(AbstractAdvisor.REQUEST_DTO);
                    fillCostInfo(chatRequestDto, context);
                    metadata.putAll(context);
                    return (Message) AssistantMessage.builder().content(item.getText())
                            .properties(metadata)
                            .toolCalls(item.getToolCalls())
                            .media(item.getMedia())
                            .build();
                }).toList();
    }

    protected void fillCostInfo(ChatRequestDto chatRequestDto, Map<String, Object> context) {
        if (context.containsKey("promptToken") && chatRequestDto.getInputUnitPrice() != null) {
            int promptToken = (int) context.get("promptToken");
            BigDecimal promptCost = chatRequestDto.getInputUnitPrice().multiply(BigDecimal.valueOf(promptToken))
                    .divide(new BigDecimal(1000000), RoundingMode.CEILING);
            context.put("promptCost", promptCost);
        }
        if (context.containsKey("completionToken") && chatRequestDto.getOutputUnitPrice() != null) {
            int completionToken = (int) context.get("completionToken");
            BigDecimal completionCost = chatRequestDto.getOutputUnitPrice().multiply(BigDecimal.valueOf(completionToken))
                    .divide(new BigDecimal(1000000), RoundingMode.CEILING);
            context.put("completionCost", completionCost);
        }
        context.put("currency", chatRequestDto.getPriceCurrency());
    }

}
