package com.arte.app.manager;

import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesAsyncClient;
import com.arte.app.pojo.richtext.ArticleDocument;
import com.arte.core.es.BulkProcessor;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.es.ElasticsearchTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 文档索引 Repository —— 负责原始文章文档的 CRUD
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:53 ✾
 **/
@Repository
@Slf4j
public class ArticleRepository {
    private final ElasticsearchTemplate template;
    private final BulkProcessor bulkProcessor;
    private final String indexName;

    public ArticleRepository(ElasticsearchTemplate template,
                             BulkProcessor bulkProcessor,
                             ElasticsearchProperties props) {
        this.template = template;
        this.bulkProcessor = bulkProcessor;
        this.indexName = props.index().documentIndex();
    }

    /**
     * 同步写操作
     *
     * @param document 文章内容
     */
    public void save(ArticleDocument document) {
        String id = String.valueOf(document.getArticleId());
        template.index(indexName, id, document);
        log.debug("索引：{} 保存文档，文档 ID：{}", indexName, document.getArticleId());
    }

    /**
     * 异步写操作
     *
     * @param document 文章内容
     * @return 异步写入结果 Future
     */
    public CompletableFuture<Void> saveAsync(ArticleDocument document) {
        String id = String.valueOf(document.getArticleId());
        return template.indexAsync(indexName, id, document)
                .thenAccept(r -> log.debug("索引：{} 异步保存文档，文档 ID：{}", indexName, document.getArticleId()));
    }

    /**
     * 批量保存（走 BulkProcessor 缓冲）
     */
    public void saveBatch(List<ArticleDocument> documents) {
        documents.forEach(doc -> {
            BulkOperation op = template.buildIndexOp(
                    String.valueOf(doc.getArticleId()), doc);
            bulkProcessor.add(indexName, op);
        });
    }

    /**
     * 部分更新文档元数据（乐观锁 retryOnConflict=3）
     */
    public void update(Integer articleId, ArticleDocument partial) {
        template.update(indexName, String.valueOf(articleId), partial, ArticleDocument.class);
        log.debug("索引：{} 更新文档，文档 ID：{}", indexName, articleId);
    }

    /**
     * 存在则更新，不存在则插入
     */
    public void upsert(ArticleDocument document) {
        String id = String.valueOf(document.getArticleId());
        template.upsert(indexName, id, document, document, ArticleDocument.class);
    }

    public DeleteResponse delete(Integer articleId) {
        DeleteResponse deleteResponse = template.delete(indexName, String.valueOf(articleId));
        log.info("索引：{} 删除文档，文档 ID：{}，删除结果：{}", indexName, articleId, deleteResponse);
        return deleteResponse;
    }

    /**
     * 同步读操作
     *
     * @param articleId 文章 ID
     * @return 文章内容
     */
    public Optional<ArticleDocument> findById(Integer articleId) {
        return template.get(indexName, String.valueOf(articleId), ArticleDocument.class);
    }


    /**
     * 异步读操作
     *
     * @param articleId 文章 ID
     * @return 文章内容 Future
     */
    public CompletableFuture<Optional<ArticleDocument>> findByIdAsync(Long articleId) {
        return template.getAsync(indexName, String.valueOf(articleId), ArticleDocument.class);
    }

    /**
     * 强制刷新（写入后立即可搜索，测试或强一致性场景使用）
     */
    public void refresh() {
        try (ElasticsearchIndicesAsyncClient indicesAsyncClient = template.getAsyncClient().indices()) {
            indicesAsyncClient.refresh(r -> r.index(indexName));
        } catch (Exception e) {
            log.warn("刷新文章索引 {} 失败", indexName, e);
        }
    }
}
