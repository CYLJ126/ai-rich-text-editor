package com.arte.ainew.application.conversation;

import com.arte.ainew.api.conversation.ConversationArchiveCommands;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.search.SearchFailure;
import reactor.core.publisher.Mono;

/**
 * 占位命令明确失败，绝不模拟归档成功。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class UnsupportedConversationArchiveCommands implements ConversationArchiveCommands {

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public Mono<Receipt> archive(String id, ExecutionContext context) {
        return unavailable();
    }

    @Override
    public Mono<Receipt> unarchive(String id, ExecutionContext context) {
        return unavailable();
    }

    private Mono<Receipt> unavailable() {
        return Mono.error(new SearchFailure(SearchFailure.Code.ARCHIVE_NOT_AVAILABLE));
    }
}
