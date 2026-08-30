package com.arte.app.strategy.richtext.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.NamedValue;
import com.arte.ai.api.EmbeddingService;
import com.arte.app.common.constant.RichTextEsIndexConstants;
import com.arte.app.pojo.richtext.ArticleEsSearchRequest;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.strategy.richtext.AbstractSearchStrategy;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.es.ElasticsearchTemplate;
import com.arte.core.es.EsSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 混合搜索策略 —— BM25 + kNN 线性加权组合
 * ES 开源版使用两次查询 + 客户端 RRF 合并，避免使用付费 RRF 功能
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 11:04 ✾
 **/
@Slf4j
@Service
public class HybridSearchStrategy extends AbstractSearchStrategy {

    private static final DateTimeFormatter ES_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ElasticsearchTemplate template;
    private final EmbeddingService embeddingService;
    private final String chunkIndex;

    public HybridSearchStrategy(ElasticsearchTemplate template,
                                EmbeddingService embeddingService,
                                ElasticsearchProperties props) {
        this.template = template;
        this.embeddingService = embeddingService;
        this.chunkIndex = props.index().chunkIndex();
    }

    @Override
    public boolean supports(Class<?> requestType) {
        return ArticleEsSearchRequest.class.equals(requestType);
    }

    @Override
    public EsSearchResponse<ChunkDocument> search(Object req) {
        ArticleEsSearchRequest request = (ArticleEsSearchRequest) req;
        // 生成嵌入向量，填充到 enriched 请求中
        if (StrUtil.isNotBlank(request.textQuery()) && request.knnFilters().semanticSearch()) {
            float[] queryVector = embeddingService.generateEmbedding(request.textQuery());
            request = request.withVector(queryVector);
        }
        SearchRequest searchRequest = buildSearchRequest(request);
        return template.search(searchRequest, ChunkDocument.class, this::mapHit);
    }

    /**
     * 构建统一 SearchRequest（三层合一）
     *
     * @param req 请求参数
     * @return ES Client 查询请求对象
     */
    private SearchRequest buildSearchRequest(ArticleEsSearchRequest req) {
        int page = req.page() != null ? req.page() : 0;
        int size = resolveSize(req);
        int from = page * size;
        SearchRequest.Builder searchRequestBuilder = new SearchRequest.Builder();
        searchRequestBuilder
                .index(chunkIndex)
                .from(from)
                .size(size)
                // 第一层 + 第二层：bool query
                .query(buildBoolQuery(req))
                .highlight(buildHighlight());
        if (req.queryVector() != null && req.queryVector().length == embeddingService.getDimension()) {
            // 第三层：kNN（与 query 并存时，ES 会做 hybrid score）
            searchRequestBuilder.knn(buildKnn(req));
        }
        return searchRequestBuilder.build();
    }

