package com.arte.ai.service;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.ModelConfigService;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.mapper.ModelConfigMapper;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelConfigParam;
import com.arte.ai.pojo.model.ModelConfigPo;
import com.arte.core.cache.Cache;
import com.arte.core.cache.CacheableDataSource;
import com.arte.core.enums.StatusEnum;
import com.arte.core.exception.BusinessException;
import com.arte.core.pojo.PageView;
import com.arte.core.pojo.UserContext;
import com.arte.core.utils.crypto.Sm2UtilForSmCrypto;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * AI 模型配置 Service 实现
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 16:46 ✾
 **/
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl extends ServiceImpl<ModelConfigMapper, ModelConfigDto> implements ModelConfigService {

    @Value("${security.sm2.privateKey}")
    protected String privateKeyText;

    @Resource(name = "defaultModelConfigCache")
    @Lazy
    private CacheableDataSource<String, ModelConfigDto> defaultModelConfigDataSource;

    @Resource(name = "defaultModelConfigCache")
    @Lazy
    private Cache<String, ModelConfigDto> defaultModelConfigCache;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigDto addModelConfig(ModelConfigDto dto) {
        // 校验同 provider + modelId 是否已存在
        boolean exists = lambdaQuery()
                .eq(ModelConfigDto::getProvider, dto.getProvider())
                .eq(ModelConfigDto::getModelId, dto.getModelId())
                .exists();
        if (exists) {
            throw new BusinessException(MessageUtils.get("error.ai.modelIdDuplicate", dto.getModelId()));
        }
        dto.setIcon(dto.getProvider().getIcon());
        if (save(dto)) {
            if (Boolean.TRUE.equals(dto.getDefaultFlag())) {
                evictDefaultModelConfig(resolveUserName(dto.getCreateBy()));
            }
            return dto;
        }
        throw new BusinessException("error.ai.modelAddFailed");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateModelConfig(ModelConfigDto dto) {
        ModelConfigDto existing = getAndCheckExists(dto.getProvider(), dto.getModelId());
        // provider + modelId 唯一性校验（排除自身）
        if (StrUtil.isNotBlank(dto.getModelId()) &&
                !existing.getModelId().equals(dto.getModelId())) {
            boolean conflicts = lambdaQuery()
                    .eq(ModelConfigDto::getProvider, dto.getProvider() != null ? dto.getProvider() : existing.getProvider())
                    .eq(ModelConfigDto::getModelId, dto.getModelId())
                    .exists();
            if (conflicts) {
                throw new BusinessException(MessageUtils.get("error.ai.modelIdDuplicate", dto.getModelId()));
            }
        }
        boolean updated = updateById(dto);
        if (updated) {
            evictDefaultModelConfig(resolveUserName(existing.getCreateBy()));
        }
        return updated;
    }

    @Override
    public PageView<ModelConfigDto> listModelConfigs(ModelConfigParam param) {
        return page(param, buildQueryWrapper(param));
    }

    @Override
    public ModelConfigDto getDefaultModelConfig(String userName) {
        if (StrUtil.isBlank(userName)) {
            return null;
        }
        return defaultModelConfigDataSource.get(userName, this::loadDefaultModelConfig);
    }

    private ModelConfigDto loadDefaultModelConfig(String userName) {
        // 查找当前登录用户默认模型
        QueryWrapper<ModelConfigDto> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("create_by", userName)
                .eq("default_flag", true)
                .eq("status", StatusEnum.DOING);
        return getOne(queryWrapper);
    }

    @Override
    public void evictDefaultModelConfig(String userName) {
        if (StrUtil.isNotBlank(userName)) {
            defaultModelConfigCache.evict(userName);
        }
    }

    private String resolveUserName(String userName) {
        return StrUtil.blankToDefault(userName, UserContext.getUserName());
    }


    @Override
    public Boolean testConnectivity(ModelProviderEnum provider, String modelId) {
        ModelConfigDto config = getAndCheckExists(provider, modelId);
        if (!StrUtil.isNotBlank(config.getApiKey())) {
            throw new BusinessException("error.ai.testConnNoApiKey");
        }
        String decryptedKey = Sm2UtilForSmCrypto.decryptHexStr(privateKeyText, config.getApiKey());
        try {
            boolean result = doTestConnectivity(config, decryptedKey);
            log.info("[ModelConfigService] 连通性测试结果, provider={}, modelId={}, result={}", provider, modelId, result);
            return result;
        } catch (Exception e) {
            log.warn("[ModelConfigService] 连通性测试失败, provider={}, modelId={}, error={}", provider, modelId, e.getMessage());
            return Boolean.FALSE;
        }
    }

    private QueryWrapper<ModelConfigDto> buildQueryWrapper(ModelConfigParam param) {
        QueryWrapper<ModelConfigDto> wrapper = new QueryWrapper<>();
        wrapper.eq(Objects.nonNull(param.getProvider()), ModelConfigPo.COL_PROVIDER, param.getProvider())
                .eq(StrUtil.isNotBlank(param.getModelId()), ModelConfigPo.COL_MODEL_ID, param.getModelId())
                .eq(StrUtil.isNotBlank(param.getModelType()), ModelConfigPo.COL_MODEL_TYPE, param.getModelType())
                .eq(StrUtil.isNotBlank(param.getCreateBy()), ModelConfigPo.COL_CREATE_BY, param.getCreateBy().trim())
                .like(StrUtil.isNotBlank(param.getModelName()), ModelConfigPo.COL_MODEL_NAME, "%" + param.getModelName() + "%")
                .between(param.getStartDateTimeFloor() != null, ModelConfigPo.COL_UPDATE_TIME, param.getStartDateTimeFloor(), param.getStartDateTimeCeil())
                .between(param.getEndDateTimeFloor() != null, ModelConfigPo.COL_UPDATE_TIME, param.getEndDateTimeFloor(), param.getEndDateTimeCeil());
        if (CollUtil.isNotEmpty(param.orders())) {
            param.orders().forEach(order -> wrapper.orderBy(true, order.isAsc(), order.getColumn()));
        }
        return wrapper;
    }

    /**
     * 查询配置并校验存在性
     */
    private ModelConfigDto getAndCheckExists(ModelProviderEnum provider, String modelId) {
        return lambdaQuery()
                .eq(ModelConfigDto::getProvider, provider)
                .eq(ModelConfigDto::getModelId, modelId)
                .one();
    }


    /**
     * 执行连通性测试
     * 根据 provider 调用对应的探活接口（简单发一条 Hello 消息）
     */
    private boolean doTestConnectivity(ModelConfigDto config, String decryptedKey) {
        ModelProviderEnum provider = config.getProvider(); // Changed from String to ModelProviderEnum
        String baseUrl = StrUtil.isNotBlank(config.getApiBaseUrl())
                ? config.getApiBaseUrl()
                : resolveDefaultBaseUrl(provider);

        // 使用 Spring WebClient 发送简单探活请求
        try {
            org.springframework.web.reactive.function.client.WebClient webClient =
                    org.springframework.web.reactive.function.client.WebClient.builder()
                            .baseUrl(baseUrl)
                            .defaultHeader("Authorization", "Bearer " + decryptedKey)
                            .defaultHeader("Content-Type", "application/json")
                            .build();

            String testBody = """
                    {
                        "model": "%s",
                        "messages": [{"role": "user", "content": "Hi"}],
                        "max_tokens": 1,
                        "stream": false
                    }
                    """.formatted(config.getModelId());

            String response = webClient.post()
                    .uri("/chat/completions")
                    .bodyValue(testBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(10))
                    .block();

            return StrUtil.isNotBlank(response);
        } catch (Exception e) {
            log.warn("[ModelConfigService] 连通性测试异常, provider={}, error={}",
                    provider, e.getMessage());
            return false;
        }
    }

    /**
     * 根据 provider 解析默认 BaseUrl
     */
    private String resolveDefaultBaseUrl(ModelProviderEnum provider) {
        if (provider == null) return "https://api.openai.com/v1";
        return switch (provider.getValue().toLowerCase()) {
            case "openai" -> "https://api.openai.com/v1";
            case "deepseek" -> "https://api.deepseek.com/v1";
            case "qwen" -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case "openrouter" -> "https://openrouter.ai/api/v1";
            default -> "https://api.openai.com/v1";
        };
    }
}
