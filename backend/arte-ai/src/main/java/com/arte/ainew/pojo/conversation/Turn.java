package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.generation.ChatMessage;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 聊天的轮次
 * <p>
 * 一次固定用户输入和回答候选关联，无独立执行终态。重新生成保留本 Turn 并新增 Invocation；
 * 编辑重发新建 Turn，用 supersedesTurnId 关联原轮次并保留分支，不修改已经执行的输入。
 * parentTurnId 决定历史路径，候选引用及当前选择的结果归属由会话服务校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Turn(String turnId, String conversationId, long sequence, String parentTurnId,
                   String supersedesTurnId, ChatMessage userMessage, List<String> invocationIds,
                   String selectedInvocationId, long version, Instant createdAt,
                   Instant updatedAt) implements Serializable {
    public Turn {
        ContractChecks.id(turnId, "turnId");
        ContractChecks.id(conversationId, "conversationId");
        ContractChecks.range(sequence, "sequence", 1, Long.MAX_VALUE);
        ContractChecks.optionalId(parentTurnId, "parentTurnId");
        ContractChecks.optionalId(supersedesTurnId, "supersedesTurnId");
        ContractChecks.require(!turnId.equals(parentTurnId) && !turnId.equals(supersedesTurnId), "Turn cannot reference itself");
        Objects.requireNonNull(userMessage, "userMessage");
        ContractChecks.require(userMessage.role() == ChatMessage.Role.USER, "Turn input must be a user message");
        invocationIds = ContractChecks.list(invocationIds, "invocationIds", 0, ContractChecks.MAX_ITEMS);
        invocationIds.forEach(id -> ContractChecks.id(id, "invocationId"));
        ContractChecks.unique(invocationIds, "invocationIds");
        ContractChecks.optionalId(selectedInvocationId, "selectedInvocationId");
        ContractChecks.require(selectedInvocationId == null || invocationIds.contains(selectedInvocationId),
                "Selected response must belong to this turn");
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }
}
