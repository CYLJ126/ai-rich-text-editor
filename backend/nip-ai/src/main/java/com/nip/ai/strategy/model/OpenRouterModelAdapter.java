//package com.nip.ai.strategy.model;
//
//import com.nip.ai.common.enums.ModelProviderEnum;
//import com.nip.ai.pojo.model.ModelRequest;
//import com.nip.ai.pojo.model.ModelResponse;
//import com.nip.ai.pojo.model.ModelStreamChunk;
//import lombok.extern.slf4j.Slf4j;
//import org.jspecify.annotations.NonNull;
//import org.springframework.ai.chat.messages.Message;
//import org.springframework.ai.chat.model.ChatResponse;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.ai.openai.OpenAiChatOptions;
//import org.springframework.stereotype.Component;
//import org.springframework.util.StringUtils;
//import reactor.core.publisher.Flux;
//
//import java.util.List;
//import java.util.Map;
//
/// **
// * OpenRouter 适配器
// * OpenRouter 完全兼容 OpenAI 协议，通过 model 字段路由到不同模型
// *
// * @author CYLJ126 ≧◔◡◔≦
// * @since 2026/6/19 13:09 ✾
// **/
//@Slf4j
//@Component
//public class OpenRouterModelAdapter extends AbstractAiModelAdapter {
//
//    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
//
//    @Override
//    String defaultBaseUrl() {
//        return DEFAULT_BASE_URL;
//    }
//
//    @Override
//    public ModelProviderEnum provider() {
//        return ModelProviderEnum.OPEN_ROUTER;
//    }
//
//    @Override
//    public ModelResponse chat(ModelRequest request) {
//        long startMs = System.currentTimeMillis();
//        try {
//            Map<String, Object> mergedParams = mergeParams(request.getProvider(), request.getModelId(), request.getParams());
//            OpenAiChatOptions options = buildOptions(request, mergedParams);
//            List<Message> messages = toSpringAiMessages(request.getSystemPrompt(), request.getMessages());
//            ChatResponse response = buildChatClient(request.getProvider(), request.getModelId())
//                    .prompt(new Prompt(messages, options))
//                    .call()
//                    .chatResponse();
//            return extractResponse(response, startMs);
//        } catch (Exception e) {
//            log.error("[OpenRouter] chat error, modelId={}", request.getModelId(), e);
//            return ModelResponse.builder()
//                    .success(false)
//                    .errorCode("OPENROUTER_ERROR")
//                    .errorMessage(e.getMessage())
//                    .build();
//        }
//    }
//
//    @Override
//    public Flux<@NonNull ModelStreamChunk> chatStream(ModelRequest request) {
//        Map<String, Object> mergedParams = mergeParams(request.getProvider(), request.getModelId(), request.getParams());
//        OpenAiChatOptions options = buildOptions(request, mergedParams);
//        List<Message> messages = toSpringAiMessages(request.getSystemPrompt(), request.getMessages());
//        return buildChatClient(request.getProvider(), request.getModelId())
//                .prompt(new Prompt(messages, options))
//                .stream()
//                .chatResponse()
//                .map(this::toStreamChunk)
//                .onErrorResume(e -> {
//                    log.error("[OpenRouter] stream error, modelId={}", request.getModelId(), e);
//                    return Flux.just(ModelStreamChunk.builder()
//                            .done(true)
//                            .errorCode("OPENROUTER_STREAM_ERROR")
//                            .errorMessage(e.getMessage())
//                            .build());
//                });
//    }
//
//    @Override
//    protected OpenAiChatOptions buildOptions(ModelRequest request, Map<String, Object> params) {
//        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
//                .model(request.getModelId());
//        if (params == null) return builder.build();
//        if (params.containsKey("temperature")) {
//            builder.temperature(((Number) params.get("temperature")).doubleValue());
//        }
//        if (params.containsKey("max_tokens")) {
//            builder.maxTokens(((Number) params.get("max_tokens")).intValue());
//        }
//        if (params.containsKey("top_p")) {
//            builder.topP(((Number) params.get("top_p")).doubleValue());
//        }
//        return builder.build();
//    }
//
//    @Override
//    protected ModelResponse extractResponse(ChatResponse response, long startMs) {
//        if (response == null || response.getResult() == null) {
//            return ModelResponse.builder().success(false).errorCode("EMPTY_RESPONSE").build();
//        }
//        var result = response.getResult();
//        var usage = response.getMetadata().getUsage();
//        return ModelResponse.builder()
//                .success(true)
//                .content(result.getOutput().getText())
//                .finishReason(result.getMetadata().getFinishReason())
//                .promptTokens(usage.getPromptTokens())
//                .completionTokens(usage.getCompletionTokens())
//                .totalTokens(usage.getTotalTokens())
//                .build();
//    }
//
//    private ModelStreamChunk toStreamChunk(ChatResponse response) {
//        if (response == null) return ModelStreamChunk.builder().done(false).build();
//        boolean isDone = response.getResult() != null && StringUtils.hasText(response.getResult().getMetadata().getFinishReason());
//        String delta = response.getResult() != null
//                ? response.getResult().getOutput().getText() : "";
//        var usage = response.getMetadata().getUsage();
//        return ModelStreamChunk.builder()
//                .deltaContent(delta)
//                .done(isDone)
//                .finishReason(isDone ? response.getResult().getMetadata().getFinishReason() : null)
//                .promptTokens(isDone ? usage.getPromptTokens() : null)
//                .completionTokens(isDone ? usage.getCompletionTokens() : null)
//                .totalTokens(isDone ? usage.getTotalTokens() : null)
//                .build();
//    }
//}