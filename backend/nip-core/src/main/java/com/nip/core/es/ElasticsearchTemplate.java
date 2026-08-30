package com.nip.core.es;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.bulk.UpdateOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.JsonpSerializable;
import co.elastic.clients.json.JsonpUtils;
import com.nip.core.exception.ElasticsearchException;
import jakarta.json.stream.JsonGenerator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Elasticsearch 操作模板 —— 封装同步/异步 CRUD，统一异常处理
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:27 ✾
 **/
@Getter
@Slf4j
@Service
public class ElasticsearchTemplate {

    private final ElasticsearchClient syncClient;

    private final ElasticsearchAsyncClient asyncClient;

    public ElasticsearchTemplate(ElasticsearchClient syncClient, ElasticsearchAsyncClient asyncClient) {
        this.syncClient = syncClient;
        this.asyncClient = asyncClient;
    }

    // ===== 同步 CRUD =====
    public <T> IndexResponse index(String indexName, String id, T document) {
        try {
            return syncClient.index(r -> r
                    .index(indexName)
                    .id(id)
                    .document(document));
        } catch (IOException e) {
            throw new ElasticsearchException("Index operation failed", "index", indexName, e);
        }
    }

    public <T> Optional<T> get(String indexName, String id, Class<T> clazz) {
        try {
            GetResponse<T> response = syncClient.get(r -> r
                    .index(indexName)
                    .id(id), clazz);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw new ElasticsearchException("Get operation failed", "get", indexName, e);
        }
    }

    public <T> UpdateResponse<T> update(String indexName, String id,
                                        T partialDoc, Class<T> clazz) {
        try {
            return syncClient.update(r -> r
                    .index(indexName)
                    .id(id)
                    .doc(partialDoc)
                    .retryOnConflict(3), clazz);
        } catch (IOException e) {
            throw new ElasticsearchException("Update operation failed", "update", indexName, e);
        }
    }

    public <T> UpdateResponse<T> upsert(String indexName, String id,
                                        T doc, T upsertDoc, Class<T> clazz) {
        try {
            return syncClient.update(r -> r
                    .index(indexName)
                    .id(id)
                    .doc(doc)
                    .upsert(upsertDoc)
                    .retryOnConflict(3), clazz);
        } catch (IOException e) {
            throw new ElasticsearchException("Upsert operation failed", "upsert", indexName, e);
        }
    }

    public DeleteResponse delete(String indexName, String id) {
        try {
            return syncClient.delete(r -> r.index(indexName).id(id));
        } catch (IOException e) {
            throw new ElasticsearchException("删除文档失败，ID：" + id, "delete", indexName, e);
        }
    }

    /**
     * 按查询条件删除文档（如删除某篇文章的所有 chunks）
     */
    public DeleteByQueryResponse deleteByQuery(String indexName, Function<DeleteByQueryRequest.Builder, DeleteByQueryRequest.Builder> fn) {
        try {
            return syncClient.deleteByQuery(fn.apply(new DeleteByQueryRequest.Builder().index(indexName)).build());
        } catch (IOException e) {
            throw new ElasticsearchException("DeleteByQuery failed", "deleteByQuery", indexName, e);
        }
    }

    /**
     * 批量操作
     */
    public BulkResponse bulk(String indexName, List<BulkOperation> operations) {
        try {
            BulkResponse response = syncClient.bulk(r -> r.index(indexName).operations(operations));
            if (response.errors()) {
                long errorCount = response.items().stream().filter(item -> item.error() != null).count();
                log.warn("Bulk operation completed with {} errors in index: {}", errorCount, indexName);
            }
            return response;
        } catch (IOException e) {
            throw new ElasticsearchException("Bulk operation failed", "bulk", indexName, e);
        }
    }

    /**
     * 构建批量 Index 操作
     */
    public <T> BulkOperation buildIndexOp(String id, T document) {
        return BulkOperation.of(op -> op.index(IndexOperation.of(i -> i.id(id).document(document))));
    }

    /**
     * 构建批量 Update 操作
     */
    public <T> BulkOperation buildUpdateOp(String id, T partialDoc) {
        return BulkOperation.of(op -> op.update(UpdateOperation.of(u -> u.id(id).action(a -> a.doc(partialDoc)))));
    }

