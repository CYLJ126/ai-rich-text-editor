package com.nip.ai.strategy.context;

import com.nip.ai.pojo.conversation.ConversationDto;
import com.nip.ai.pojo.message.MessageDto;
import com.nip.ai.pojo.model.ModelMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全量上下文策略
 * 发送全部历史消息，适合上下文窗口足够大的模型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:13 ✾
 **/
@Service("fullContextStrategy")
public class FullContextStrategy implements ContextStrategy {
    @Override
    public List<ModelMessage> buildContext(ConversationDto conversation,
                                           List<MessageDto> allMessages,
                                           String latestSummary) {
        return allMessages.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getDeleteFlag()))
                .map(m -> ModelMessage.builder()
                        .role(m.getRole().getValue())
                        .content(m.getContent())
                        .thinkContent(m.getReasoningContent())
                        .build())
                .collect(Collectors.toList());
    }
}
