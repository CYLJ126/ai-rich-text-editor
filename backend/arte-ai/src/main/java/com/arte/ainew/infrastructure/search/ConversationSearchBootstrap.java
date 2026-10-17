package com.arte.ainew.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.JsonpSerializable;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.spi.search.ConversationSearchIndexLock;
import com.arte.ainew.spi.search.ConversationSearchSyncStore;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import lombok.Getter;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serial;
import java.io.StringWriter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话搜索启动初始化器
 * <p>
 * 负责准备搜索索引、同步进度及读写别名。
 * 通过别名级互斥协调多实例初始化，仅补齐缺失资源，保留已有别名指向和映射。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ConversationSearchBootstrap {

    private final ElasticsearchClient elasticsearchClient;
    private final ConversationSearchIndexLock searchIndexLock;
    private final ConversationSearchSyncStore searchSyncStore;
    private final ConversationSearchWriter searchWriter;
    private final NewAiSearchProperties properties;
    private final JsonMapper json = JsonMapper.builder().build();
    @Getter
    private volatile boolean ready;

    public ConversationSearchBootstrap(ElasticsearchClient elasticsearchClient, ConversationSearchIndexLock searchIndexLock, ConversationSearchSyncStore searchSyncStore,
                                       ConversationSearchWriter searchWriter, NewAiSearchProperties properties) {
        this.searchIndexLock = searchIndexLock;
        this.searchSyncStore = searchSyncStore;
        this.searchWriter = searchWriter;
        this.properties = properties;
        var options = co.elastic.clients.transport.rest5_client.Rest5ClientOptions.of(elasticsearchClient._transportOptions()).toBuilder();
        var timeout = org.apache.hc.core5.util.Timeout.ofMilliseconds(properties.limits().requestTimeout().toMillis());
        options.restClientRequestOptionsBuilder().setRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                .setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build());
        this.elasticsearchClient = elasticsearchClient.withTransportOptions(options.build());
    }

    /**
     * ApplicationReady 后由后台线程调用；任何一步失败都保留已完成步骤，下一次重新核验。
     */
    public synchronized void initialize() throws Exception {
        if (ready) return;
        searchIndexLock.locked(() -> {
            prepare();
            return null;
        });
        ready = true;
    }

    /**
     * 校验 ES／IK 版本、索引结构及现有别名，首次部署时创建缺失索引；
     * 登记目标代际的同步进度，补齐读写别名并复核指向。
     */
    private void prepare() throws Exception {
        if (!properties.index().expectedServerVersion().equals(elasticsearchClient.info().version().number()))
            throw failure("ES_VERSION_MISMATCH");
        var nodes = elasticsearchClient.nodes().info(b -> b.metric(co.elastic.clients.elasticsearch.nodes.info.NodesInfoMetric.Plugins));
        if (nodes.nodeStats() == null || nodes.nodeStats().failed() != 0
                || nodes.nodeStats().total() != nodes.nodes().size()) throw failure("INCOMPLETE_NODE_INFORMATION");
        if (nodes.nodes().isEmpty() || nodes.nodes().values().stream().anyMatch(n -> n.plugins().stream().noneMatch(plugin ->
                "analysis-ik".equals(plugin.name()) && properties.index().expectedIkVersion().equals(plugin.version()))))
            throw failure("IK_VERSION_MISMATCH");
        String target = properties.sync().generation();
        String read = alias(properties.index().readAlias()), write = alias(properties.index().writeAlias());
        if (read != null && write != null && !read.equals(write)) throw failure("ALIASES_DISAGREE");
        String active = read != null ? read : write;
        // 先读取进度表，迁移遗漏时不在 ES 留下半初始化资源；旧进度不能证明重建空索引已回填。
        boolean registered = !"NOT_READY".equals(searchSyncStore.status(target).state());
        boolean exists = elasticsearchClient.indices().exists(b -> b.index(target)).value();
        if (!exists) {
            if (registered) throw failure("REGISTERED_INDEX_MISSING");
            if (active != null) throw failure("REBUILD_TARGET_NOT_PREPARED");
            try {
                searchWriter.createGeneration(target);
            } catch (ElasticsearchException e) {
                if (!"resource_already_exists_exception".equals(e.error().type())) throw e;
                // 与不经过本初始化器的建索引操作竞争时，仅吞掉已存在，随后完整核验。
            }
        }
        validate(target);
        if (active != null && !active.equals(target)) validate(active);
        searchSyncStore.register(target, properties.index().rulesVersion());
        String binding = active == null ? target : active;
        if (read == null || write == null) {
            // 只添加缺失项；禁止调用 activateGeneration（它会移除旧别名并切换）。
            var result = elasticsearchClient.indices().updateAliases(b -> {
                if (read == null)
                    b.actions(a -> a.add(add -> add.index(binding).alias(properties.index().readAlias())));
                if (write == null)
                    b.actions(a -> a.add(add -> add.index(binding).alias(properties.index().writeAlias()).isWriteIndex(true)));
                return b;
            });
            if (!result.acknowledged()) throw failure("ALIAS_NOT_ACKNOWLEDGED");
        }
        if (!binding.equals(alias(properties.index().readAlias())) || !binding.equals(alias(properties.index().writeAlias())))
            throw failure("ALIASES_CHANGED");
    }

    private String alias(String name) throws Exception {
        // 同名普通索引不能冒充别名，否则新建搜索可能绕过代际约束。
        try {
            var aliases = elasticsearchClient.indices().getAlias(b -> b.name(name)).aliases();
            if (aliases.size() != 1) throw failure("ALIAS_MULTIPLE_TARGETS");
            var entry = aliases.entrySet().iterator().next();
            var definition = entry.getValue().aliases().get(name);
            if (definition == null || definition.filter() != null || definition.routing() != null
                    || definition.searchRouting() != null || definition.indexRouting() != null
                    || Boolean.TRUE.equals(definition.isHidden())
                    || name.equals(properties.index().writeAlias()) && !Boolean.TRUE.equals(definition.isWriteIndex()))
                throw failure("ALIAS_DEFINITION_MISMATCH");
            return entry.getKey();
        } catch (ElasticsearchException e) {
            if (e.status() != 404) throw e;
            if (elasticsearchClient.indices().exists(b -> b.index(name)).value()) throw failure("ALIAS_NAME_OCCUPIED");
            return null;
        }
    }

    private void validate(String generation) throws Exception {
        if (!generation.startsWith(properties.index().generationPrefix() + "-") || generation.equals(properties.index().readAlias()) || generation.equals(properties.index().writeAlias()))
            throw failure("GENERATION_NAME_MISMATCH");
        var indices = elasticsearchClient.indices().get(b -> b.index(generation)).indices();
        if (!indices.keySet().equals(Set.of(generation))) throw failure("PHYSICAL_INDEX_REQUIRED");
        var state = indices.get(generation);
        Map<String, Object> expected = SearchIndexSchema.create(properties.index());
        requireSubset(expected.get("mappings"), asMap(state.mappings()), "MAPPING_MISMATCH");
        var settings = asMap(state.settings().index());
        @SuppressWarnings("unchecked") var desired = (Map<String, Object>) expected.get("settings");
        requireSubset(desired.get("analysis"), settings.get("analysis"), "ANALYZER_MISMATCH");
        if (!String.valueOf(properties.index().shards()).equals(String.valueOf(settings.get("number_of_shards"))))
            throw failure("SHARD_COUNT_MISMATCH");
        // 副本数可由部署运维动态调整，不据此修改已有索引。
    }

    private Map<?, ?> asMap(JsonpSerializable value) {
        if (value == null) return Map.of();
        var out = new StringWriter();
        var mapper = elasticsearchClient._transport().jsonpMapper();
        try (var generator = mapper.jsonProvider().createGenerator(out)) {
            value.serialize(generator, mapper);
        }
        return json.readValue(out.toString(), Map.class);
    }

    private void requireSubset(Object expected, Object actual, String code) {
        if (expected instanceof Map<?, ?> fields && actual instanceof Map<?, ?> found) {
            fields.forEach((key, value) -> requireSubset(value, found.get(key), code));
        } else if (!Objects.equals(expected, actual)) throw failure(code);
    }

    /**
     * 安全错误码可写入日志，不包含连接地址、依赖响应或用户内容。
     */
    public static final class InitializationFailure extends IllegalStateException {

        @Serial
        private static final long serialVersionUID = -381865743952795297L;

        private InitializationFailure(String code) {
            super(code);
        }
    }

    private InitializationFailure failure(String code) {
        return new InitializationFailure(code);
    }
}
