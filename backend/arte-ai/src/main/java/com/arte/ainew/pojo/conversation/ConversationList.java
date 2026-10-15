package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.List;

/**
 * 新目录契约，与旧 offset 分页和执行版本独立。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
public final class ConversationList {

    private ConversationList() {
    }

    public record Item(String conversationId, String title, long metadataVersion, boolean pinned,
                       boolean titleManuallyEdited, boolean autoTitleResolved, String previewText,
                       Long userMessageCount, Instant lastMessageGeneratedAt, Instant sortActivityAt,
                       Instant createdAt, boolean statisticsReady) {
    }

    public record Page(List<Item> records, String nextCursor, boolean hasMore, Long total) {
        public Page {
            records = List.copyOf(records);
        }
    }

    public record Window(Page pinned, Page normal, String anchorConversationId, boolean anchorFallback) {
    }

    public record Query(boolean pinned, int size, String cursor, boolean includeTotal) {
        public Query {
            ContractChecks.range(size, "size", 1, 20);
            if (cursor != null) ContractChecks.text(cursor, "cursor", 2048);
        }
    }

    public record WindowQuery(int normalSize, String anchorConversationId) {
        public WindowQuery {
            ContractChecks.range(normalSize, "normalSize", 1, 100);
            ContractChecks.optionalId(anchorConversationId, "anchorConversationId");
        }
    }

    /**
     * rename 与 setPinned 共用命令键空间；只允许二者之一。
     */
    public record Command(String conversationId, long expectedMetadataVersion, String title, Boolean pinned,
                          String idempotencyKey) {
        public Command {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(expectedMetadataVersion, "expectedMetadataVersion", 0, Long.MAX_VALUE);
            ContractChecks.id(idempotencyKey, "idempotencyKey");
            ContractChecks.require((title != null) != (pinned != null), "Exactly one metadata operation is required");
            if (title != null) {
                title = title.strip();
                ContractChecks.text(title, "title", 256);
            }
        }
    }

    /**
     * 重放返回首次提交的回执；不把当前值伪装成原结果。
     */
    public record Receipt(String conversationId, String commandId, Item conversation, long pinnedCount,
                          boolean changed) {
    }

    public record CommandResult(String commandId, com.arte.ainew.pojo.execution.StoreOutcome.Code outcome,
                                Receipt receipt) {
    }

    public record BackfillProgress(int processed, boolean hasMore) {
    }
}
