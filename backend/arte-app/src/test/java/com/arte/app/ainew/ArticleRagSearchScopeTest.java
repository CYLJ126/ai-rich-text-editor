package com.arte.app.ainew;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.arte.ai.api.EmbeddingService;
import com.arte.app.pojo.richtext.ArticleEsSearchRequest;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.app.service.richtext.ArticleServiceImpl;
import com.arte.app.strategy.richtext.handler.HybridSearchStrategy;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.es.ElasticsearchTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 验证实际旧参数转换及 ES 请求，避免只检查 DTO 而漏掉 query/kNN 两个召回分支。 */
class ArticleRagSearchScopeTest {
    @Test void originalHybridSearchAppliesTheAuthorizedIdsToBothBm25AndKnn() {
        var template = mock(ElasticsearchTemplate.class);
        var embedding = mock(EmbeddingService.class);
        when(embedding.generateEmbedding("文章问题")).thenReturn(new float[]{1, 2});
        when(embedding.getDimension()).thenReturn(2);
        var properties = new ElasticsearchProperties(List.of("http://localhost:9200"), null, null, null, null, null,
                null, null, new ElasticsearchProperties.Index("articles", "chunks", 1, 0, 1, 0));
        var strategy = new HybridSearchStrategy(template, embedding, properties);
        var service = mock(ArticleServiceImpl.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "hybridSearchStrategy", strategy);
        var param = new ArticleParam();
        param.setSearchBingoText("文章问题"); param.setArticleIds(Set.of(10, 11)); param.setSemanticSearch(true); param.setSearchTitle(false);
        param.setCurrent(1); param.setSize(10);
        service.hybridSearch(param);
        var request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(template).search(request.capture(), eq(ChunkDocument.class), any());
        var search = request.getValue();
        assertEquals(0, search.from()); assertEquals(10, search.size());
        var bm25 = search.query().bool().filter().stream().filter(q -> q.isTerms() && q.terms().field().equals("article_meta.article_id")).findFirst().orElseThrow();
        var knn = search.knn().getFirst().filter().stream().filter(q -> q.isTerms() && q.terms().field().equals("article_meta.article_id")).findFirst().orElseThrow();
        assertEquals(Set.of(10L, 11L), bm25.terms().terms().value().stream().map(value -> value.isAny() ? value.anyValue().to(Integer.class).longValue() : value.longValue()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(10L, 11L), knn.terms().terms().value().stream().map(value -> value.isAny() ? value.anyValue().to(Integer.class).longValue() : value.longValue()).collect(java.util.stream.Collectors.toSet()));
        verify(embedding).generateEmbedding("文章问题");
    }
    @Test void keywordOnlySearchDoesNotCallEmbeddingAndStillFiltersBm25() {
        var service = mock(ArticleServiceImpl.class, CALLS_REAL_METHODS);
        var strategy = mock(HybridSearchStrategy.class);
        ReflectionTestUtils.setField(service, "hybridSearchStrategy", strategy);
        var param = new ArticleParam(); param.setSearchBingoText("文章问题"); param.setArticleIds(Set.of(10)); param.setSemanticSearch(false);
        service.hybridSearch(param);
        var request = ArgumentCaptor.forClass(Object.class); verify(strategy).search(request.capture());
        var query = (ArticleEsSearchRequest) request.getValue();
        assertEquals(Set.of(10), query.hardFilters().articleIds());
        assertFalse(query.knnFilters().semanticSearch());
    }
}