    /**
     * 第一层 + 第二层：bool query
     * filter  = 硬过滤（不参与评分）
     * should  = BM25 文本相关性
     *
     * @param req 请求参数
     * @return ES 查询条件
     */
    private Query buildBoolQuery(ArticleEsSearchRequest req) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        // ---- 第一层：hard filters ----
        appendHardFilters(bool, req.hardFilters());
        // ---- 第二层：should（BM25 相关性） ----
        appendShouldClauses(bool, req.textQuery(), req.titleQuery(), req.shouldOptions());
        return Query.of(q -> q.bool(bool.build()));
    }

    /**
     * 第一层：硬过滤 → bool.filter
     *
     * @param bool bool 构造器
     * @param hf   过滤条件
     */
    private void appendHardFilters(BoolQuery.Builder bool, ArticleEsSearchRequest.HardFilters hf) {
        if (hf == null || hf.isEmpty()) return;
        // authors: terms 过滤
        if (CollUtil.isNotEmpty(hf.authors())) {
            bool.filter(f -> f.terms(t -> t
                    .field(meta("author"))
                    .terms(tv -> tv.value(
                            hf.authors().stream().map(FieldValue::of).toList()))));
        }
        // article_ids: terms 过滤
        if (CollUtil.isNotEmpty(hf.articleIds())) {
            bool.filter(f -> f.terms(t -> t
                    .field(meta("article_id"))
                    .terms(tv -> tv.value(
                            hf.articleIds().stream().map(FieldValue::of).toList()))));
        }
        // tag_ids: terms 过滤
        if (CollUtil.isNotEmpty(hf.tagIds())) {
            bool.filter(f -> f.terms(t -> t
                    .field(meta("tag_ids"))
                    .terms(tv -> tv.value(
                            hf.tagIds().stream().map(FieldValue::of).toList()))));
        }
        // is_public: term 过滤
        if (hf.isPublic() != null) {
            bool.filter(f -> f.term(t -> t
                    .field(meta("is_public"))
                    .value(FieldValue.of(hf.isPublic()))));
        }
        // catalog_ids: terms 过滤
        if (CollUtil.isNotEmpty(hf.catalogIds())) {
            bool.filter(f -> f.terms(t -> t
                    .field(meta("catalog_id"))
                    .terms(tv -> tv.value(
                            hf.catalogIds().stream().map(FieldValue::of).toList()))));
        }
        // create_time 范围
        if (hf.createTimeFloor() != null || hf.createTimeCeil() != null) {
            bool.filter(f -> f.range(r -> r
                    .untyped(u -> {
                        u.field(meta("create_time"));
                        if (hf.createTimeFloor() != null)
                            u.gte(JsonData.of(hf.createTimeFloor().format(ES_DATE_FMT)));
                        if (hf.createTimeCeil() != null)
                            u.lte(JsonData.of(hf.createTimeCeil().format(ES_DATE_FMT)));
                        return u;
                    })
            ));
        }
        // 字数范围
        if (hf.characterCountFloor() != null || hf.characterCountCeil() != null) {
            bool.filter(f -> f.range(r -> r
                    .untyped(u -> {
                        u.field(meta("character_count"));
                        if (hf.characterCountFloor() != null)
                            u.gte(JsonData.of(hf.characterCountFloor()));
                        if (hf.characterCountCeil() != null)
                            u.lte(JsonData.of(hf.characterCountCeil()));
                        return u;
                    })
            ));
        }
        // update_time 范围
        if (hf.updateTimeFloor() != null || hf.updateTimeCeil() != null) {
            bool.filter(f -> f.range(r -> r
                    .untyped(u -> {
                        u.field(meta("update_time"));
                        if (hf.updateTimeFloor() != null)
                            u.gte(JsonData.of(hf.updateTimeFloor().format(ES_DATE_FMT)));
                        if (hf.updateTimeCeil() != null)
                            u.lte(JsonData.of(hf.updateTimeCeil().format(ES_DATE_FMT)));
                        return u;
                    })
            ));
        }
        // extra: 其余精确匹配
        if (hf.extra() != null) {
            hf.extra().forEach((field, value) ->
                    bool.filter(f -> f.term(t -> t
                            .field(field)
                            .value(toFieldValue(value)))));
        }
    }

    /**
     * 第二层：BM25 相关性 → bool.should
     *
     * @param bool      bool 构造器
     * @param textQuery 查询关键词
     * @param opts      相关性选项
     */
    private void appendShouldClauses(BoolQuery.Builder bool, String textQuery, String titleQuery, ArticleEsSearchRequest.ShouldOptions opts) {
        if (StrUtil.isNotBlank(titleQuery)) {
            // match: article_meta.title（高权重）
            bool.should(s -> s.match(m -> m
                    .field(meta("title"))
                    .query(titleQuery)
                    .boost(opts.titleBoost())));
        }
        if (StrUtil.isNotBlank(textQuery)) {
            // match: content
            bool.should(s -> s.match(m -> m
                    .field(RichTextEsIndexConstants.FIELD_CONTENT)
                    .query(textQuery)
                    .operator(Operator.And) // 多个词语搜索用 AND
                    .boost(opts.contentBoost())));
            // match_phrase: 精确短语（可选）
            String phrase = opts.phraseQuery() != null ? opts.phraseQuery() : textQuery;
            bool.should(s -> s.matchPhrase(mp -> mp
                    .field(RichTextEsIndexConstants.FIELD_CONTENT)
                    .query(phrase)
                    .slop(opts.phraseSlop())));
            bool.minimumShouldMatch(String.valueOf(opts.minimumShouldMatch()));
        }
    }

    /**
     * 第三层：KNN 搜索构造
     *
     * @param req 请求参数
     * @return KNN 搜索请求
     */
    private KnnSearch buildKnn(ArticleEsSearchRequest req) {
        List<Float> vectorList = toFloatList(req.queryVector());
        List<Query> knnFilterQueries = buildKnnFilterQueries(req.knnFilters(), req.textQuery());
        return KnnSearch.of(k -> {
            k.field(RichTextEsIndexConstants.FIELD_EMBEDDING)
                    .queryVector(vectorList)
                    .k(req.knnK())
                    .boost(req.knnBoost())
                    .numCandidates(req.knnNumCandidates());
            if (!knnFilterQueries.isEmpty()) {
                k.filter(knnFilterQueries);
            }
            return k;
        });
    }

    /**
     * KNN 内部过滤条件构建
     *
     * @param kf        KNN 过滤条件
     * @param textQuery 查询关键词
     * @return KNN 过滤条件
     */
    private List<Query> buildKnnFilterQueries(ArticleEsSearchRequest.KnnFilters kf, String textQuery) {
        List<Query> queries = new ArrayList<>();
        if (BooleanUtil.isFalse(kf.semanticSearch())) {
            // content 文本过滤（加速 kNN 候选范围），语义搜索即 Rag 时不加此条件
            if (textQuery != null && !textQuery.isBlank()) {
                queries.add(Query.of(q -> q.match(m -> m
                        .field(RichTextEsIndexConstants.FIELD_CONTENT)
                        .query(textQuery))));
            }
        }
        // authors: terms 过滤
        if (CollUtil.isNotEmpty(kf.authors())) {
            queries.add(Query.of(q -> q.terms(t -> t
                    .field(meta("author"))
                    .terms(tv -> tv.value(
                            kf.authors().stream().map(FieldValue::of).toList())))));
        }
        // article_ids: terms 过滤
        if (CollUtil.isNotEmpty(kf.articleIds())) {
            queries.add(Query.of(q -> q.terms(t -> t
                    .field(meta("article_id"))
                    .terms(tv -> tv.value(
                            kf.articleIds().stream().map(FieldValue::of).toList())))));
        }
        // tag_ids: terms 过滤
        if (CollUtil.isNotEmpty(kf.tagIds())) {
            queries.add(Query.of(q -> q.terms(t -> t
                    .field(meta("tag_ids"))
                    .terms(tv -> tv.value(
                            kf.tagIds().stream().map(FieldValue::of).toList())))));
        }
        // catalog_ids
        if (CollUtil.isNotEmpty(kf.catalogIds())) {
            queries.add(Query.of(q -> q.terms(t -> t
                    .field(meta("catalog_id"))
                    .terms(tv -> tv.value(
                            kf.catalogIds().stream().map(FieldValue::of).toList())))));
        }
        // article_type
        if (kf.articleType() != null) {
            queries.add(Query.of(q -> q.term(t -> t
                    .field(meta("article_type"))
                    .value(FieldValue.of(kf.articleType())))));
        }
        // has_strikethrough
        if (kf.hasStrikethrough() != null) {
            queries.add(Query.of(q -> q.term(t -> t
                    .field(RichTextEsIndexConstants.FIELD_HAS_STRIKETHROUGH)
                    .value(FieldValue.of(kf.hasStrikethrough())))));
        }
        // extra
        if (kf.extra() != null) {
            kf.extra().forEach((field, value) ->
                    queries.add(Query.of(q -> q.term(t -> t
                            .field(field)
                            .value(toFieldValue(value))))));
        }
        return queries;
    }

    /**
     * article_meta.{field} 快捷拼接
     */
    private static String meta(String field) {
        return RichTextEsIndexConstants.FIELD_ARTICLE_META + "." + field;
    }

    /**
     * 返回每页查询条数
     *
     * @param request 请求参数
     * @return 页大小
     */
    private int resolveSize(ArticleEsSearchRequest request) {
        return (request.size() != null && request.size() > 0) ? request.size() : RichTextEsIndexConstants.DEFAULT_PAGE_SIZE;
    }

    private Highlight buildHighlight() {
        return Highlight.of(h -> h
                .fields(NamedValue.of(
                        RichTextEsIndexConstants.FIELD_CONTENT,
                        HighlightField.of(f -> f.numberOfFragments(3).fragmentSize(150))
                ))
                .preTags("<em>")
                .postTags("</em>"));
    }
}