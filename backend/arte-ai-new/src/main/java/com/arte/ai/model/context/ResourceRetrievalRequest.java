package com.arte.ai.model.context;

import java.util.List;
import java.util.Objects;

/** 显式检索选择；NONE 不查询资料，全文只允许一篇，ES 查询最多返回十个片段。 */
public record ResourceRetrievalRequest(Mode mode, List<String> articleIds, boolean semanticSearch, int maxResults) {
    public enum Mode { NONE, ARTICLE_FULL_TEXT, SELECTED_ARTICLES, ARTICLE_LIBRARY }
    public ResourceRetrievalRequest {
        Objects.requireNonNull(mode, "mode");
        articleIds = articleIds == null ? List.of() : List.copyOf(articleIds);
        if (articleIds.size() > 16 || articleIds.stream().distinct().count() != articleIds.size()
                || articleIds.stream().anyMatch(id -> !id.matches("[1-9][0-9]{0,9}") || Long.parseLong(id) > Integer.MAX_VALUE)
                || maxResults < 1 || maxResults > 10)
            throw new IllegalArgumentException("invalid retrieval selection");
        if (mode == Mode.ARTICLE_FULL_TEXT && articleIds.size() != 1
                || mode == Mode.SELECTED_ARTICLES && articleIds.isEmpty()
                || (mode == Mode.NONE || mode == Mode.ARTICLE_LIBRARY) && !articleIds.isEmpty())
            throw new IllegalArgumentException("article selection does not match retrieval mode");
    }
}
