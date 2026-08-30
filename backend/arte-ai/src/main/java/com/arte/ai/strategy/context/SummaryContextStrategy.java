package com.arte.ai.strategy.context;

import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.model.ModelMessage;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要压缩策略
 * 将较早的消息替换为摘要，保留最近 N 条完整消息
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:13 ✾
 **/
@Service("summaryContextStrategy")
public class SummaryContextStrategy implements ContextStrategy {
    /**
     * 摘要后保留的最近消息数
     */
    private static final int RECENT_KEEP_COUNT = 10;

    @Override
    public List<ModelMessage> buildContext(ConversationDto conversation,
                                           List<MessageDto> allMessages,
                                           String latestSummary) {
        List<ModelMessage> result = new ArrayList<>();
        // 如果有历史摘要，先注入摘要作为 system 消息
        if (StringUtils.hasText(latestSummary)) {
            result.add(ModelMessage.builder()
                    .role("system")
                    .content("以下是之前对话的摘要，请基于此继续对话：\n" + latestSummary)
                    .build());
        }
        // 保留最近 RECENT_KEEP_COUNT 条消息
        List<MessageDto> recent = allMessages.size() > RECENT_KEEP_COUNT
                ? allMessages.subList(allMessages.size() - RECENT_KEEP_COUNT, allMessages.size())
                : allMessages;
        recent.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleteFlag()))
                .map(m -> ModelMessage.builder()
                        .role(m.getRole().getValue())
                        .content(m.getContent())
                        .thinkContent(m.getReasoningContent())
                        .build())
                .forEach(result::add);
        return result;
    }
}
