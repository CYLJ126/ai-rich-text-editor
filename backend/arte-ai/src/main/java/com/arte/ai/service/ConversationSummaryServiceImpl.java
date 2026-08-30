package com.arte.ai.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.arte.ai.api.ConversationSummaryService;
import com.arte.ai.api.MessageService;
import com.arte.ai.api.ModelAdapter;
import com.arte.ai.common.enums.ModelProviderEnum;
import com.arte.ai.mapper.ConversationSummaryMapper;
import com.arte.ai.pojo.conversation.ConversationSummaryDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.model.ModelMessage;
import com.arte.ai.pojo.model.ModelRequest;
import com.arte.ai.pojo.model.ModelResponse;
import com.arte.ai.strategy.model.ModelAdapterFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话摘要 Service
 * 异步对历史消息进行摘要压缩，降低 token 消耗
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSummaryServiceImpl extends ServiceImpl<ConversationSummaryMapper, ConversationSummaryDto> implements ConversationSummaryService {

    private final MessageService messageService;
    private final ModelAdapterFactory adapterRegistry;

    /**
     * 触发摘要的消息轮数阈值
     */
    private static final int SUMMARY_TRIGGER_ROUNDS = 20;
    /**
     * 摘要时使用的轻量模型
     */
    private static final String SUMMARY_MODEL_ID = "deepseek-v4-flash";

    @Override
    public String getLatestSummaryContent(String convId) {
        ConversationSummaryDto latest = lambdaQuery()
                .eq(ConversationSummaryDto::getConvId, convId)
                .orderByDesc(ConversationSummaryDto::getCreateTime)
                .last("LIMIT 1")
                .one();
        return latest != null ? latest.getSummaryContent() : null;
    }

    /**
     * 检查是否需要触发摘要并异步执行
     */
    @Override
    @Async("aiTaskExecutor")
    public void checkAndSummaryAsync(String convId) {
        try {
            long totalMsgCount = messageService.lambdaQuery()
                    .eq(MessageDto::getConvId, convId)
                    .eq(MessageDto::getDeleteFlag, false)
                    .count();

            // 获取上次摘要覆盖的消息数
            ConversationSummaryDto lastSummary = lambdaQuery()
                    .eq(ConversationSummaryDto::getConvId, convId)
                    .orderByDesc(ConversationSummaryDto::getCreateTime)
                    .last("LIMIT 1")
                    .one();

            long lastCoveredCount = lastSummary != null
                    ? lastSummary.getCoveredMessageCount() : 0;
            long newMessageCount = totalMsgCount - lastCoveredCount;

            if (newMessageCount < SUMMARY_TRIGGER_ROUNDS * 2L) {
                return; // 未达到触发条件
            }

            log.info("[SummaryService] 触发摘要, convId={}, newMsgCount={}", convId, newMessageCount);
            doSummary(convId, (int) totalMsgCount);
        } catch (Exception e) {
            log.error("[SummaryService] 摘要异步执行失败, convId={}", convId, e);
        }
    }

    private void doSummary(String convId, int totalCount) {
        // 获取需要摘要的消息（排除最近10条，保留完整上下文）
        int keepRecent = 10;
        List<MessageDto> allMessages = messageService.selectMainBranchMessages(convId, totalCount);
        if (CollectionUtils.isEmpty(allMessages) || allMessages.size() <= keepRecent) {
            return;
        }

        List<MessageDto> toSummarize = allMessages.subList(0, allMessages.size() - keepRecent);

        // 构建摘要 Prompt
        String summaryPrompt = buildSummaryPrompt(toSummarize);

        // 调用轻量模型生成摘要
        ModelAdapter adapter = adapterRegistry.getAdapter(ModelProviderEnum.DEEPSEEK, SUMMARY_MODEL_ID);
        ModelRequest request = ModelRequest.builder()
                .modelId(SUMMARY_MODEL_ID)
                .systemPrompt("你是一个对话摘要助手，请将以下对话内容提炼为简洁的摘要，" +
                        "保留关键信息和用户意图，摘要使用中文，不超过500字。")
                .messages(List.of(ModelMessage.builder()
                        .role("user")
                        .content(summaryPrompt)
                        .build()))
                .stream(false)
                .build();

//        ModelResponse response = adapter.chat(request);
        ModelResponse response = null;
        if (!response.isSuccess() || response.getContent() == null) {
            log.warn("[SummaryService] 摘要生成失败, convId={}", convId);
            return;
        }

        // 持久化摘要
        ConversationSummaryDto summary = new ConversationSummaryDto();
        summary.setConvId(convId);
        summary.setSummaryContent(response.getContent());
        summary.setCoveredMessageCount(toSummarize.size());
        summary.setStartMessageId(toSummarize.getFirst().getMessageId());
        summary.setEndMessageId(toSummarize.getLast().getMessageId());
        summary.setTokensBefore(response.getPromptTokens());
        summary.setTokensAfter(response.getCompletionTokens());
        summary.setCreateTime(LocalDateTime.now());
        save(summary);

        log.info("[SummaryService] 摘要生成完成, convId={}, coveredCount={}",
                convId, toSummarize.size());
    }

    private String buildSummaryPrompt(List<MessageDto> messages) {
        return messages.stream()
                .map(m -> String.format("[%s]: %s",
                        m.getRole().getValue().equals("user") ? "用户" : "助手",
                        m.getContent()))
                .collect(Collectors.joining("\n"));
    }
}