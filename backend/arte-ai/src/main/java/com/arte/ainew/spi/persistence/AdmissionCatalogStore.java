package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import reactor.core.publisher.Mono;

/**
 * 受理事务所需的会话版本／活跃 Turn 门闩和预算账户初始化；由可信控制面显式调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface AdmissionCatalogStore {
    /**
     * 已有记录不能覆盖；不替代 ConversationService 的完整编辑、历史和删除能力。
     */
    Mono<Void> createConversation(Conversation conversation);

    Mono<Conversation> findConversation(ExecutionOwner owner, String conversationId);

    Mono<Turn> findTurn(ExecutionOwner owner, String conversationId, String turnId);

    /**
     * 只允许零 held、charged、version 的新账户；不提供静默重置账本的 upsert。
     */
    Mono<Void> createAccount(BudgetCommands.Account account);
}
