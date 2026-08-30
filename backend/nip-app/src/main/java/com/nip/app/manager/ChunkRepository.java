package com.nip.app.manager;

import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import com.nip.app.pojo.richtext.ChunkDocument;
import com.nip.core.es.BulkProcessor;
import com.nip.core.es.ElasticsearchProperties;
import com.nip.core.es.ElasticsearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 分块索引 Repository —— 负责文章 chunks 的 CRUD
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:54 ✾
 **/
@Slf4j
@Repository
public class ChunkRepository {

    private final ElasticsearchTemplate template;
    private final BulkProcessor bulkProcessor;
    private final String indexName;

    public ChunkRepository(ElasticsearchTemplate template,
                           BulkProcessor bulkProcessor,
                           ElasticsearchProperties props) {
        this.template = template;
        this.bulkProcessor = bulkProcessor;
        this.indexName = props.index().chunkIndex();
    }

    /**
     * 同步写操作
     *
     * @param chunk 文章分块
     */
    public void save(ChunkDocument chunk) {
        template.index(indexName, chunk.getChunkId(), chunk);
    }


    /**
     * 异步写操作
     *
     * @param chunk 文章分块
     */
    public CompletableFuture<Void> saveAsync(ChunkDocument chunk) {
        return template.indexAsync(indexName, chunk.getChunkId(), chunk)
                .thenAccept(r -> log.debug("异步保存分块: {}", chunk.getChunkId()));
    }

    /**
     * 批量保存 chunks（走 BulkProcessor）
     */
    public void saveBatchAsync(List<ChunkDocument> chunks) {
        chunks.forEach(chunk -> {
            BulkOperation op = template.buildIndexOp(chunk.getChunkId(), chunk);
            bulkProcessor.add(indexName, op);
        });
        log.debug("异步保存 {} 个分块", chunks.size());
    }

    /**
     * 立即批量保存（同步，适合小批量或需要强一致性的场景）
     */
    public BulkResponse saveBatch(List<ChunkDocument> chunks) {
        List<BulkOperation> ops = chunks.stream()
                .map(chunk -> template.buildIndexOp(chunk.getChunkId(), chunk))
                .toList();
        BulkResponse bulkResponse = template.bulk(indexName, ops);
        log.debug("保存 {} 个分块，结果：{}", chunks.size(), bulkResponse);
        return bulkResponse;
    }

    public void update(String chunkId, ChunkDocument partial) {
        template.update(indexName, chunkId, partial, ChunkDocument.class);
    }

    public void delete(Integer chunkId) {
        template.delete(indexName, String.valueOf(chunkId));
    }

    /**
     * 删除文章的所有 chunks（更新文章时先删后写）
     */
    public DeleteByQueryResponse deleteByArticleId(Integer articleId) {
        return template.deleteByQuery(indexName, builder ->
                builder.query(q -> q
                        .term(t -> t
                                .field("article_id")
                                .value(articleId))));
    }

    public CompletableFuture<Void> deleteByArticleIdAsync(Integer articleId) {
        return CompletableFuture.runAsync(() -> deleteByArticleId(articleId));
    }

    /**
     * 读操作
     *
     * @param chunkId 分块 ID
     * @return 文章分块内容
     */
    public Optional<ChunkDocument> findById(Integer chunkId) {
        return template.get(indexName, String.valueOf(chunkId), ChunkDocument.class);
    }

    /**
     * 强制刷新索引
     */
    public void refresh() {
        try (ElasticsearchIndicesClient indicesClient = template.getSyncClient().indices();) {
            indicesClient.refresh(r -> r.index(indexName));
        } catch (Exception e) {
            log.warn("Failed to refresh chunk index: {}", indexName, e);
        }
    }
}
