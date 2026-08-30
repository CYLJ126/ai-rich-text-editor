package com.arte.ai.strategy.model;

import com.arte.core.i18n.MessageUtils;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 为 DeepSeek 请求添加一些 Spring AI 不支持的参数。
 * 为暂未原生支持 thinking 参数的 Spring AI DeepSeek 请求体补充关闭思考配置。
 */
final class DeepSeekThinkingRequestBodySupport {
    private static final Map<String, String> THINKING_DISABLED = Map.of("type", "disabled");
    private static final JsonMapper DEFAULT_OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private DeepSeekThinkingRequestBodySupport() {
    }

    static WebClient.Builder buildWebClient() {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().jacksonJsonEncoder(
                        new JacksonJsonEncoder(buildObjectMapper())))
                .build();
        return WebClient.builder().exchangeStrategies(strategies);
    }

    static JsonMapper buildObjectMapper() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(
                DeepSeekApi.ChatCompletionRequest.class,
                new ThinkingDisabledRequestSerializer());
        return JsonMapper.builder()
                .findAndAddModules()
                .addModule(module)
                .build();
    }

    private static final class ThinkingDisabledRequestSerializer
            extends ValueSerializer<DeepSeekApi.ChatCompletionRequest> {

        @Override
        public void serialize(
                DeepSeekApi.ChatCompletionRequest request,
                JsonGenerator generator,
                SerializationContext context) throws JacksonException {
            JsonNode requestNode = DEFAULT_OBJECT_MAPPER.valueToTree(request);
            if (!(requestNode instanceof ObjectNode objectNode)) {
                throw new IllegalStateException(MessageUtils.get("error.ai.deepseekBodyMustBeObject"));
            }
            objectNode.set(
                    "thinking",
                    DEFAULT_OBJECT_MAPPER.valueToTree(THINKING_DISABLED));
            context.writeValue(generator, objectNode);
        }
    }
}
