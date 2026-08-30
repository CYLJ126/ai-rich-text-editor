package com.nip.app.strategy.richtext.handler;

import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.nip.app.common.constant.RichTextEsIndexConstants;
import com.nip.app.pojo.richtext.ChunkDocument;
import com.nip.app.strategy.richtext.AbstractSearchStrategy;
import com.nip.core.es.ElasticsearchProperties;
import com.nip.core.es.ElasticsearchTemplate;
import com.nip.core.es.EsSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 向量搜索策略 —— 纯 kNN 搜索，支持元数据过滤
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 11:03 ✾
 **/
@Service
@Slf4j
public class VectorSearchStrategy extends AbstractSearchStrategy {
    private final ElasticsearchTemplate template;
    private final String chunkIndex;

    public VectorSearchStrategy(ElasticsearchTemplate template,
                                ElasticsearchProperties props) {
        this.template = template;
        this.chunkIndex = props.index().chunkIndex();
    }

    @Override
    public boolean supports(Class<?> requestType) {
        return VectorSearchRequest.class.equals(requestType);
    }

    @Override
    public EsSearchResponse<ChunkDocument> search(Object req) {
        var request = (VectorSearchRequest) req;
        int k = request.k() > 0 ? request.k() : RichTextEsIndexConstants.DEFAULT_KNN_K;
        int numCandidates = request.numCandidates() > 0
                ? request.numCandidates() : RichTextEsIndexConstants.DEFAULT_KNN_NUM_CANDIDATES;
        List<Float> vectorList = toFloatList(request.queryVector());
        SearchRequest esRequest = SearchRequest.of(r -> r
                .index(chunkIndex)
                .size(k)
                .knn(buildKnn(vectorList, k, numCandidates, request.filters())));
        return template.search(esRequest, ChunkDocument.class, this::mapHit);
    }

    KnnSearch buildKnn(List<Float> vector, int k, int numCandidates,
                       Map<String, Object> filters) {
        return KnnSearch.of(knn -> {
            knn.field(RichTextEsIndexConstants.FIELD_EMBEDDING)
                    .queryVector(vector)
                    .k(k)
                    .numCandidates(numCandidates);
            if (filters != null && !filters.isEmpty()) {
                knn.filter(buildFilters(filters));
            }
            return knn;
        });
    }

    private List<Query> buildFilters(Map<String, Object> filters) {
        return filters.entrySet().stream()
                .map(e -> Query.of(q -> q
                        .term(t -> t
                                .field(e.getKey())
                                .value(toFieldValue(e.getValue())))))
                .toList();
    }

    @Override
    protected EsSearchResponse.Hit<ChunkDocument> mapHit(Hit<ChunkDocument> hit) {
        return new EsSearchResponse.Hit<>(
                hit.id(),
                hit.score() != null ? hit.score().floatValue() : 0f,
                hit.source(),
                Map.of());
    }

    /**
     * 向量搜索专用请求
     */
    public record VectorSearchRequest(
            float[] queryVector,
            int k,
            int numCandidates,
            Map<String, Object> filters
    ) {
    }
}

