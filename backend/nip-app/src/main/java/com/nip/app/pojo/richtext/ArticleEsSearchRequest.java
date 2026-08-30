package com.nip.app.pojo.richtext;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * 混合检索请求
 * <pre>
 * 查询结构分三层：
 *  1. hardFilters   → query.bool.filter  (不参与评分，硬过滤)
 *  2. shouldClauses → query.bool.should  (BM25 文本相关性)
 *  3. knn           → knn{}              (语义向量检索)
 *     knnFilters    → knn.filter         (kNN 内部加速过滤)
 * </pre>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:21 ✾
 */
public record ArticleEsSearchRequest(
        // ---------- 文本查询 ----------
        String textQuery,               // 全局文本查询词（用于生成 embedding & should 子句）
        String titleQuery,                 // 标题查询词

        // ---------- 向量查询 ----------
        float[] queryVector,            // 由调用方/策略层生成
        Integer knnK,
        Integer knnNumCandidates,

        // ---------- 评分权重 ----------
        Float bm25Boost,
        Float knnBoost,

        // ---------- 第一层：硬过滤（query.bool.filter） ----------
        HardFilters hardFilters,

        // ---------- 第二层：should 子句额外配置 ----------
        ShouldOptions shouldOptions,

        // ---------- 第三层：kNN 内部过滤 ----------
        KnnFilters knnFilters,

        // ---------- 分页 ----------
        Integer page,
        Integer size
) {

    /**
     * 用向量创建新实例（其余字段不变）
     */
    public ArticleEsSearchRequest withVector(float[] vector) {
        return new ArticleEsSearchRequest(
                textQuery, titleQuery,
                vector, knnK, knnNumCandidates,
                bm25Boost, knnBoost,
                hardFilters, shouldOptions, knnFilters,
                page, size);
    }

    /**
     * 内嵌结构：第一层硬过滤
     */
    public record HardFilters(
            Set<String> authors,
            Set<Integer> articleIds,
            Set<Integer> catalogIds,
            Set<Integer> tagIds,
            Boolean isPublic,
            Integer characterCountFloor,
            Integer characterCountCeil,
            LocalDateTime createTimeFloor,   // create_time >= floor
            LocalDateTime createTimeCeil,    // create_time <= ceil
            LocalDateTime updateTimeFloor,
            LocalDateTime updateTimeCeil,
            Map<String, Object> extra        // 其余精确/范围过滤，key=字段名
    ) {

        public static HardFilters empty() {
            return new HardFilters(Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), null,
                    null, null, null, null, null, null, Map.of());
        }

        public boolean isEmpty() {
            return (authors == null || authors.isEmpty())
                    && (articleIds == null || articleIds.isEmpty())
                    && (catalogIds == null || catalogIds.isEmpty())
                    && (tagIds == null || tagIds.isEmpty())
                    && isPublic == null
                    && characterCountFloor == null && characterCountCeil == null
                    && createTimeFloor == null && createTimeCeil == null
                    && updateTimeFloor == null && updateTimeCeil == null
                    && (extra == null || extra.isEmpty());
        }
    }

    /**
     * 内嵌结构：第二层 should 选项
     */
    public record ShouldOptions(
            Float titleBoost,           // article_meta.title 权重，默认 3.0（3.0 是业界通用值（2～5 之间都可））
            Float contentBoost,         // content 权重，默认 1.0（BM25 的基准分，和 KNN 分数做加法时比例合适）
            String phraseQuery,         // match_phrase 短语查询词（可与 textQuery 不同），null 则不加
            Integer phraseSlop,             // match_phrase slop，默认 2
            Integer minimumShouldMatch      // 默认 1
    ) {

        public static ShouldOptions defaults() {
            return new ShouldOptions(3.0f, 1.0f, null, 2, 1);
        }
    }

    /**
     * 内嵌结构：第三层 kNN 内部过滤
     */
    public record KnnFilters(
            Boolean semanticSearch,
            Set<String> authors,
            Set<Integer> articleIds,
            Set<Integer> catalogIds,
            Set<Integer> tagIds,
            String articleType,
            Boolean hasStrikethrough,
            Boolean withMatch, // 是否做 match 匹配，默认不做，即执行语义搜索
            Map<String, Object> extra    // 其余精确过滤
    ) {

        public static KnnFilters empty() {
            return new KnnFilters(false, Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), Collections.emptySet(), null, null, false, Map.of());
        }

        public boolean isEmpty() {
            return semanticSearch == null
                    && (authors == null || authors.isEmpty())
                    && (articleIds == null || articleIds.isEmpty())
                    && (catalogIds == null || catalogIds.isEmpty())
                    && (tagIds == null || tagIds.isEmpty())
                    && articleType == null
                    && hasStrikethrough == null
                    && withMatch == null
                    && (extra == null || extra.isEmpty());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * HybridSearchRequest Builder
     */
    public static final class Builder {
        private String textQuery;
        private String titleQuery;
        private float[] queryVector;
        private Integer knnK = 50; // 向量多召回一些，再与文本分加权
        private Integer knnNumCandidates = 200; // kNN 的候选数，默认 200，建议 200~300，提高 HNSW 图的探测精度，召回更准
        private Float bm25Boost = 1.0f; // BM25 的基准分，默认 1.0f
        private Float knnBoost = 1.2f; // kNN 的基准分，默认 1.2f（略微高于 BM25，避免被标题 BM25 完全压制）
        private HardFilters hardFilters = HardFilters.empty();
        private ShouldOptions shouldOptions = ShouldOptions.defaults();
        private KnnFilters knnFilters = KnnFilters.empty();
        private Integer page = 0;
        private Integer size = 20;

        public Builder textQuery(String v) {
            textQuery = v;
            return this;
        }

        public Builder titleQuery(String v) {
            titleQuery = v;
            return this;
        }

        public Builder queryVector(float[] v) {
            queryVector = v;
            return this;
        }

        public Builder knnK(Integer v) {
            knnK = v;
            return this;
        }

        public Builder knnNumCandidates(Integer v) {
            knnNumCandidates = v;
            return this;
        }

        public Builder bm25Boost(Float v) {
            bm25Boost = v;
            return this;
        }

        public Builder knnBoost(Float v) {
            knnBoost = v;
            return this;
        }

        public Builder hardFilters(HardFilters v) {
            hardFilters = v;
            return this;
        }

        public Builder shouldOptions(ShouldOptions v) {
            shouldOptions = v;
            return this;
        }

        public Builder knnFilters(KnnFilters v) {
            knnFilters = v;
            return this;
        }

        public Builder page(Integer v) {
            page = v;
            return this;
        }

        public Builder size(Integer v) {
            size = v;
            return this;
        }

        public ArticleEsSearchRequest build() {
            return new ArticleEsSearchRequest(
                    textQuery, titleQuery, queryVector, knnK, knnNumCandidates,
                    bm25Boost, knnBoost,
                    hardFilters != null ? hardFilters : HardFilters.empty(),
                    shouldOptions != null ? shouldOptions : ShouldOptions.defaults(),
                    knnFilters != null ? knnFilters : KnnFilters.empty(),
                    page, size);
        }
    }
}