package com.arte.ai.spi.store;

import com.arte.ai.model.context.ContextSnapshot;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.conversation.Turn;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.identity.ExecutionScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 聊天权威存储。claim 原子比较会话版本、分配顺序、推进版本及占位；重复键须核对摘要。
 * withTurn 在事务内排他锁定提交，回调仅提交状态变更／串行受理异步调用，不装配历史或等待模型网络结果。
 * ready、accept、reject、release 加入同一回调事务；回调回滚不撤销独立模型受理事务。
 */
public interface ChatStore {
    Conversation create(Conversation conversation);

    Optional<Conversation> conversation(ExecutionScope scope, String id);

    List<Conversation> conversations(ExecutionScope scope, String title, int offset, int limit);

    Conversation rename(ExecutionScope scope, String id, long version, String title, Instant now);

    Conversation delete(ExecutionScope scope, String id, long version, Instant now);

    Turn claim(Turn draft);

    Optional<Turn> turn(ExecutionScope scope, String id);

    Optional<Turn> activeTurn(ExecutionScope scope, String conversationId);

    List<Turn> turns(ExecutionScope scope, String conversationId, long beforeSequence, int limit);

    <T> T withTurn(ExecutionScope scope, String id, Function<Turn, T> work);

    Turn ready(Turn expected, ContextSnapshot snapshot, Instant now);

    Turn accept(Turn expected, String executionId, Instant now);

    Turn reject(Turn expected, ExecutionError error, Instant now);

    Turn release(Turn expected, Instant now);

    Optional<ContextSnapshot> snapshot(ExecutionScope scope, String id);
}
