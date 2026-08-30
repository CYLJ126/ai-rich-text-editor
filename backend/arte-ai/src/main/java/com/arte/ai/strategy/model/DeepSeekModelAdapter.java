package com.arte.ai.strategy.model;

import cn.hutool.core.util.StrUtil;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.common.enums.ReasoningEffortEnum;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.core.enums.TextTypeEnum;
import com.arte.core.exception.ChatException;
import com.arte.core.exception.InteractiveException;
import com.arte.core.utils.crypto.Sm2UtilForSmCrypto;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpStatusClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Value;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * DeepSeek 模型适配器
 * <a href="https://api-docs.deepseek.com/zh-cn/api/create-chat-completion">DeepSeek API - 对话补全</a>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:07 ✾
 **/
@Slf4j
public abstract class DeepSeekModelAdapter extends AbstractModelAdapter {
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    @Value("${spring.ai.deepseek.api-key}")
    private String apiKey;

    @Override
    public ModelProviderEnum provider() {
        return ModelProviderEnum.DEEPSEEK;
    }

    @Override
    public ChatModel getChatModel(ModelConfigDto modelConfig, ChatOptions chatOptions) {
        return getChatModel(modelConfig, chatOptions, false);
    }

    private ChatModel getChatModel(ModelConfigDto modelConfig, ChatOptions chatOptions, boolean disableThinking) {
        if (StrUtil.hasBlank(modelConfig.getApiKey(), modelConfig.getApiBaseUrl())) {
            throw new ChatException("error.ai.apiKeyOrBaseUrlRequired");
        }
        String apiKey = Sm2UtilForSmCrypto.decryptForSmCrypto(modelConfig.getApiKey(), privateKey);
        String baseUrl = StrUtil.blankToDefault(modelConfig.getApiBaseUrl(), modelConfig.getApiBaseUrl());
        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl);
        if (disableThinking) {
            apiBuilder.webClientBuilder(DeepSeekThinkingRequestBodySupport.buildWebClient());
        }
        DeepSeekApi deepSeekApi = apiBuilder.build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(Objects.nonNull(chatOptions) ? (DeepSeekChatOptions) chatOptions : DeepSeekChatOptions.builder().build())
                .build();
    }


    @Override
    public ChatClient buildChatClient(ChatRequestDto chatRequest) {
        // 获取用户模型配置
        ModelConfigDto config = modelConfigService.getById(chatRequest.getModelAutoId());
        boolean disableThinking = Objects.equals(chatRequest.getReasoningEffort(), ReasoningEffortEnum.NONE);
        ChatModel deepSeekChatModel = getChatModel(config, buildOptions(chatRequest), disableThinking);
        return ChatClient.builder(deepSeekChatModel).build();
    }

    /**
     * 构建 DeepSeekChatOptions
     * 官方 v4 文档，frequency_penalty、presence_penalty 已不再支持，不用设置
     * topK 不支持
     *
     * @param chatRequest 对话请求参数
     * @return DeepSeekChatOptions
     */
    @Override
    public DeepSeekChatOptions buildOptions(ChatRequestDto chatRequest) {
        // TODO 其他参数设置：stop/stopSequences/logprobs/topLogprobs/tools/toolChoice/toolCallbacks/toolContext
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        builder.model(chatRequest.getModelId());
        if (Objects.nonNull(chatRequest.getTemperature())) {
            builder.temperature(chatRequest.getTemperature());
        }
        if (Objects.nonNull(chatRequest.getTopP())) {
            builder.topP(chatRequest.getTopP());
        }
        if (Objects.nonNull(chatRequest.getMaxTokens())) {
            builder.maxTokens(chatRequest.getMaxTokens());
        }
        builder.responseFormat(getDeepSeekResponseFormat(chatRequest));

        return builder.build();
    }

    protected ResponseFormat getDeepSeekResponseFormat(ChatRequestDto chatRequest) {
        if (Objects.equals(chatRequest.getTextType(), TextTypeEnum.JSON)) {
            return ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
        }
        return ResponseFormat.builder().type(ResponseFormat.Type.TEXT).build();
    }

    private void listOfficialModelConfig() {
        HttpClient.create(ConnectionProvider.builder("deepseek-model-config-client")
                        .maxConnections(50)
                        .build())
                .headers(headers -> headers
                        .set(HttpHeaderNames.ACCEPT, HttpHeaderValues.APPLICATION_JSON)
                        .set(HttpHeaderNames.AUTHORIZATION, "Bearer " + apiKey))
                .get()
                .uri(DEFAULT_BASE_URL + "/models")
                .responseSingle((response, byteBufMono) -> {
                    log.info("模型提供商：{}，模型 ID：{}，响应状态: {}", provider(), modelId(), response.status());
                    // 非 2xx 状态码处理
                    if (!response.status().codeClass().equals(HttpStatusClass.SUCCESS)) {
                        return byteBufMono.asString()
                                .flatMap(body -> Mono.error(
                                        new InteractiveException("请求失败，状态码: "
                                                + response.status().code() + "，响应体: " + body)
                                ));
                    }
                    // 读取响应体并反序列化
                    return byteBufMono.asString(StandardCharsets.UTF_8);
                })
                .doOnSuccess(config -> log.info("模型提供商：{}，模型 ID：{}，获取到模型配置成功: {}", provider(), modelId(), config))
                .doOnError(e -> log.error("模型提供商：{}，模型 ID：{}，获取到模型配置失败，模型ID: {}", provider(), modelId(), e));
    }

}
