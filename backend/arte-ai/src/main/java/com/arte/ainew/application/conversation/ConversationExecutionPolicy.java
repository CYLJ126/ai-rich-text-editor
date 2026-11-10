package com.arte.ainew.application.conversation;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

/**
 * 会话执行判定服务
 * <p>
 * 统一判定普通文本资格和调用对其直接会话的占用要求；不授予发送权限，不读取或释放预算。
 * 纯判定方法可供后续受理、终态事务及历史组装复用，读取门面只消费耐久关联。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 11:30 ✾
 */
public final class ConversationExecutionPolicy {

    private final AdmissionCatalogStore conversations;

    /**
     * 创建会话执行判定服务，通过目录存储读取调用关联的耐久会话和轮次。
     *
     * @param conversations 会话及轮次目录存储
     */
    public ConversationExecutionPolicy(AdmissionCatalogStore conversations) {
        this.conversations = conversations;
    }

    /**
     * 会话及候选关联缺失或不匹配时不能推断为普通文本。
     * 同时核验请求类型、会话归属、轮次关联及调用候选身份。
     *
     * @param invocation   待判定的调用快照
     * @param conversation 调用关联的耐久会话，缺失时可为空
     * @param turn         调用关联的耐久轮次，缺失时可为空
     * @return 请求符合普通文本规则且会话、轮次和候选关联均匹配时返回真
     */
    public static boolean ordinaryText(Invocation invocation, Conversation conversation, Turn turn) {
        Invocation.ConversationLink link = invocation.conversation();
        return invocation.ordinaryTextRequest() && link != null && conversation != null && turn != null
                && conversation.conversationId().equals(link.conversationId())
                && conversation.owner().equals(ExecutionOwners.from(invocation.request().context()))
                && turn.conversationId().equals(link.conversationId()) && turn.turnId().equals(link.turnId())
                && turn.candidates().contains(AnswerCandidate.invocation(invocation.request().context().executionId()));
    }

    /**
     * 子调用不占用直接会话，父 Run 仍独立持有占用。UNKNOWN 只有资格已确认的普通文本可不阻塞。
     * 费用状态、错误码及模型能力均不能替代本判定。
     *
     * @param invocation   待判定的调用快照
     * @param conversation 调用关联的耐久会话，缺失时可为空
     * @param turn         调用关联的耐久轮次，缺失时可为空
     * @return 本调用仍要求保留直接会话占用时返回真，不代表当前会话的整体占用状态
     */
    public static boolean blocksConversation(Invocation invocation, Conversation conversation, Turn turn) {
        if (invocation.conversation() == null || invocation.request().context().parentExecutionId() != null) {
            return false;
        }
        if (!invocation.state().terminal()) {
            return true;
        }
        return invocation.state() == Invocation.State.UNKNOWN && !ordinaryText(invocation, conversation, turn);
    }

    /**
     * 供已授权的状态投影使用；记录缺失时保守阻塞，存储读取错误继续传播。
     * 仅在普通文本请求处于 UNKNOWN 且直接关联会话时，读取会话和轮次以核验完整资格。
     *
     * @param invocation 待判定的调用快照，调用方须事先完成读取授权
     * @return 本调用是否要求保留直接会话占用；所需关联记录缺失时返回真
     */
    public Mono<Boolean> blocksConversation(Invocation invocation) {
        if (invocation.conversation() == null || invocation.request().context().parentExecutionId() != null
                || invocation.state() != Invocation.State.UNKNOWN || !invocation.ordinaryTextRequest()) {
            return Mono.just(blocksConversation(invocation, null, null));
        }
        ExecutionOwner owner = ExecutionOwners.from(invocation.request().context());
        Invocation.ConversationLink link = invocation.conversation();
        return Mono.zip(conversations.findConversation(owner, link.conversationId()),
                        conversations.findTurn(owner, link.conversationId(), link.turnId()))
                .map(records -> blocksConversation(invocation, records.getT1(), records.getT2()))
                .defaultIfEmpty(true);
    }
}
