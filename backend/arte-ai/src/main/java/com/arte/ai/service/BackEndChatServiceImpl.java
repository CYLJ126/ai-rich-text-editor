package com.arte.ai.service;

import cn.hutool.core.util.IdUtil;
import com.arte.ai.advisor.*;
import com.arte.ai.api.BackEndChatService;
import com.arte.ai.api.MessageService;
import com.arte.ai.api.ModelAdapter;
import com.arte.ai.common.enums.KnowledgeBaseTypeEnum;
import com.arte.ai.common.enums.ReasoningEffortEnum;
import com.arte.ai.mcp.server.ArteMcpTools;
import com.arte.ai.pojo.EditMessageRequestDto;
import com.arte.ai.pojo.RegenerateRequestDto;
import com.arte.ai.pojo.chat.ChatRagRequestDto;
import com.arte.ai.pojo.chat.ChatRequestDto;
import com.arte.ai.pojo.chat.ChatRequestParam;
import com.arte.ai.pojo.model.ModelConfigDto;
import com.arte.ai.pojo.model.ModelStreamChunk;
import com.arte.ai.strategy.model.ModelAdapterFactory;
import com.arte.ai.tool.RequestParamHandler;
import com.arte.core.serialize.SerializerFactory;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class BackEndChatServiceImpl implements BackEndChatService {

    @Resource
    private ModelAdapterFactory modelAdapterFactory;

    private final ObjectMapper objectMapper = SerializerFactory.buildStreamJsonMapper();

    @Resource
    private PersistentChatMemoryAdvisor persistentChatMemoryAdvisor;

    @Resource
    private RetrievalAugmentationAdvisorFactory retrievalAugmentationAdvisorFactory;

    @Resource
    private RequestHandlingAdvisor requestLogAdvisor;

    @Resource
    private GenerateTypeHandlingAdvisor generateTypeHandlingAdvisor;

    @Resource
    private RequestParamHandler requestParamHandler;

    @Resource
    protected MessageService messageService;

    @Resource
    private ArteMcpTools arteMcpTools;

    @Override
    public Flux<@NonNull String> streamChat(ChatRequestParam request) {
        // 首先填充完整请求参数
        ChatRequestDto chatRequestDto = requestParamHandler.handleChatRequest(request);
        return executeChat(chatRequestDto, request.getContent());
    }

    @Override
    public Flux<@NonNull String> streamGenerate(ChatRequestParam chatRequestParam) {
        if (Objects.isNull(chatRequestParam) || Objects.isNull(chatRequestParam.getGenerateType())) {
            return Flux.error(new IllegalArgumentException("生成类型不能为空"));
        }
        // 流式生成默认关闭推理/思考；请求显式设置时尊重请求值。
        if (Objects.isNull(chatRequestParam.getReasoningEffort())) {
            chatRequestParam.setReasoningEffort(ReasoningEffortEnum.NONE);
        }
        // 流式生成时，消息 ID 由后端自行生成
        chatRequestParam.setUserMessageId(IdUtil.fastSimpleUUID());
        chatRequestParam.setAssistantMessageId(IdUtil.fastSimpleUUID());
        // 首先填充完整请求参数
        ChatRequestDto chatRequestDto = requestParamHandler.handleGenerateRequest(chatRequestParam);

        // 组装需要的 Advisor
        List<Advisor> advisors = new ArrayList<>();
        // 文档检索增强，先只支持单个知识库，优先级 10
        KnowledgeBaseTypeEnum knowledgeBaseTypeEnum = getKnowledgeBaseTypeEnum(chatRequestDto);
        if (knowledgeBaseTypeEnum != null) {
            advisors.add(retrievalAugmentationAdvisorFactory.getAdvisor(knowledgeBaseTypeEnum));
        }
        // 生成类型处理，优先级 998
        advisors.add(generateTypeHandlingAdvisor);
        // 日志打印，优先级 999
        advisors.add(requestLogAdvisor);

        // 构建 ChatClient 并发起请求
        ModelAdapter modelAdapter = modelAdapterFactory.getAdapter(chatRequestDto.getProvider(), chatRequestDto.getModelId());
        ChatClient chatClient = modelAdapter.buildChatClient(chatRequestDto);
        ChatClient.StreamResponseSpec streamResponseSpec = chatClient.prompt()
                .advisors(aSpec -> {
                    // 1. 给 MemoryAdvisor（它认 ChatMemory.CONVERSATION_ID）
                    aSpec.param(AbstractAdvisor.CONVERSATION_ID, IdUtil.fastSimpleUUID());
                    // 2. 自定义业务对象
                    aSpec.param(AbstractAdvisor.REQUEST_DTO, chatRequestDto);
                })
                .advisors(advisors)
                .tools(arteMcpTools)
                .stream();
        return fluxReturn(streamResponseSpec, chatRequestParam.getAssistantMessageId());
    }

    @Override
    public Flux<@NonNull String> regenerate(RegenerateRequestDto request) {
        ChatRequestDto chatRequestDto = requestParamHandler.handleRegenerateRequest(request);
        return executeChat(chatRequestDto, chatRequestDto.getContent());
    }

    @Override
    public Flux<@NonNull String> editAndResend(EditMessageRequestDto request) {
        // TODO
        return null;
    }

    private Flux<@NonNull String> executeChat(ChatRequestDto chatRequestDto, String content) {
        // 组装需要的 Advisor
        List<Advisor> advisors = new ArrayList<>();
        // 消息持久化、历史记忆增强，优先级 1
        advisors.add(persistentChatMemoryAdvisor);
        ModelConfigDto defaultModelConfig = chatRequestDto.getDefaultModelConfig();
        ModelAdapter defaultModelAdapter = modelAdapterFactory.getAdapter(defaultModelConfig.getProvider(), defaultModelConfig.getModelId());
        // 查询优化增强，优先级 2
        advisors.add(new QueryOptimizationAdvisor(ChatClient.builder(defaultModelAdapter
                .getChatModel(defaultModelConfig, null)), messageService).withOrder(2));
        // 文档检索增强，先只支持单个知识库，优先级 10
        if (chatRequestDto.getKnowledgeBaseId() != null) {
            advisors.add(retrievalAugmentationAdvisorFactory.getAdvisor(chatRequestDto.getKnowledgeBaseId()));
        }
        // 日志打印，优先级 999
        advisors.add(requestLogAdvisor);

        // 构建 ChatClient 并发起请求
        ModelAdapter modelAdapter = modelAdapterFactory.getAdapter(chatRequestDto.getProvider(), chatRequestDto.getModelId());
        ChatClient chatClient = modelAdapter.buildChatClient(chatRequestDto);
        ChatClient.StreamResponseSpec streamResponseSpec = chatClient.prompt()
                .user(content)
                .advisors(aSpec -> {
                    // 1. 给 MemoryAdvisor（它认 ChatMemory.CONVERSATION_ID）
                    aSpec.param(AbstractAdvisor.CONVERSATION_ID, chatRequestDto.getConvId());
                    // 2. 自定义业务对象
                    aSpec.param(AbstractAdvisor.REQUEST_DTO, chatRequestDto);
                })
                .advisors(advisors)
                .tools(arteMcpTools)
                .stream();
        return fluxReturn(streamResponseSpec, chatRequestDto.getAssistantMessageId());
    }

    private @NonNull Flux<String> fluxReturn(
            ChatClient.StreamResponseSpec streamResponseSpec, String assistantMessageId) {
        // 原子变量，收集最后一个 chunk 的 token
        AtomicReference<Integer> promptTokens = new AtomicReference<>(0);
        AtomicReference<Integer> completionTokens = new AtomicReference<>(0);
        AtomicReference<Integer> totalTokens = new AtomicReference<>(0);
        AtomicReference<String> finishReason = new AtomicReference<>("stop");

        return streamResponseSpec.chatClientResponse()
                .map(resp -> convertChunk(resp, assistantMessageId, promptTokens, completionTokens, totalTokens, finishReason))
                // 用 concatWith(Mono.fromCallable) 延迟求值，等流真正结束后再构建最终包
                .concatWith(Mono.fromCallable(() -> buildDoneJson(
                        finishReason.get(),
                        assistantMessageId,
                        promptTokens.get(),
                        completionTokens.get(),
                        totalTokens.get()
                )))
                .onErrorResume(e -> {
                    log.error("流式响应异常", e);
                    return Flux.just(buildErrorJson(e.getMessage(), assistantMessageId));
                });
    }

    private String convertChunk(
            ChatClientResponse resp,
            String messageId,
            AtomicReference<Integer> promptTokens,
            AtomicReference<Integer> completionTokens,
            AtomicReference<Integer> totalTokens,
            AtomicReference<String> finishReason) {

        String delta = "";
        String thinkDelta = "";

        if (resp.chatResponse() != null && resp.chatResponse().getResult() != null) {
            AssistantMessage am = resp.chatResponse().getResult().getOutput();
            delta = am.getText() != null ? am.getText() : "";

            // 思考内容（如果模型支持，比如 DeepSeek-R1）
            Map<String, Object> meta = am.getMetadata();
            thinkDelta = firstMetadataText(meta,
                    AbstractAdvisor.REASONING_CONTENT,
                    "reasoning_content",
                    "reasoningSummary",
                    "reasoning_summary",
                    "reasoning_summary_text");

            // 提取 token（流式最后一个 chunk 才有非0值）
            ChatResponseMetadata md = resp.chatResponse().getMetadata();
            int pt = md.getUsage().getPromptTokens();
            int ct = md.getUsage().getCompletionTokens();
            int tt = md.getUsage().getTotalTokens();
            if (pt > 0) promptTokens.set(pt);
            if (ct > 0) completionTokens.set(ct);
            if (tt > 0) totalTokens.set(tt);

            // 提取 finishReason
            resp.chatResponse().getResult();
            String reason = resp.chatResponse().getResult().getMetadata().getFinishReason();
            if (reason != null && !reason.isBlank()) {
                finishReason.set(reason);
            }
        }

        ModelStreamChunk chunk = new ModelStreamChunk();
        chunk.setDeltaContent(delta);
        chunk.setDeltaThinkContent(thinkDelta);
        chunk.setDone(false);

        return buildChunkJson(chunk, messageId);
    }

    private KnowledgeBaseTypeEnum getKnowledgeBaseTypeEnum(ChatRequestDto chatRequestDto) {
        if (chatRequestDto.getKnowledgeBaseId() != null) {
            return chatRequestDto.getKnowledgeBaseId();
        }
        ChatRagRequestDto chatRagRequest = chatRequestDto.getChatRagRequest();
        if (chatRagRequest != null && chatRagRequest.getKnowledgeBaseType() != null) {
            return chatRagRequest.getKnowledgeBaseType();
        }
        return null;
    }

    private String firstMetadataText(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    /**
     * 构建流结束包（done=true，携带 token 统计）
     */
    private String buildDoneJson(
            String finishReason,
            String messageId,
            int promptTokens,
            int completionTokens,
            int totalTokens) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("delta", "");
            map.put("thinkDelta", "");
            map.put("done", true);
            map.put("finishReason", finishReason);
            map.put("promptTokens", promptTokens);
            map.put("completionTokens", completionTokens);
            map.put("totalTokens", totalTokens);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[BackendInteraction] buildDoneJson 序列化失败", e);
            return "{\"done\":true}";
        }
    }

    /**
     * 构建普通 chunk 的紧凑 JSON
     * 示例：{"messageId":"xxx","delta":"你好","thinkDelta":null,"done":false}
     */
    private String buildChunkJson(ModelStreamChunk chunk, String messageId) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("delta", chunk.getDeltaContent());
            map.put("thinkDelta", chunk.getDeltaThinkContent());
            map.put("done", chunk.isDone());
            if (chunk.isDone()) {
                map.put("finishReason", chunk.getFinishReason());
                map.put("promptTokens", chunk.getPromptTokens());
                map.put("completionTokens", chunk.getCompletionTokens());
                map.put("totalTokens", chunk.getTotalTokens());
                map.put("thinkTokens", chunk.getThinkTokens());
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[BackendInteraction] buildChunkJson 序列化失败", e);
            return "{\"error\":\"序列化失败\",\"done\":false}";
        }
    }

    /**
     * 构建错误 JSON
     * 示例：{"messageId":"xxx","error":true,"errorMessage":"xxx","done":true}
     */
    private String buildErrorJson(String errorMessage, String messageId) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("messageId", messageId);
            map.put("error", Map.of("message", Objects.toString(errorMessage, "生成失败")));
            map.put("done", true);
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[BackendInteraction] buildErrorJson 序列化失败", e);
            return "{\"error\":true,\"done\":true}";
        }
    }

}
