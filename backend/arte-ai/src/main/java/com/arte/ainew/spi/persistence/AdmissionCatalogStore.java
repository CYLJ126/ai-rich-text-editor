package com.arte.ainew.spi.persistence;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.StoreOutcome;
import reactor.core.publisher.Mono;

/**
 * 受理事务所需的会话版本／活跃轮次和预算账户初始化；由可信控制面显式调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface AdmissionCatalogStore {

    /**
     * 已有记录不能覆盖；不替代 ConversationService 的完整编辑、历史和删除能力。
     */
    Mono<Void> createConversation(Conversation conversation);

    /**
     * owner＋操作幂等键原子创建；摘要一致返回原会话，冲突不覆盖，包括会话后续变更后重放。
     */
    Mono<StoreOutcome<Conversation>> createConversationOnce(Conversation conversation, String idempotencyKey, String requestDigest);

    Mono<Conversation> findConversation(ExecutionOwner owner, String conversationId);

    Mono<ConversationPage<Conversation>> listConversations(ExecutionOwner owner, long current, long size);

    /**
     * 归属、版本、计数与分页读取在同一事务中检查，不存在或版本冲突使用明确结果码。
     */
    Mono<StoreOutcome<ConversationPage<Turn>>> listTurns(ExecutionOwner owner, String conversationId,
                                                         long expectedVersion, long current, long size);

    Mono<Turn> findTurn(ExecutionOwner owner, String conversationId, String turnId);

    /**
     * 只允许零 held、charged、version 的新账户；不提供静默重置账本的 upsert。
     */
    Mono<Void> createAccount(BudgetCommands.Account account);
}
