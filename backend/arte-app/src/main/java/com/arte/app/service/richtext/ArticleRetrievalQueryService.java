package com.arte.app.service.richtext;

import com.arte.ai.conversation.ChatValues;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

/** 新检索只读服务；复用原 hybridSearch，调用方必须传入经过授权的非空文章集合。 */
public final class ArticleRetrievalQueryService {
    public record Metadata(String id, String title, String version) {
        public ResourceRef resource() { return ResourceRef.saved("ARTICLE", id, version); }
    }
    private final JdbcTemplate jdbc;
    private final ArticleService articles;
    private final ArticleContextQueryService texts;
    public ArticleRetrievalQueryService(JdbcTemplate jdbc, ArticleService articles, ArticleContextQueryService texts) {
        this.jdbc = jdbc; this.articles = articles; this.texts = texts;
    }
    /** 不读正文。空间登记是候选范围，不能代替文章授权；超限失败，不能冒充全库检索。 */
    public List<Metadata> candidates(ExecutionScope scope) { return candidates(scope, List.of()); }
    public List<Metadata> candidates(ExecutionScope scope, List<String> selectedIds) {
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(scope.tenantId()); parameters.add(scope.workspaceId());
        selectedIds.forEach(id -> parameters.add(Integer.parseInt(id)));
        String selected = selectedIds.isEmpty() ? "" : " AND a.id IN (" + String.join(",", java.util.Collections.nCopies(selectedIds.size(), "?")) + ")";
        var values = jdbc.query("SELECT a.id,a.title,a.row_version FROM arte_rt_article a JOIN arte_security_resource r"
                + " ON r.resource_type='ARTICLE' AND r.resource_id=CONCAT('',a.id)"
                + " WHERE a.is_delete=0 AND r.enabled=1 AND r.tenant_id=? AND r.workspace_id=?" + selected + " ORDER BY a.id LIMIT 4097",
                (row, index) -> new Metadata(Integer.toString(row.getInt("id")), row.getString("title"),
                        row.getObject("row_version") == null ? null : Integer.toString(row.getInt("row_version"))), parameters.toArray());
        if (values.size() > 4096) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "rag-library-capacity");
        return values.stream().filter(value -> value.version() != null).toList();
    }
    public ArticleContextQueryService.ArticleText fullText(String id) {
        return texts.find(id).orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "rag-article"));
    }
    public List<ChunkDocument> search(String query, Set<Integer> authorizedIds, boolean semantic, int limit) {
        if (authorizedIds.isEmpty()) return List.of();
        var param = new ArticleParam();
        param.setSearchBingoText(query); param.setSearchTitle(false); param.setSemanticSearch(semantic);
        param.setArticleIds(Set.copyOf(authorizedIds)); param.setCurrent(1); param.setSize(limit);
        // 原服务把 articleIds 同时传给 BM25 hardFilters 和 kNN filters。
        var result = articles.hybridSearch(param);
        if (result == null || result.hits() == null) throw ChatValues.failure(CommonErrorCode.INTERNAL_ERROR, "rag-search");
        return result.hits().stream().map(hit -> hit.source()).toList();
    }
}
