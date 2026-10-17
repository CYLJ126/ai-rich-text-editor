package com.arte.ainew.web.request;

import com.arte.ainew.pojo.search.ConversationSearch;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 会话搜索 HTTP DTO
 * <p>
 * 不接受客户端 owner／PIT／ES sort。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ConversationSearchRequests {

    private ConversationSearchRequests() {
    }

    public record Search(@NotNull @Valid ConversationRequests.Scope scope, @NotNull @Size(max = 8192) String query,
                         @Size(max = 256) List<@NotBlank @Size(max = 256) String> groupIds,
                         @Min(1) @Max(20) int size, @Size(max = 16384) String cursor) {
        public ConversationSearch.Query toQuery() {
            return new ConversationSearch.Query(query, groupIds, size, cursor);
        }
    }

    public record Close(@NotNull @Valid ConversationRequests.Scope scope,
                        @NotBlank @Size(max = 256) String searchSessionId) {
    }

    public record Around(@NotNull @Valid ConversationRequests.Scope scope,
                         @NotBlank @Size(max = 256) String conversationId, @Size(max = 256) String turnId,
                         @Min(0) Long expectedVersion, @Min(1) @Max(10) int size,
                         @Size(max = 16384) String historyCursor) {
        public ConversationSearch.AroundQuery toQuery() {
            return new ConversationSearch.AroundQuery(conversationId, turnId, expectedVersion, size, historyCursor);
        }
    }
}
