package com.nip.ai.strategy.context;

import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.ai.pojo.model.ModelMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 滑动窗口策略
 * 取最近 N 条消息作为上下文
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:12 ✾
 **/
@Service("windowContextStrategy")
public class WindowContextStrategy implements ContextStrategy {
    @Override
    public List<ModelMessage> buildContext(ConversationDto conversation,
                                           List<MessageDto> allMessages,
                                           String latestSummary) {
        int windowSize = conversation.getContextWindow() != null
                ? conversation.getContextWindow() : 20;
        List<MessageDto> windowed = allMessages.size() > windowSize
                ? allMessages.subList(allMessages.size() - windowSize, allMessages.size())
                : allMessages;
        return windowed.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleteFlag()))
                .map(m -> ModelMessage.builder()
                        .role(m.getRole().getValue())
                        .content(m.getContent())
                        .thinkContent(m.getReasoningContent())
                        .build())
                .collect(Collectors.toList());
    }
}
