package com.arte.ainew.pojo.search;

import com.arte.ainew.common.search.SearchText;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Turn;

import java.util.List;
import java.util.Objects;

/**
 * 会话搜索及聊天历史定位的请求、响应对象集合，不暴露 owner、PIT 或 ES 类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ConversationSearch {

    private ConversationSearch() {
    }

    public enum Kind {TITLE, MESSAGE}

    public enum EndReason {END}

    public enum Direction {FORWARD, BACKWARD, FIRST}

    /**
     * 会话搜索请求，携带关键词、分组条件及分页参数。
     */
    public record Query(String query, List<String> groupIds, int size, String cursor) {

        public Query {
            query = SearchText.query(query);
            groupIds = SearchText.groups(groupIds);
            SearchText.requireGroupsAvailable(groupIds);
            ContractChecks.range(size, "size", 1, 20);
            if (cursor != null) ContractChecks.text(cursor, "cursor", 16384);
        }
    }

    /**
     * 片段中的高亮区间，使用 UTF-16 下标，左闭右开。
     */
    public record Highlight(int start, int end) {

        public Highlight {
            ContractChecks.require(start >= 0 && end > start, "Invalid highlight range");
        }
    }

    /**
     * 搜索结果的正文截取片段及其高亮区间。
     */
    public record Snippet(String text, List<Highlight> highlights) {

        public Snippet {
            Objects.requireNonNull(text, "text");
            ContractChecks.require(text.length() <= 16384 && SearchText.visibleLength(text) <= 800, "Preview too long");
            highlights = List.copyOf(highlights);
            int previous = 0;
            for (var h : highlights) {
                ContractChecks.require(h.start() >= previous && h.end() <= text.length(), "Invalid highlight ranges");
                ContractChecks.require(!splitsSurrogate(text, h.start()) && !splitsSurrogate(text, h.end()), "Split UTF-16 pair");
                previous = h.end();
            }
        }
    }

    private static boolean splitsSurrogate(String value, int offset) {
        return offset > 0 && offset < value.length() && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }

    /**
     * 单条标题或消息命中，包含展示片段和消息定位信息。
     */
    public record Item(String entryKey, String conversationId, String title, Kind matchKind, Snippet snippet,
                       boolean canLocateMessage, String turnId, String messageId, String invocationId,
                       Integer candidateOrdinal, boolean partial, AnswerCandidate candidate) {

        public Item(String key, String conversation, String title, Kind kind, Snippet snippet, boolean canLocate,
                    String turn, String message, String invocation, Integer ordinal, boolean partial) {
            this(key, conversation, title, kind, snippet, canLocate, turn, message, invocation, ordinal, partial,
                    invocation == null ? null : AnswerCandidate.invocation(invocation));
        }

        public Item {
            ContractChecks.digest(entryKey, "entryKey");
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.text(title, "title", ContractChecks.MAX_TEXT_CHARS + ContractChecks.MAX_PARTS + 2);
            Objects.requireNonNull(matchKind, "matchKind");
            Objects.requireNonNull(snippet, "snippet");
            ContractChecks.require((invocationId == null) == (candidate == null), "Typed candidate identity mismatch");
            if (candidate != null && candidate.kind() == AnswerCandidate.Kind.INVOCATION)
                ContractChecks.require(candidate.executionId().equals(invocationId), "Invocation candidate mismatch");
            if (matchKind == Kind.TITLE) {
                ContractChecks.require(!canLocateMessage && turnId == null && messageId == null
                        && invocationId == null && candidateOrdinal == null && !partial, "Title cannot locate message");
            } else {
                ContractChecks.require(canLocateMessage, "Message target required");
                ContractChecks.id(turnId, "turnId");
                ContractChecks.id(messageId, "messageId");
                ContractChecks.optionalId(invocationId, "invocationId");
                ContractChecks.require((invocationId == null) == (candidateOrdinal == null), "Candidate identity mismatch");
                if (candidateOrdinal != null)
                    ContractChecks.range(candidateOrdinal, "candidateOrdinal", 1, Integer.MAX_VALUE);
            }
        }
    }

    /**
     * 一页搜索结果及前后翻页、返回首页的游标。
     */
    public record Page(String searchSessionId, List<Item> records, String previousCursor, String nextCursor,
                       String firstCursor, EndReason endReason, boolean partialPage, Long total) {

        public Page {
            ContractChecks.id(searchSessionId, "searchSessionId");
            records = ContractChecks.list(records, "records", 0, 20);
            ContractChecks.unique(records.stream().map(Item::entryKey).toList(), "entryKey");
            ContractChecks.text(firstCursor, "firstCursor", 16384);
            if (previousCursor != null) ContractChecks.text(previousCursor, "previousCursor", 16384);
            if (nextCursor != null) ContractChecks.text(nextCursor, "nextCursor", 16384);
            ContractChecks.require(total == null, "Search total is unknown");
            ContractChecks.require(!partialPage || endReason == null, "Budget page cannot declare END");
        }
    }

    /**
     * 按目标轮次定位，或按历史游标翻页读取聊天历史的请求。
     */
    public record AroundQuery(String conversationId, String turnId, Long expectedVersion, int size,
                              String historyCursor) {

        public AroundQuery {
            ContractChecks.id(conversationId, "conversationId");
            ContractChecks.range(size, "size", 1, 10);
            if (historyCursor == null) {
                ContractChecks.id(turnId, "turnId");
                ContractChecks.require(expectedVersion != null && expectedVersion >= 0, "Expected version required");
            } else {
                ContractChecks.text(historyCursor, "historyCursor", 16384);
                ContractChecks.require(turnId == null && expectedVersion == null, "History cursor mode is exclusive");
            }
        }
    }

    /**
     * 聊天历史窗口，携带会话版本、轮次记录和前后浏览游标。
     */
    public record AroundPage(long conversationVersion, List<Turn> records, String earlierCursor, String laterCursor) {

        public AroundPage {
            ContractChecks.range(conversationVersion, "conversationVersion", 0, Long.MAX_VALUE);
            records = ContractChecks.list(records, "records", 0, 10);
        }
    }
}
