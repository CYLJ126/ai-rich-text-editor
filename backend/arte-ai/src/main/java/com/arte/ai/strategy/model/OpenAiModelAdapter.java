package com.arte.ai.strategy.model;

import cn.hutool.core.util.StrUtil;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.core.enums.TextTypeEnum;
import com.arte.core.exception.ChatException;
import com.arte.core.utils.crypto.Sm2UtilForSmCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容模型适配器。
 * 大部分支持 OpenAI 协议的厂商可直接继承该类。
 *
 * @author CYLJ126
 * @since 2026/7/21 10:14
 **/
@Slf4j
public abstract class OpenAiModelAdapter extends AbstractModelAdapter {

    @Override
    public ModelProviderEnum provider() {
        return ModelProviderEnum.OPENAI;
    }

    @Override
    public ChatModel getChatModel(ModelConfigDto modelConfigDto, ChatOptions chatOptions) {
        // API Key 只在创建客户端时解密，不写回请求对象。
        ModelConfigDto config = modelConfigService.getById(modelConfigDto.getId());
        if (config == null) {
            throw new ChatException("模型配置不存在: " + modelConfigDto.getId());
        }
        if (StrUtil.hasBlank(config.getApiKey(), config.getApiBaseUrl())) {
            throw new ChatException("API Key 或 Base URL 不能为空");
        }

        OpenAiChatOptions.Builder optionsBuilder;
        String apiKey = Sm2UtilForSmCrypto.decryptForSmCrypto(config.getApiKey(), privateKey);
        if (Objects.nonNull(chatOptions)) {
            optionsBuilder = ((OpenAiChatOptions) chatOptions).mutate();
        } else {
            optionsBuilder = OpenAiChatOptions.builder();
        }
        optionsBuilder.apiKey(apiKey).baseUrl(config.getApiBaseUrl().trim());
        applyConnectionOptions(optionsBuilder, config);

        return OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .build();
    }

    @Override
    public ChatClient buildChatClient(ChatRequestDto chatRequest) {
        if (chatRequest == null || chatRequest.getModelAutoId() == null) {
            throw new ChatException("模型请求或模型配置 ID 不能为空");
        }
        ModelConfigDto config = modelConfigService.getById(chatRequest.getModelAutoId());
        try {
            OpenAiChatModel openAiChatModel = (OpenAiChatModel) getChatModel(config, buildOptions(chatRequest));
            return ChatClient.builder(openAiChatModel).build();
        } catch (ChatException e) {
            throw e;
        } catch (Exception e) {
            log.error("创建 OpenAI 兼容客户端失败，modelId={}", chatRequest.getModelId(), e);
            throw new ChatException("创建 OpenAI 兼容客户端失败", e);
        }
    }

    @Override
    public OpenAiChatOptions buildOptions(ChatRequestDto chatRequest) {
        if (chatRequest == null) {
            throw new ChatException("模型请求不能为空");
        }
        if (StrUtil.isBlank(chatRequest.getModelId())) {
            throw new ChatException("模型 ID 不能为空");
        }

        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(chatRequest.getModelId())
                .streamUsage(true);
        if (chatRequest.getSeed() != null) {
            builder.seed(chatRequest.getSeed());
        }
        if (StrUtil.isNotBlank(chatRequest.getUserName())) {
            builder.user(chatRequest.getUserName());
        }
        Map<String, Object> extraParam = chatRequest.getExtraParam();
        if (extraParam != null && !extraParam.isEmpty()) {
            // 使用副本，避免请求发出前外部继续修改参数。
            builder.extraBody(new HashMap<>(extraParam));
        }
        if (Objects.nonNull(chatRequest.getTemperature())) {
            builder.temperature(chatRequest.getTemperature());
        }
        if (Objects.nonNull(chatRequest.getReasoningEffort())) {
            builder.reasoningEffort(chatRequest.getReasoningEffort().getValue());
        }
        if (Objects.nonNull(chatRequest.getFrequencyPenalty())) {
            builder.frequencyPenalty(chatRequest.getFrequencyPenalty());
        }
        if (Objects.nonNull(chatRequest.getPresencePenalty())) {
            builder.presencePenalty(chatRequest.getPresencePenalty());
        }
        if (Objects.nonNull(chatRequest.getTopP())) {
            builder.topP(chatRequest.getTopP());
        }
        if (Objects.nonNull(chatRequest.getMaxTokens())) {
            builder.maxCompletionTokens(chatRequest.getMaxTokens());
        }
        if (Objects.nonNull(chatRequest.getTextType())) {
            builder.responseFormat(getResponseFormat(chatRequest));
        }
        return builder.build();
    }

    protected ResponseFormat getResponseFormat(ChatRequestDto chatRequest) {
        if (Objects.equals(chatRequest.getTextType(), TextTypeEnum.JSON)) {
            return ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
        }
        return ResponseFormat.builder().type(ResponseFormat.Type.TEXT).build();
    }

    private void applyConnectionOptions(OpenAiChatOptions.Builder builder, ModelConfigDto config) {
        if (config.getTimeoutSeconds() != null) {
            if (config.getTimeoutSeconds() <= 0) {
                throw new ChatException("请求超时时间必须大于 0 秒");
            }
            builder.timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
        }
        if (config.getMaxRetries() != null) {
            if (config.getMaxRetries() < 0) {
                throw new ChatException("最大重试次数不能小于 0");
            }
            builder.maxRetries(config.getMaxRetries());
        }
        if (StrUtil.isNotBlank(config.getProxy())) {
            builder.proxy(parseProxy(config.getProxy()));
        }
    }

    /**
     * 支持 host:port、http(s)://host:port 和 socks(5)://host:port。
     */
    static Proxy parseProxy(String proxyValue) {
        String value = proxyValue == null ? "" : proxyValue.trim();
        if (value.isEmpty()) {
            return Proxy.NO_PROXY;
        }

        try {
            URI uri = new URI(value.contains("://") ? value : "http://" + value);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            Proxy.Type type = switch (scheme) {
                case "http", "https" -> Proxy.Type.HTTP;
                case "socks", "socks5" -> Proxy.Type.SOCKS;
                default -> throw new ChatException("不支持的代理协议: " + scheme);
            };
            String host = uri.getHost();
            if (StrUtil.isBlank(host) || uri.getPort() < 1 || uri.getPort() > 65535) {
                throw new ChatException("代理地址格式错误，应为 host:port");
            }
            return new Proxy(type, InetSocketAddress.createUnresolved(host, uri.getPort()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new ChatException("代理地址格式错误，应为 host:port", e);
        }
    }
}
