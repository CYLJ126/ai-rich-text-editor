package com.arte.ainew.api.conversation;

import com.arte.ainew.common.execution.ExecutionContext;
import reactor.core.publisher.Mono;

/**
 * 后续归档写入扩展点
 * <p>
 * 幂等及并发字段随真实归档能力一并确定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationArchiveCommands {

    record Receipt(String conversationId, boolean archived) {
    }

    /**
     * 当前实现是否支持归档和取消归档。
     */
    boolean isSupported();

    /**
     * 归档指定会话，保留原会话及历史消息。
     */
    Mono<Receipt> archive(String conversationId, ExecutionContext context);

    /**
     * 取消归档，恢复原会话。
     */
    Mono<Receipt> unarchive(String conversationId, ExecutionContext context);
}
