package com.arte.app.manager;

import com.arte.core.i18n.MessageUtils;

import cn.hutool.core.io.resource.ResourceUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import com.arte.ai.api.EmbeddingService;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.exception.ElasticsearchException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 引生命周期管理 —— 负责索引的创建、映射更新
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:25 ✾
 **/
@Service
@Slf4j
public class IndexManager {

    private final ElasticsearchClient client;
    private final ElasticsearchProperties props;
    private final EmbeddingService embeddingService;
    private final ReentrantLock initLock = new ReentrantLock();

    public IndexManager(ElasticsearchClient client, ElasticsearchProperties props, EmbeddingService embeddingService) {
        this.client = client;
        this.props = props;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void initIndices() {
        initLock.lock();
        try {
            ensureIndex(props.index().documentIndex(), "es/mappings/articles-settings.json");
            ensureIndex(props.index().chunkIndex(), "es/mappings/article-chunks-settings.jsonTemplate");
        } finally {
            initLock.unlock();
        }
    }

    /**
     * 确保索引存在，不存在则创建
     * @param indexName 索引名
     * @param settingsClasspath 索引定义的文件路径
     */
    public void ensureIndex(String indexName, String settingsClasspath) {
        try {
            boolean exists = client.indices()
                    .exists(ExistsRequest.of(r -> r.index(indexName)))
                    .value();
            if (exists) {
                log.info("索引 {} 已经存在，无需创建", indexName);
                return;
            }
            createIndex(indexName, settingsClasspath);
            log.info("创建索引 {} 成功", indexName);
        } catch (IOException e) {
            throw new ElasticsearchException(MessageUtils.get("error.es.createIndexFailed", indexName), "ensureIndex", indexName, e);
        }
    }

    private void createIndex(String indexName, String settingsClasspath) throws IOException {
        int dimension = embeddingService.getDimension();
        log.info("嵌入向量维度：{}", dimension);

        String jsonTemplate = ResourceUtil.readUtf8Str(settingsClasspath);
        String json = jsonTemplate.replace("${dimension}", String.valueOf(dimension));

        client.indices().create(
                CreateIndexRequest.of(r ->
                        r.index(indexName)
                                .withJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))
                )
        );
    }

    /**
     * 动态更新 mapping（仅支持新增字段，不可修改已有字段类型）
     */
    public void updateMapping(String indexName, String mappingClasspath) {
        try (InputStream is = new ClassPathResource(mappingClasspath).getInputStream()) {
            client.indices().putMapping(PutMappingRequest.of(r ->
                    r.index(indexName).withJson(is)));
            log.info("Updated mapping for index: {}", indexName);
        } catch (IOException e) {
            throw new ElasticsearchException(
                    "Failed to update mapping", "updateMapping", indexName, e);
        }
    }

    /**
     * 删除索引（谨慎使用）
     */
    public void deleteIndex(String indexName) {
        try {
            client.indices().delete(r -> r.index(indexName));
            log.warn("Deleted elasticsearch index: {}", indexName);
        } catch (IOException e) {
            throw new ElasticsearchException(
                    "Failed to delete index", "deleteIndex", indexName, e);
        }
    }
}
