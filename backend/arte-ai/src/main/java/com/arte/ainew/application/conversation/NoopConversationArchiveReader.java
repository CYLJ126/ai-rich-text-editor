package com.arte.ainew.application.conversation;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

/**
 * 归档尚未启用时的空读适配，不访问数据库或 ES。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class NoopConversationArchiveReader implements ConversationArchiveReader {
    @Override
    public Mono<Set<String>> findArchivedConversationIds(ExecutionOwner owner, Set<String> ids) {
        return Mono.fromSupplier(() -> {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(ids, "conversationIds");
            ContractChecks.range(ids.size(), "conversationIds.size", 0, 256);
            ids.forEach(id -> ContractChecks.id(id, "conversationId"));
            return Set.of();
        });
    }
}
