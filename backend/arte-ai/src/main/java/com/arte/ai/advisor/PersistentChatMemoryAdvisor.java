package com.arte.ai.advisor;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.arte.ai.api.ConversationService;
import com.arte.ai.api.MessageService;
import com.arte.ai.api.ModelAdapter;
import com.arte.ai.common.enums.ContextStrategyEnum;
import com.arte.ai.common.enums.MessageRoleEnum;
import com.arte.ai.common.enums.MessageStatusEnum;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.strategy.model.ModelAdapterFactory;
import com.arte.core.enums.CurrencyEnum;
import com.arte.core.exception.ChatException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * 消息持久化 Advisor
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/7/10 20:50 ✾
 **/
@NullMarked
@Service
@Slf4j
public class PersistentChatMemoryAdvisor extends AbstractAdvisor {

    @Resource
    protected MessageService messageService;

    @Resource
    protected ConversationService conversationService;

    @Resource
    protected ModelAdapterFactory modelAdapterFactory;

    @Override
    public int getOrder() {
        return 1;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        ChatRequestDto chatRequest = getChatRequestDto(chatClientRequest);
        // 是否为重新生成请求
        boolean regenerate = Boolean.TRUE.equals(chatRequest.getRegenerate());
        Prompt incomingPrompt = chatClientRequest.prompt();
        Message newMessage = incomingPrompt.getLastUserOrToolResponseMessage();
        // ToolCallingAdvisor 会在工具执行后携带 ToolResponseMessage 重新进入下游 Advisor。
        // 该轮 Prompt 已包含完整上下文，不能再次加载历史消息，否则会重复拼接历史记录。
        if (newMessage instanceof ToolResponseMessage toolResponseMessage) {
            if (!regenerate) {
                MessageDto toolMessage = transferToolResponseMessage(toolResponseMessage, chatRequest);
                if (!messageService.save(toolMessage)) {
                    throw new ChatException("error.ai.toolResponseSaveFailed");
                }
                conversationService.incrementMessageCount(chatRequest.getConvId(), 1, chatRequest.getUserName());
                log.info("工具响应消息已保存，消息 ID：{}，工具数量：{}", toolMessage.getMessageId(), toolResponseMessage.getResponses().size());
            }
            return chatClientRequest;
        }
        // 加载历史消息
        List<Message> messages = getHistoryMessages(chatRequest);
        messages.addAll(incomingPrompt.getInstructions());
        // 系统消息放最前面
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                Message systemMessage = messages.remove(i);
                messages.addFirst(systemMessage);
                break;
            }
        }
        Prompt prompt = incomingPrompt.mutate().messages(messages).build();
        // 保存 UserMessage
        UserMessage userMessage = (UserMessage) newMessage;
        String userMessageId = requireMessageId(chatRequest.getUserMessageId(), "用户消息 ID 不能为空");
        if (!regenerate) {
            MessageDto dbMessage = transferMessage(userMessage, chatRequest);
            dbMessage.setMessageId(userMessageId);
            dbMessage.setStatus(MessageStatusEnum.COMPLETED);
            messageService.save(dbMessage);
        }
        // 将所有 messageId 放入 context
        Map<String, Object> ctx = putIntoRequest(true, chatClientRequest,
                MESSAGE_DIGEST, StrUtil.sub(userMessage.getText(), 0, 20),
                USER_MESSAGE_ID_KEY, userMessageId,
                STREAMING_START_TIME_KEY, System.currentTimeMillis());
        log.info("用户消息 ID：{}", userMessageId);
        return chatClientRequest.mutate()
                .prompt(prompt)
                .context(ctx)
                .build();
    }


    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        if (chatClientResponse.chatResponse() == null) {
            return chatClientResponse;
        }
        ResponsePersistenceResult result = persistResponseMessages(chatClientResponse);
        afterResponsePersisted(chatClientResponse, result);
        return enrichResponseContext(chatClientResponse, result);
    }

    protected void afterResponsePersisted(ChatClientResponse response, ResponsePersistenceResult result) {
        String conversationId = getConversationId(response);
        log.info("会话 ID：{}，耗时：{} ms，响应元数据：{}", conversationId, result.latency(), result.metadata());
        if (Boolean.TRUE.equals(result.chatRequest().getRegenerate())) {
            return;
        }
        // 工具调用请求是一次中间响应。记录消息数量，但最终回复完成前不更新会话摘要和最后消息。
        if (result.toolCallResponse()) {
            conversationService.incrementMessageCount(conversationId, result.messages().size(), result.chatRequest().getUserName());
            return;
        }
        List<MessageDto> messages = result.messages();
        conversationService.updateWhenResponse(conversationId, messages.getLast().getMessageId(),
                LocalDateTime.now(), getFromResponse(response, MESSAGE_DIGEST),
                messages.size() + 1, result.chatRequest().getUserName());
    }

    protected ResponsePersistenceResult persistResponseMessages(ChatClientResponse response) {
        var chatResponse = Objects.requireNonNull(response.chatResponse(), "AI 响应不能为空");
        Map<String, @Nullable Object> context = response.context();
        ChatRequestDto chatRequest = getChatRequestDto(response);

        long latency = calculateLatency(context);
        ChatResponseMetadata metadata = chatResponse.getMetadata();

        Map<String, Object> adapterContext = new HashMap<>();
        adapterContext.put(REQUEST_DTO, chatRequest);
        ModelAdapter modelAdapter = modelAdapterFactory.getAdapter(chatRequest.getProvider(), chatRequest.getModelId());
        List<Message> assistantMessages = modelAdapter.handleResponse(chatResponse, adapterContext);
        // 判断是否为工具调用响应
        boolean toolCallResponse = assistantMessages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast).anyMatch(AssistantMessage::hasToolCalls);
        List<MessageDto> messages = new ArrayList<>(assistantMessages.size());
        for (int i = 0; i < assistantMessages.size(); i++) {
            Message chatMessage = assistantMessages.get(i);
            // TODO reasoning_token/first_token_ms/error_code/error_message/retry_count
            MessageDto message = transferResponseMessage(chatMessage, chatRequest, latency, metadata,
                    context.get(REASONING_CONTENT), context.get(MODEL_PARAM));
            if (toolCallResponse) {
                // assistantMessageId 只保留给最终助手回复，中间工具调用使用独立消息 ID。
                message.ensureMessageId(StrUtil.EMPTY);
            } else if (i == 0) {
                message.setMessageId(requireMessageId(chatRequest.getAssistantMessageId(), "AI 响应消息 ID 不能为空"));
            } else {
                message.ensureMessageId(StrUtil.EMPTY);
            }
            customizeResponseMessage(message, chatRequest);
            messages.add(message);
        }
        if (Boolean.TRUE.equals(chatRequest.getRegenerate())) {
            if (!toolCallResponse) {
                replaceRegeneratedMessage(messages, chatRequest);
            }
        } else {
            messageService.saveBatch(messages);
        }
        log.debug("响应消息：{}", assistantMessages);
        return new ResponsePersistenceResult(chatRequest, messages, latency, metadata, toolCallResponse);
    }

    private void replaceRegeneratedMessage(List<MessageDto> messages, ChatRequestDto chatRequest) {
        if (messages.isEmpty()) {
            throw new ChatException("error.ai.noSaveableResponse");
        }
        MessageDto original = messageService.getByMessageId(chatRequest.getAssistantMessageId());
        if (original == null) {
            throw new ChatException("error.ai.regenerateMessageNotFound");
        }
        MessageDto replacement = messages.getFirst();
        original.setOptimizedContent(replacement.getContent());
        original.setRetryCount(Optional.ofNullable(original.getRetryCount()).orElse(0) + 1);
        original.setUpdateBy(replacement.getUpdateBy());
        original.setUpdateTime(LocalDateTime.now());
        if (!messageService.updateById(original)) {
            throw new ChatException("error.ai.regenerateSaveFailed");
        }
    }

    protected void customizeResponseMessage(MessageDto message, ChatRequestDto chatRequest) {
        // 子类可按业务场景补充消息属性
    }

    protected ChatClientResponse enrichResponseContext(
            ChatClientResponse response, ResponsePersistenceResult result) {
        var usage = result.metadata().getUsage();
        Map<String, Object> context = putIntoResponse(true, response,
                LATENCY, result.latency(),
                "promptTokens", usage.getPromptTokens(),
                "completionTokens", usage.getCompletionTokens(),
                "totalTokens", usage.getTotalTokens());
        return response.mutate().context(context).build();
    }

    private long calculateLatency(Map<String, @Nullable Object> context) {
        Object startTime = context.get(STREAMING_START_TIME_KEY);
        return startTime instanceof Long value ? System.currentTimeMillis() - value : -1L;
    }

    protected record ResponsePersistenceResult(
            ChatRequestDto chatRequest,
            List<MessageDto> messages,
            long latency,
            ChatResponseMetadata metadata,
            boolean toolCallResponse) {
    }

    protected MessageDto transferResponseMessage(Message chatMsg, ChatRequestDto chatRequestDto, Long latency, ChatResponseMetadata respMetaData, @Nullable Object reasoningContent, @Nullable Object modelParam) {
        Map<String, Object> metadata = chatMsg.getMetadata();
        MessageDto dbMessage = transferMessage(chatMsg, chatRequestDto);
        dbMessage.setRequestId((String) metadata.get("requestId"));
        dbMessage.setLatencyMs(latency.intValue());
        dbMessage.setPromptToken(respMetaData.getUsage().getPromptTokens());
        dbMessage.setCompletionToken(respMetaData.getUsage().getCompletionTokens());
        dbMessage.setTotalToken(respMetaData.getUsage().getTotalTokens());
        dbMessage.setReasoningContent(reasoningContent == null ? "" : reasoningContent.toString());
        dbMessage.setModelParam(modelParam == null ? Map.of() : (Map<String, Object>) modelParam);
        dbMessage.setPromptCost((BigDecimal) metadata.get("promptCost"));
        dbMessage.setCompletionCost((BigDecimal) metadata.get("completionCost"));
        dbMessage.setCurrency((CurrencyEnum) metadata.get("currency"));
        dbMessage.setModelId(chatRequestDto.getModelAutoId());
        dbMessage.setStatus(MessageStatusEnum.COMPLETED);
        if (chatMsg instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            dbMessage.setToolCalls(toToolCallMaps(assistantMessage.getToolCalls()));
            dbMessage.setFinishReason("tool_calls");
        }
        return dbMessage;
    }

    /**
     * 将一次工具执行结果转换为 role=tool 的持久化消息。
     */
    protected MessageDto transferToolResponseMessage(ToolResponseMessage toolResponseMessage, ChatRequestDto chatRequest) {
        MessageDto dbMessage = transferMessage(toolResponseMessage, chatRequest);
        dbMessage.ensureMessageId(StrUtil.EMPTY);
        dbMessage.setModelId(chatRequest.getModelAutoId());
        dbMessage.setStatus(MessageStatusEnum.COMPLETED);
        dbMessage.setContent(toolResponseMessage.getResponses().stream()
                .map(ToolResponseMessage.ToolResponse::responseData)
                .collect(java.util.stream.Collectors.joining("\n")));
        dbMessage.setToolCalls(toToolResponseMaps(toolResponseMessage.getResponses()));
        return dbMessage;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        // Get the scheduler from BaseAdvisor
        Scheduler scheduler = this.getScheduler();
        StringBuilder reasoningContentBuilder = new StringBuilder();

        // Process the request with the before method
        return Mono.just(chatClientRequest)
                .publishOn(scheduler)
                .map(request -> this.before(request, streamAdvisorChain))
                .flatMapMany(streamAdvisorChain::nextStream)
                .doOnNext(chunk -> extractAndAccumulateReasoningContent(chunk, reasoningContentBuilder))
                .transform(flux -> streamAndAggregate(flux, response -> {
                    putIntoResponse(false, response, REASONING_CONTENT, reasoningContentBuilder.toString());
                    this.after(response, streamAdvisorChain);
                }));
    }

    /**
     * Keep the provider response streaming to the caller while aggregating a side copy
     * for persistence. Returning ChatClientMessageAggregator directly would collapse
     * the whole stream into one response.
     */
    Flux<ChatClientResponse> streamAndAggregate(
            Flux<ChatClientResponse> source, Consumer<ChatClientResponse> aggregatedResponseConsumer) {
        return source.publish(shared -> {
            Flux<ChatClientResponse> persistence = new ChatClientMessageAggregator()
                    .aggregateChatClientResponse(shared, aggregatedResponseConsumer)
                    .thenMany(Flux.empty());
            return Flux.merge(shared, persistence);
        });
    }

    private List<Message> getHistoryMessages(ChatRequestDto chatRequest) {
        // TODO 暂仅支持 ContextStrategyEnum.WINDOW 模式
        if (chatRequest.getContextStrategy() != ContextStrategyEnum.WINDOW) {
            return new ArrayList<>();
        }
        List<MessageDto> dbMessages;
        if (Boolean.TRUE.equals(chatRequest.getRegenerate())) {
            MessageDto userMessage = messageService.getByMessageId(chatRequest.getUserMessageId());
            if (userMessage == null) {
                throw new ChatException("error.ai.parentReplyNotFound");
            }
            dbMessages = messageService.selectConversationContextMessagesBefore(
                    chatRequest.getConvId(), userMessage.getId(), chatRequest.getContextWindow());
        } else {
            dbMessages = messageService.selectConversationContextMessages(
                    chatRequest.getConvId(), chatRequest.getContextWindow());
        }
        List<Message> aiMessagesList = new ArrayList<>();
        if (CollUtil.isEmpty(dbMessages)) {
            return aiMessagesList;
        }
        // 按 ID 从小到大排序
        dbMessages.sort(Comparator.comparing(MessageDto::getId));
        for (MessageDto dbMessage : dbMessages) {
            switch (dbMessage.getRole()) {
                case USER:
                    UserMessage userMessage = UserMessage.builder().text(dbMessage.getContent())
                            .media(dbMessage.getAttachments())
                            .metadata(dbMessage.toMap())
                            .build();
                    aiMessagesList.add(userMessage);
                    break;
                case ASSISTANT:
                    AssistantMessage assistantMessage = AssistantMessage.builder()
                            .content(dbMessage.getContent())
                            .toolCalls(toAssistantToolCalls(dbMessage.getToolCalls()))
                            .media(dbMessage.getAttachments())
                            .build();
                    aiMessagesList.add(assistantMessage);
                    break;
                case SYSTEM:
                    SystemMessage systemMessage = SystemMessage.builder()
                            .text(dbMessage.getContent())
                            .metadata(dbMessage.toMap())
                            .build();
                    aiMessagesList.add(systemMessage);
                    break;
                case TOOL:
                    List<ToolResponseMessage.ToolResponse> responses = toToolResponses(dbMessage.getToolCalls());
                    if (hasMatchingToolCall(aiMessagesList, responses)) {
                        aiMessagesList.add(ToolResponseMessage.builder()
                                .responses(responses)
                                .metadata(dbMessage.toMap())
                                .build());
                    } else {
                        log.warn("跳过缺少配对 assistant tool_calls 的工具响应消息，messageId={}",
                                dbMessage.getMessageId());
                    }
                    break;
            }
        }
        return aiMessagesList;
    }

    static List<Map<String, Object>> toToolCallMaps(List<AssistantMessage.ToolCall> toolCalls) {
        if (CollUtil.isEmpty(toolCalls)) {
            return List.of();
        }
        return toolCalls.stream().map(toolCall -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", toolCall.id());
            value.put("type", toolCall.type());
            value.put("name", toolCall.name());
            value.put("arguments", toolCall.arguments());
            return value;
        }).toList();
    }

    static List<Map<String, Object>> toToolResponseMaps(List<ToolResponseMessage.ToolResponse> responses) {
        if (CollUtil.isEmpty(responses)) {
            return List.of();
        }
        return responses.stream().map(response -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", response.id());
            value.put("name", response.name());
            value.put("responseData", response.responseData());
            return value;
        }).toList();
    }

    static List<AssistantMessage.ToolCall> toAssistantToolCalls(List<Map<String, Object>> toolCalls) {
        if (CollUtil.isEmpty(toolCalls)) {
            return List.of();
        }
        return toolCalls.stream()
                .filter(value -> value.containsKey("arguments"))
                .map(value -> new AssistantMessage.ToolCall(
                        mapText(value, "id"), mapText(value, "type"),
                        mapText(value, "name"), mapText(value, "arguments")))
                .toList();
    }

    static List<ToolResponseMessage.ToolResponse> toToolResponses(List<Map<String, Object>> toolCalls) {
        if (CollUtil.isEmpty(toolCalls)) {
            return List.of();
        }
        return toolCalls.stream()
                .filter(value -> value.containsKey("responseData"))
                .map(value -> new ToolResponseMessage.ToolResponse(
                        mapText(value, "id"), mapText(value, "name"), mapText(value, "responseData")))
                .toList();
    }

    private static String mapText(Map<String, Object> value, String key) {
        return Objects.toString(value.get(key), "");
    }

    private static boolean hasMatchingToolCall(List<Message> messages, List<ToolResponseMessage.ToolResponse> responses) {
        if (CollUtil.isEmpty(messages) || CollUtil.isEmpty(responses)
                || !(messages.getLast() instanceof AssistantMessage assistantMessage)
                || !assistantMessage.hasToolCalls()) {
            return false;
        }
        Set<String> toolCallIds = assistantMessage.getToolCalls().stream()
                .map(AssistantMessage.ToolCall::id)
                .collect(java.util.stream.Collectors.toSet());
        return responses.stream().allMatch(response -> toolCallIds.contains(response.id()));
    }

    protected void extractAndAccumulateReasoningContent(ChatClientResponse chunk, StringBuilder reasoningContentBuilder) {
        assert chunk.chatResponse() != null;
        String reasoningContent = getFromResponse(chunk, REASONING_CONTENT);
        Generation generation = chunk.chatResponse().getResult();
        assert generation != null;
        AssistantMessage assistantMessage = generation.getOutput();
        if (StrUtil.isBlank(reasoningContent)) {
            reasoningContent = firstNonBlank(
                    getFromResponse(chunk, "reasoning_content"),
                    metadataText(assistantMessage, REASONING_CONTENT),
                    metadataText(assistantMessage, "reasoning_content"),
                    metadataText(assistantMessage, "reasoningSummary"),
                    metadataText(assistantMessage, "reasoning_summary"),
                    metadataText(assistantMessage, "reasoning_summary_text"));
        }
        if (StrUtil.isBlank(reasoningContent) && assistantMessage instanceof DeepSeekAssistantMessage deepSeekAm) {
            reasoningContent = deepSeekAm.getReasoningContent();
        }
        if (StrUtil.isNotBlank(reasoningContent)) {
            assistantMessage.getMetadata().put(REASONING_CONTENT, reasoningContent);
            reasoningContentBuilder.append(reasoningContent);
        }
    }

    private String metadataText(AssistantMessage message, String key) {
        Object value = message.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    protected MessageDto transferMessage(Message chatMsg, ChatRequestDto chatRequest) {
        MessageDto dbMessage = new MessageDto();
        dbMessage.setConvId(chatRequest.getConvId());
        dbMessage.setCreateBy(chatRequest.getUserName());
        dbMessage.setUpdateBy(chatRequest.getUserName());
        LocalDateTime now = LocalDateTime.now();
        dbMessage.setCreateTime(now);
        dbMessage.setUpdateTime(now);
        dbMessage.setContent(chatMsg.getText());
        dbMessage.setRole(MessageRoleEnum.fromMessageType(chatMsg.getMessageType()));
        return dbMessage;
    }

    protected String requireMessageId(@Nullable String messageId, String errorMessage) {
        if (StrUtil.isBlank(messageId)) {
            throw new ChatException(errorMessage);
        }
        return messageId;
    }
}
