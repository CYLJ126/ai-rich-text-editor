package com.arte.ai.strategy.context;

import com.arte.ai.pojo.conversation.ConversationDto;
import com.arte.ai.pojo.message.MessageDto;
import com.arte.ai.pojo.model.ModelMessage;

import java.util.List;

/**
 * 上下文策略接口
 * 不同策略决定发送给模型的历史消息范围
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/19 13:10 ✾
 **/
public interface ContextStrategy {
    /**
     * 构建发送给模型的消息列表
     *
     * @param conversation  当前会话
     * @param allMessages   全量历史消息（主分支，已按sort_order排序）
     * @param latestSummary 最新摘要（SUMMARY策略使用）
     * @return 用于发送给模型的消息列表
     */
    List<ModelMessage> buildContext(ConversationDto conversation,
                                    List<MessageDto> allMessages,
                                    String latestSummary);
}
