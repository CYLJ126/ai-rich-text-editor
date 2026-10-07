package com.arte.ainew.web.response;

import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.ChatMessage;

import java.time.Instant;
import java.util.List;

/**
 * 轮次及回答候选引用；执行状态和回答内容由调用查询接口提供，不复制终态权威。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
public record ConversationTurnResponse(String turnId, String conversationId, long sequence,
                                       String parentTurnId, String supersedesTurnId, ChatMessage userMessage,
                                       List<String> invocationIds, String selectedInvocationId, long version,
                                       Instant createdAt, Instant updatedAt) {
    public static ConversationTurnResponse from(Turn turn) {
        return new ConversationTurnResponse(turn.turnId(), turn.conversationId(), turn.sequence(), turn.parentTurnId(),
                turn.supersedesTurnId(), turn.userMessage(), turn.invocationIds(), turn.selectedInvocationId(),
                turn.version(), turn.createdAt(), turn.updatedAt());
    }
}
