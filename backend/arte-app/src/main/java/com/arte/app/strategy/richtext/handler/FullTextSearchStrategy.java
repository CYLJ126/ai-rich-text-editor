package com.arte.app.strategy.richtext.handler;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.SourceConfig;
import co.elastic.clients.util.NamedValue;
import com.arte.app.common.constant.RichTextEsIndexConstants;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.strategy.richtext.AbstractSearchStrategy;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.es.ElasticsearchTemplate;
import com.arte.core.es.EsSearchRequest;
import com.arte.core.es.EsSearchResponse;
import org.springframework.stereotype.Service;

/**
 * 全文搜索策略 —— BM25 多字段加权搜索
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:56 ✾
 **/
@Service
public class FullTextSearchStrategy extends AbstractSearchStrategy {

    private final ElasticsearchTemplate template;
    private final String chunkIndex;

    public FullTextSearchStrategy(ElasticsearchTemplate template,
                                  ElasticsearchProperties props) {
        this.template = template;
        this.chunkIndex = props.index().chunkIndex();
    }

    @Override
    public boolean supports(Class<?> requestType) {
        return EsSearchRequest.class.equals(requestType);
    }

    @Override
    public EsSearchResponse<ChunkDocument> search(Object req) {
        var request = (EsSearchRequest) req;
        int page = request.page() != null ? request.page() : 0;
        int size = Math.min(
                request.size() != null ? request.size() : RichTextEsIndexConstants.DEFAULT_PAGE_SIZE,
                RichTextEsIndexConstants.MAX_PAGE_SIZE);
        int from = page * size;

        SearchRequest esRequest =
                SearchRequest.of(r -> r
                        .index(chunkIndex)
                        .from(from)
                        .size(size)
                        .query(buildQuery(request))
                        .highlight(buildHighlight())
                        .source(buildSource(request)));

        return template.search(esRequest, ChunkDocument.class, this::mapHit);
    }

    private SourceConfig buildSource(EsSearchRequest request) {
        if (request.includeFields() != null && !request.includeFields().isEmpty()) {
            return SourceConfig.of(s -> s
                    .filter(f -> f.includes(request.includeFields())));
        }
        return SourceConfig.of(s -> s.fetch(true));
    }

    // ================================================================
    // Query 构建
    // ================================================================

    private Query buildQuery(EsSearchRequest request) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        // 多字段加权 BM25
        bool.must(m -> m.multiMatch(mm -> mm
                .query(request.query())
                .fields(
                        RichTextEsIndexConstants.FIELD_CONTENT + "^3",
                        RichTextEsIndexConstants.FIELD_BOLD_TERMS + "^2",
                        RichTextEsIndexConstants.FIELD_HIGHLIGHT_TERMS + "^2",
                        RichTextEsIndexConstants.FIELD_ARTICLE_META_TITLE + "^2",
                        RichTextEsIndexConstants.FIELD_BREADCRUMB + "^2",
                        RichTextEsIndexConstants.FIELD_SECTION_HEADING + "^2"
                )
                .type(TextQueryType.BestFields)
                .minimumShouldMatch("75%")));

        // 降权含删除线内容
        bool.should(s -> s.term(t -> t
                .field(RichTextEsIndexConstants.FIELD_HAS_STRIKETHROUGH)
                .value(false)
                .boost(1.2f)));

        // 动态过滤条件
        if (request.filters() != null) {
            request.filters().forEach((field, value) ->
                    bool.filter(f -> f.term(t -> t
                            .field(field)
                            .value(toFieldValue(value)))));
        }

        // 排除非公开文章
        /*bool.filter(f -> f.term(t -> t
                .field(RichTextEsIndexConstants.FIELD_ARTICLE_META_IS_PUBLIC)
                .value(true)));*/

        return Query.of(q -> q.bool(bool.build()));
    }

    // ================================================================
    // Highlight 构建
    // ================================================================

    /**
     * ✅ 修复：ES Java Client 9.x 的 Highlight.Builder.fields() 签名为
     * {@code fields(NamedValue<HighlightField>, NamedValue<HighlightField>...)}，
     * 不再支持 (String, HighlightField) 形式。
     */
    private Highlight buildHighlight() {
        return Highlight.of(h -> h
                .fields(
                        NamedValue.of(
                                RichTextEsIndexConstants.FIELD_CONTENT,
                                HighlightField.of(f -> f.numberOfFragments(3).fragmentSize(150))
                        ),
                        NamedValue.of(
                                RichTextEsIndexConstants.FIELD_ARTICLE_META_TITLE,
                                HighlightField.of(f -> f.numberOfFragments(1))
                        )
                )
                .preTags("<em>")
                .postTags("</em>"));
    }

}