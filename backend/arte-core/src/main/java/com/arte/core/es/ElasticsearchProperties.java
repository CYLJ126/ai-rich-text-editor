package com.arte.core.es;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Elasticsearch 配置属性
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 09:40 ✾
 **/
@ConfigurationProperties(prefix = "elasticsearch")
public record ElasticsearchProperties(
        @DefaultValue({"http://localhost:9200"})
        List<String> uris,
        String username,
        String password,
        @DefaultValue("30s") Duration connectTimeout,
        @DefaultValue("60s") Duration socketTimeout,
        @DefaultValue("10s") Duration connectionRequestTimeout,
        Pool pool,
        Bulk bulk,
        Index index
) {
    public record Pool(
            @DefaultValue("20") int maxConnTotal,
            @DefaultValue("10") int maxConnPerRoute
    ) {
    }

    public record Bulk(
            @DefaultValue("500") int batchSize,
            @DefaultValue("5242880") long maxBytesPerBatch, // 5MB
            @DefaultValue("30s") Duration flushInterval
    ) {
    }

    public record Index(
            @DefaultValue("articles") String documentIndex,
            @DefaultValue("article_chunks") String chunkIndex,
            @DefaultValue("3") int documentShards,
            @DefaultValue("1") int documentReplicas,
            @DefaultValue("5") int chunkShards,
            @DefaultValue("1") int chunkReplicas
    ) {
    }
}
