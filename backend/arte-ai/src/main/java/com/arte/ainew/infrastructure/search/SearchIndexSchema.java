package com.arte.ainew.infrastructure.search;

import com.arte.ainew.config.NewAiSearchProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话搜索索引的结构定义，统一生成字段映射、分词分析器及规则版本元数据。
 * 供索引创建和兼容性校验复用，本类只生成配置，不访问 ES。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchIndexSchema {

    private SearchIndexSchema() {
    }

    /**
     * 按索引配置生成 settings 和 mappings，包含当前规则版本所需的检索及高亮字段。
     */
    public static Map<String, Object> create(NewAiSearchProperties.Index cfg) {
        var fields = new LinkedHashMap<String, Object>();
        for (var field : List.of("entryKey", "conversationKey", "tenantId", "workspaceId", "subjectId",
                "conversationId", "turnId", "messageId", "invocationId", "recordKind", "role", "groupIds", "extractorVersion", "contentHash", "sourceRef"))
            fields.put(field, Map.of("type", "keyword"));
        for (var field : List.of("turnSequence", "sourceRevision")) fields.put(field, Map.of("type", "long"));
        fields.put("candidateOrdinal", Map.of("type", "integer"));
        fields.put("generatedAt", Map.of("type", "date_nanos"));
        fields.put("partial", Map.of("type", "boolean"));
        fields.put("searchable", Map.of("type", "boolean"));
        for (var field : List.of("title", "body")) {
            var subfields = new LinkedHashMap<String, Object>();
            subfields.put("identifier", Map.of("type", "text", "analyzer", "arte_search_identifier", "index_options", "offsets"));
            if ("v2".equals(cfg.rulesVersion())) {
                subfields.put("recall", Map.of("type", "text", "analyzer", "arte_search_recall", "search_analyzer", "arte_search_query", "index_options", "offsets"));
                subfields.put("literal", Map.of("type", "text", "analyzer", "arte_search_literal", "index_options", "offsets"));
            }
            fields.put(field, Map.of("type", "text",
                    "analyzer", "arte_search_index", "search_analyzer", "arte_search_query", "index_options", "offsets",
                    "fields", subfields));
        }
        var analysis = Map.of("analyzer", Map.of(
                        "arte_search_index", Map.of("type", "custom", "tokenizer", cfg.indexTokenizer(), "filter", List.of("lowercase")),
                        "arte_search_query", Map.of("type", "custom", "tokenizer", cfg.queryTokenizer(), "filter", List.of("lowercase")),
                        "arte_search_identifier", Map.of("type", "pattern", "pattern", "[^\\p{L}\\p{N}_-]+", "lowercase", true),
                        "arte_search_recall", Map.of("type", "custom", "tokenizer", "ik_max_word", "filter", List.of("lowercase")),
                        // 中文逐字、其他文字按词；只用于连续短语补召回，不做任意散字 OR 匹配。
                        "arte_search_literal", Map.of("type", "custom", "tokenizer", "arte_search_literal_tokens", "filter", List.of("lowercase"))),
                "tokenizer", Map.of("arte_search_literal_tokens", Map.of("type", "pattern", "group", 0, "pattern", "[\\p{IsHan}]|[^\\p{IsHan}\\W]+")));
        return Map.of("settings", Map.of("number_of_shards", cfg.shards(), "number_of_replicas", cfg.replicas(), "analysis", analysis),
                "mappings", Map.of("dynamic", "strict", "_meta", Map.of("rulesVersion", cfg.rulesVersion(),
                        "esVersion", cfg.expectedServerVersion(), "ikVersion", cfg.expectedIkVersion()), "properties", fields));
    }
}
