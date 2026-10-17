package com.arte.ainew.infrastructure.search;

import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.spi.search.ConversationSearchIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话搜索 ES 查询构造器，组装归属过滤、全文匹配、排序、快照分页及高亮配置。
 * 强制使用可信主体范围过滤，查询结果仍需应用层复核当前读取资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchIndexDsl {

    private SearchIndexDsl() {
    }

    /**
     * 按请求构造包含全文匹配的搜索 DSL。
     */
    public static Map<String, Object> search(ConversationSearchIndex.Read read, NewAiSearchProperties properties) {
        return search(read, properties, true);
    }

    /**
     * 构造完整查询；无有效词项时返回零命中查询，并保留 PIT 续租和分页配置。
     */
    public static Map<String, Object> search(ConversationSearchIndex.Read read, NewAiSearchProperties properties, boolean hasTerms) {
        var filters = new ArrayList<Object>();
        filters.add(term("tenantId", read.owner().tenantId()));
        filters.add(term("workspaceId", read.owner().workspaceId()));
        filters.add(term("subjectId", read.owner().subjectId()));
        filters.add(term("searchable", true));
        if (!read.groupIds().isEmpty()) filters.add(Map.of("terms", Map.of("groupIds", read.groupIds())));
        var matches = new ArrayList<Object>();
        for (var field : List.of("title", "body", "title.identifier", "body.identifier"))
            matches.add(Map.of("match", Map.of(field, Map.of("query", read.query(), "operator", "and",
                    "zero_terms_query", "none", "boost", field.startsWith("title") ? 3.0 : 1.0))));
        if ("v2".equals(properties.index().rulesVersion())) {
            for (var field : List.of("title", "body")) {
                double boost = field.equals("title") ? 3.0 : 1.0;
                matches.add(Map.of("match", Map.of(field + ".recall", Map.of("query", read.query(), "operator", "and", "zero_terms_query", "none", "boost", boost * .5))));
                if (read.query().codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN))
                    matches.add(Map.of("match_phrase", Map.of(field + ".literal", Map.of("query", read.query(), "slop", 0, "zero_terms_query", "none", "boost", boost * .25))));
            }
        }
        boolean reverse = read.direction() == ConversationSearch.Direction.BACKWARD;
        var sorts = List.of(Map.of("_score", Map.of("order", reverse ? "asc" : "desc")),
                Map.of("generatedAt", Map.of("order", reverse ? "asc" : "desc", "missing", reverse ? "_first" : "_last",
                        "format", "strict_date_optional_time_nanos")),
                Map.of("entryKey", Map.of("order", reverse ? "desc" : "asc")),
                Map.of("_shard_doc", Map.of("order", reverse ? "desc" : "asc")));
        var body = new LinkedHashMap<String, Object>();
        body.put("size", read.size());
        body.put("track_total_hits", false);
        body.put("timeout", properties.limits().requestTimeout().toMillis() + "ms");
        body.put("pit", Map.of("id", read.pit().id(), "keep_alive", read.keepAlive().toMillis() + "ms"));
        body.put("query", hasTerms ? Map.of("bool", Map.of("filter", filters, "should", matches, "minimum_should_match", 1)) : Map.of("match_none", Map.of()));
        body.put("sort", sorts);
        if (!read.after().isEmpty()) body.put("search_after", read.after());
        // 回答全文由高亮器在 ES 内读取，仅将有界片段带回应用。
        body.put("_source", List.of("entryKey", "conversationKey", "tenantId", "workspaceId", "subjectId",
                "conversationId", "recordKind", "role", "turnId", "messageId", "invocationId", "turnSequence",
                "candidateOrdinal", "groupIds", "title", "generatedAt", "partial", "searchable", "sourceRevision", "extractorVersion"));
        body.put("highlight", Map.of("type", "unified", "encoder", "html", "pre_tags", List.of("<arte-hit>"), "post_tags", List.of("</arte-hit>"),
                "fields", List.of(Map.of("title", highlight(properties, "title")), Map.of("body", highlight(properties, "body")))));
        return body;
    }

    /**
     * 配置单个字段的有界高亮片段，并合并当前规则支持的辅助字段命中。
     */
    private static Map<String, Object> highlight(NewAiSearchProperties p, String field) {
        return Map.of("fragment_size", p.limits().previewMaxChars(), "number_of_fragments", 1,
                "no_match_size", p.limits().previewMaxChars(), "require_field_match", true,
                "matched_fields", "v2".equals(p.index().rulesVersion()) ? List.of(field, field + ".identifier", field + ".recall", field + ".literal") : List.of(field, field + ".identifier"));
    }

    private static Map<String, Object> term(String field, Object value) {
        return Map.of("term", Map.of(field, value));
    }
}
