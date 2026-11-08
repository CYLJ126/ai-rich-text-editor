package com.arte.ainew.application.conversation;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import reactor.core.publisher.Mono;

/**
 * 统一判定普通文本资格和调用对其直接会话的占用要求；不授予发送权限，不读取或释放预算。
 * 纯判定方法可供后续受理、终态事务及历史组装复用，读取门面只消费耐久关联。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 11:30 ✾
 */
public final class ConversationExecutionPolicy {

    private final AdmissionCatalogStore conversations;

    public ConversationExecutionPolicy(AdmissionCatalogStore conversations) {
        this.conversations = conversations;
    }

    /**
     * 会话及候选关联缺失或不匹配时不能推断为普通文本。
     */
    public static boolean ordinaryText(Invocation invocation, Conversation conversation, Turn turn) {
        var link = invocation.conversation();
        return invocation.ordinaryTextRequest() && link != null && conversation != null && turn != null
                && conversation.conversationId().equals(link.conversationId())
                && conversation.owner().equals(ExecutionOwners.from(invocation.request().context()))
                && turn.conversationId().equals(link.conversationId()) && turn.turnId().equals(link.turnId())
                && turn.candidates().contains(AnswerCandidate.invocation(invocation.request().context().executionId()));
    }

    /**
     * 子调用不占用直接会话，父 Run 仍独立持有占用。UNKNOWN 只有资格已确认的普通文本可不阻塞。
     * 费用状态、错误码及模型能力均不能替代本判定。
     */
    public static boolean blocksConversation(Invocation invocation, Conversation conversation, Turn turn) {
        if (invocation.conversation() == null || invocation.request().context().parentExecutionId() != null)
            return false;
        if (!invocation.state().terminal()) return true;
        return invocation.state() == Invocation.State.UNKNOWN && !ordinaryText(invocation, conversation, turn);
    }

    /**
     * 供已授权的状态投影使用；记录缺失时保守阻塞，存储读取错误继续传播。
     */
    public Mono<Boolean> blocksConversation(Invocation invocation) {
        if (invocation.conversation() == null || invocation.request().context().parentExecutionId() != null
                || invocation.state() != Invocation.State.UNKNOWN || !invocation.ordinaryTextRequest())
            return Mono.just(blocksConversation(invocation, null, null));
        var owner = ExecutionOwners.from(invocation.request().context());
        var link = invocation.conversation();
        return Mono.zip(conversations.findConversation(owner, link.conversationId()),
                        conversations.findTurn(owner, link.conversationId(), link.turnId()))
                .map(records -> blocksConversation(invocation, records.getT1(), records.getT2()))
                .defaultIfEmpty(true);
    }
}
