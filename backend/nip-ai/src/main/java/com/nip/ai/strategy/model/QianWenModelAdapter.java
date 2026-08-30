//package com.nip.ai.strategy.model;
//
//import com.nip.ai.common.enums.ModelProviderEnum;
//import com.nip.ai.pojo.model.ModelRequest;
//import com.nip.ai.pojo.model.ModelResponse;
//import com.nip.ai.pojo.model.ModelStreamChunk;
//import lombok.extern.slf4j.Slf4j;
//import org.jspecify.annotations.NonNull;
//import org.springframework.ai.chat.client.ChatClient;
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
// * 通义千问适配器
// * 千问兼容 OpenAI 协议，使用 DashScope OpenAI 兼容端点
// *
// * @author CYLJ126 ≧◔◡◔≦
// * @since 2026/6/19 13:08 ✾
// **/
//@Slf4j
//@Component
//public class QianWenModelAdapter extends AbstractAiModelAdapter {
//    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
//
//    @Override
//    String defaultBaseUrl() {
//        return DEFAULT_BASE_URL;
//    }
//
//    @Override
//    public ModelProviderEnum provider() {
//        return ModelProviderEnum.QIAN_WEN;
//    }
//
//    @Override
//    public ModelResponse chat(ModelRequest request) {
//        long startMs = System.currentTimeMillis();
//        try {
//            Map<String, Object> mergedParams = mergeParams(request.getProvider(), request.getModelId(), request.getParams());
//            OpenAiChatOptions options = buildOptions(request, mergedParams);
//            List<Message> messages = toSpringAiMessages(request.getSystemPrompt(), request.getMessages());
//            ChatClient client = buildChatClient(request.getProvider(), request.getModelId());
//            ChatResponse response = client.prompt(new Prompt(messages, options))
//                    .call()
//                    .chatResponse();
//            return extractResponse(response, startMs);
//        } catch (Exception e) {
//            log.error("[QianWen] chat error, modelId={}", request.getModelId(), e);
//            return ModelResponse.builder()
//                    .success(false)
//                    .errorCode("QIANWEN_ERROR")
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
//        ChatClient client = buildChatClient(request.getProvider(), request.getModelId());
//        return client.prompt(new Prompt(messages, options))
//                .stream()
//                .chatResponse()
//                .map(this::toStreamChunk)
//                .onErrorResume(e -> {
//                    log.error("[QianWen] stream error, modelId={}", request.getModelId(), e);
//                    return Flux.just(ModelStreamChunk.builder()
//                            .done(true)
//                            .errorCode("QIANWEN_STREAM_ERROR")
//                            .errorMessage(e.getMessage())
//                            .build());
//                });
//    }
//
//    @Override
//    protected OpenAiChatOptions buildOptions(ModelRequest request, Map<String, Object> params) {
//        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
//                .model(request.getModelId());
//        // 千问思考模式：qwq 系列或 enable_thinking 参数 TODO
//        if (request.isEnableThinking()
//                || request.getModelId().toLowerCase().startsWith("qwq")) {
////            builder.withAdditionalOption("enable_thinking", true);
//            if (request.getThinkingBudget() != null) {
////                builder.withAdditionalOption("thinking_budget", request.getThinkingBudget());
//            }
//        }
//        // 千问网络搜索 TODO
//        if (request.isEnableSearch()) {
////            builder.withAdditionalOption("enable_search", true);
//        }
//        extractCommonParams(builder, params);
//        return builder.build();
//    }
//
//    private void extractCommonParams(OpenAiChatOptions.Builder builder, Map<String, Object> params) {
//        if (params == null) return;
//        if (params.containsKey("temperature")) {
//            builder.temperature(((Number) params.get("temperature")).doubleValue());
//        }
//        if (params.containsKey("max_tokens")) {
//            builder.maxTokens(((Number) params.get("max_tokens")).intValue());
//        }
//        if (params.containsKey("top_p")) {
//            builder.topP(((Number) params.get("top_p")).doubleValue());
//        }
//    }
//
//    @Override
//    protected ModelResponse extractResponse(ChatResponse response, long startMs) {
//        if (response == null || response.getResult() == null) {
//            return ModelResponse.builder().success(false).errorCode("EMPTY_RESPONSE").build();
//        }
//        var result = response.getResult();
//        var usage = response.getMetadata().getUsage();
//        String thinkContent = extractThinkContent(response);
//        return ModelResponse.builder()
//                .success(true)
//                .content(result.getOutput().getText())
//                .thinkContent(thinkContent)
//                .finishReason(result.getMetadata().getFinishReason())
//                .promptTokens(usage.getPromptTokens())
//                .completionTokens((int) usage.getCompletionTokens())
//                .totalTokens((int) usage.getTotalTokens())
//                .build();
//    }
//
//    private ModelStreamChunk toStreamChunk(ChatResponse response) {
//        if (response == null) return ModelStreamChunk.builder().done(false).build();
//        boolean isDone = response.getResult() != null && StringUtils.hasText(response.getResult().getMetadata().getFinishReason());
//        String delta = response.getResult() != null
//                ? response.getResult().getOutput().getText() : "";
//        String thinkDelta = extractThinkContent(response);
//        var usage = response.getMetadata().getUsage();
//        return ModelStreamChunk.builder()
//                .deltaContent(delta)
//                .deltaThinkContent(thinkDelta)
//                .done(isDone)
//                .finishReason(response.getResult().getMetadata().getFinishReason())
//                .promptTokens(usage.getPromptTokens())
//                .completionTokens(usage.getCompletionTokens())
//                .totalTokens(usage.getTotalTokens())
//                .build();
//    }
//
//    /**
//     * 千问 thinking content 在 result output metadata 中
//     */
//    private String extractThinkContent(ChatResponse response) {
//        try {
//            if (response.getResult() == null) return null;
//            var output = response.getResult().getOutput();
//            Object tc = output.getMetadata().get("reasoning_content");
//            return tc != null ? tc.toString() : null;
//        } catch (Exception e) {
//            return null;
//        }
//    }
//}