    // ===== 异步 CRUD =====
    public <T> CompletableFuture<IndexResponse> indexAsync(String indexName, String id, T document) {
        return asyncClient.index(r -> r.index(indexName).id(id).document(document))
                .toCompletableFuture()
                .exceptionally(e -> {
                    log.error("异步保存 es 文档失败，索引名：{}，id：{}", indexName, id, e);
                    throw new ElasticsearchException("异步保存 es 文档失败", "indexAsync", indexName, e);
                });
    }

    public <T> CompletableFuture<Optional<T>> getAsync(String indexName, String id, Class<T> clazz) {
        return asyncClient.get(r -> r.index(indexName).id(id), clazz)
                .toCompletableFuture()
                .thenApply(r -> r.found() ? Optional.ofNullable(r.source()) : Optional.<T>empty())
                .exceptionally(e -> {
                    throw new ElasticsearchException("Async get failed", "getAsync", indexName, e);
                });
    }

    public <T> CompletableFuture<BulkResponse> bulkAsync(String indexName, List<BulkOperation> operations) {
        return asyncClient.bulk(r -> r.index(indexName).operations(operations))
                .toCompletableFuture()
                .exceptionally(e -> {
                    throw new ElasticsearchException("Async bulk failed", "bulkAsync", indexName, e);
                });
    }

    /**
     * 执行搜索并返回结果，供 SearchStrategy 使用
     */
    public <T> EsSearchResponse<T> search(SearchRequest request, Class<T> clazz, Function<Hit<T>, EsSearchResponse.Hit<T>> hitMapper) {
        try {
            printRequest(request);
            SearchResponse<T> response = syncClient.search(request, clazz);
            return buildResponse(request, response, hitMapper);
        } catch (IOException e) {
            throw new ElasticsearchException("Search failed", "search", String.join(",", request.index()), e);
        }
    }

    /**
     * 异步执行搜索，返回 CompletableFuture
     */
    public <T> CompletableFuture<EsSearchResponse<T>> searchAsync(SearchRequest request, Class<T> clazz, Function<Hit<T>, EsSearchResponse.Hit<T>> hitMapper) {
        printRequest(request);
        return asyncClient.search(request, clazz)
                .toCompletableFuture()
                .thenApply(response -> buildResponse(request, response, hitMapper))
                .exceptionally(e -> {
                    throw new ElasticsearchException("Async search failed", "searchAsync", String.join(",", request.index()), e);
                });
    }

    private <T> EsSearchResponse<T> buildResponse(SearchRequest request, SearchResponse<T> response, Function<Hit<T>, EsSearchResponse.Hit<T>> hitMapper) {
        long total = response.hits().total() != null ? response.hits().total().value() : 0L;
        List<EsSearchResponse.Hit<T>> hits = response.hits().hits().stream().map(hitMapper).toList();
        int from = request.from() != null ? request.from() : 0;
        int size = request.size() != null ? request.size() : 10;
        return new EsSearchResponse<>(hits, total, from / Math.max(size, 1), size, (long) from + hits.size() < total);
    }

    private void printRequest(SearchRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("[ES 查询] 索引：{}，请求参数：{}", String.join(",", request.index()), toJson(request));
        }
    }

    /**
     * 将 ES 请求对象序列化为可读 JSON 字符串
     * <p>
     * {@link JsonpUtils#toJsonString} 是官方提供的序列化工具，
     * 内部使用 Jackson 或 Jakarta JSON-P，与 client 配置保持一致。
     * </p>
     */
    private String toJson(JsonpSerializable target) {
        try (StringWriter writer = new StringWriter()) {
            // 从 syncClient 获取已配置的 JsonpMapper，保证序列化行为一致
            JsonpMapper mapper = syncClient._transport().jsonpMapper();
            try (JsonGenerator generator = mapper.jsonProvider().createGenerator(writer)) {
                target.serialize(generator, mapper);
            }
            return writer.toString();
        } catch (Exception e) {
            log.warn("[ES] 请求序列化失败，无法打印请求体", e);
            return "<serialize failed>";
        }
    }

}
