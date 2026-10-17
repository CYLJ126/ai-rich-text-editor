package com.arte.ainew.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.pojo.search.SearchSync.Desired;
import com.arte.ainew.pojo.search.SearchSync.Mutation;
import com.arte.ainew.pojo.search.SearchSync.Scope;
import com.arte.ainew.pojo.search.SearchSync.WriteResult;
import com.arte.ainew.spi.search.ConversationSearchWriter;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringReader;
import java.util.*;

/**
 * 固定物理目标，逐项同步版本检查；归档额外按会话清理未知残留。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ElasticsearchConversationSearchWriter implements ConversationSearchWriter {

    private final ElasticsearchClient client;
    private final NewAiSearchProperties properties;
    private final Set<String> verified = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ElasticsearchConversationSearchWriter(ElasticsearchClient client, NewAiSearchProperties properties) {
        this.properties = Objects.requireNonNull(properties);
        var options = co.elastic.clients.transport.rest5_client.Rest5ClientOptions.of(Objects.requireNonNull(client)._transportOptions()).toBuilder();
        var timeout = org.apache.hc.core5.util.Timeout.ofMilliseconds(properties.limits().requestTimeout().toMillis());
        options.restClientRequestOptionsBuilder().setRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                .setResponseTimeout(timeout).setConnectionRequestTimeout(timeout).build());
        this.client = client.withTransportOptions(options.build());
    }

    private void target(String g) {
        if (g == null || !g.matches("[a-z][a-z0-9_-]{0,127}") || !g.startsWith(properties.index().generationPrefix() + "-")
                || g.equals(properties.index().readAlias()) || g.equals(properties.index().writeAlias()))
            throw new IllegalArgumentException("Physical search generation required");
    }

    private void physical(String g) throws Exception {
        target(g);
        if (verified.contains(g)) return;
        var response = client.indices().get(b -> b.index(g));
        if (!response.indices().keySet().equals(Set.of(g)))
            throw new IllegalArgumentException("Search target is not a physical index");
        var mapping = response.indices().get(g).mappings();
        if (mapping == null || mapping.meta().get("rulesVersion") == null || !properties.index().rulesVersion().equals(mapping.meta().get("rulesVersion").to(String.class)))
            throw new IllegalStateException("Physical generation rules mismatch");
        verified.add(g);
    }

    @Override
    public List<WriteResult> write(String g, List<Mutation> mutations, Runnable beforeSend) throws Exception {
        physical(g);
        if (mutations.isEmpty()) return List.of();
        if (mutations.size() > 256) throw new IllegalArgumentException("Index bulk exceeds source limit");
        var bulk = new BulkRequest.Builder();
        var positions = new ArrayList<Integer>();
        var results = new ArrayList<WriteResult>(Collections.nCopies(mutations.size(), null));
        var deleteIds = mutations.stream().filter(m -> m.desired() == Desired.DELETE).map(m -> m.record().entryKey()).toList();
        var currentDeletes = new HashMap<String, co.elastic.clients.elasticsearch.core.get.GetResult<Map>>();
        if (!deleteIds.isEmpty()) {
            var found = client.mget(b -> b.index(g).ids(deleteIds).sourceIncludes("sourceRevision"), Map.class);
            if (found.docs().size() != deleteIds.size())
                throw new IllegalStateException("Incomplete delete precondition read");
            for (int n = 0; n < deleteIds.size(); n++) {
                var item = found.docs().get(n);
                if (item.isResult() && item.result().id().equals(deleteIds.get(n)))
                    currentDeletes.put(deleteIds.get(n), item.result());
            }
        }
        for (int n = 0; n < mutations.size(); n++) {
            var m = mutations.get(n);
            var r = m.record();
            if (m.desired() == Desired.DELETE) {
                var current = currentDeletes.get(r.entryKey());
                if (current == null) {
                    results.set(n, new WriteResult(r.entryKey(), false, "DELETE_READ_FAILED"));
                    continue;
                }
                if (!current.found()) {
                    results.set(n, new WriteResult(r.entryKey(), true, null));
                    continue;
                }
                Object observed = current.source() == null ? null : current.source().get("sourceRevision");
                if (observed instanceof Number revision && revision.longValue() > r.sourceRevision()) {
                    results.set(n, new WriteResult(r.entryKey(), false, "VERSION_CONFLICT"));
                    continue;
                }
                bulk.operations(op -> op.delete(d -> d.index(g).id(r.entryKey()).ifSeqNo(current.seqNo()).ifPrimaryTerm(current.primaryTerm())));
            } else {
                // 物理删除会推进 ES 内部版本；业务同步版本独立放在 _source 中仲裁。
                bulk.operations(op -> op.update(u -> u.index(g).id(r.entryKey()).action(action -> action
                        .script(script -> script.lang("painless").source(src -> src.scriptString("if (ctx._source.sourceRevision != null && ctx._source.sourceRevision > params.doc.sourceRevision) { ctx.op = 'noop'; } else { ctx._source = params.doc; }"))
                                .params("doc", co.elastic.clients.json.JsonData.of(r.source())))
                        .scriptedUpsert(true).upsert(r.source()))));
            }
            positions.add(n);
        }
        if (!positions.isEmpty()) {
            beforeSend.run();
            var response = client.bulk(bulk.build());
            if (response.items().size() != positions.size()) throw new IllegalStateException("Incomplete index bulk");
            for (int i = 0; i < positions.size(); i++) {
                int n = positions.get(i);
                var item = response.items().get(i);
                var mutation = mutations.get(n);
                if (!item.id().equals(mutation.record().entryKey()) || !g.equals(item.index()))
                    throw new IllegalStateException("Mismatched index bulk item");
                boolean obsolete = "noop".equals(item.result());
                boolean ok = !obsolete && item.error() == null && (item.status() >= 200 && item.status() < 300 || mutation.desired() == Desired.DELETE && item.status() == 404);
                results.set(n, new WriteResult(item.id(), ok, ok ? null : obsolete || item.status() == 409 ? "VERSION_CONFLICT" : "ES_ITEM_FAILED"));
            }
        }
        return List.copyOf(results);
    }

    @Override
    public boolean purgeArchived(String g, Scope scope, Runnable beforeDelete) throws Exception {
        physical(g);
        var response = client.search(b -> b.index(g).size(100).seqNoPrimaryTerm(true)
                .source(s -> s.fetch(false)).allowPartialSearchResults(false).timeout(properties.limits().requestTimeout().toMillis() + "ms")
                .query(q -> q.term(t -> t.field("conversationKey").value(SearchIndexRecord.key(scope.owner(), scope.conversationId()))))
                .sort(s -> s.field(f -> f.field("entryKey").order(SortOrder.Asc))), Void.class);
        ElasticsearchConversationSearchIndex.requireComplete(response.timedOut(), response.shards().failed().intValue(), Boolean.TRUE.equals(response.terminatedEarly()));
        if (response.hits().hits().isEmpty()) return true;
        var bulk = new BulkRequest.Builder();
        for (var hit : response.hits().hits()) {
            if (hit.seqNo() == null || hit.primaryTerm() == null)
                throw new IllegalStateException("Missing conditional delete token");
            bulk.operations(op -> op.delete(d -> d.index(g).id(hit.id()).ifSeqNo(hit.seqNo()).ifPrimaryTerm(hit.primaryTerm())));
        }
        beforeDelete.run();
        var deleted = client.bulk(bulk.build());
        if (deleted.items().size() != response.hits().hits().size() || deleted.items().stream().anyMatch(i -> i.error() != null || i.status() != 200 && i.status() != 404))
            throw new IllegalStateException("Archive purge needs retry");
        return response.hits().hits().size() < 100;
    }

    @Override
    public void createGeneration(String g) throws Exception {
        target(g);
        if (!properties.index().expectedServerVersion().equals(client.info().version().number()))
            throw new IllegalStateException("Elasticsearch version differs from configured generation");
        String schema = JsonMapper.builder().build().writeValueAsString(SearchIndexSchema.create(properties.index()));
        var result = client.indices().create(b -> b.index(g).withJson(new StringReader(schema)));
        if (!result.acknowledged() || !result.shardsAcknowledged())
            throw new IllegalStateException("Generation creation not acknowledged");
    }

    @Override
    public void activateGeneration(String g) throws Exception {
        physical(g);
        client.indices().refresh(b -> b.index(g));
        var result = client.indices().updateAliases(b -> b
                .actions(a -> a.remove(r -> r.index("*").alias(properties.index().readAlias()).mustExist(false)))
                .actions(a -> a.remove(r -> r.index("*").alias(properties.index().writeAlias()).mustExist(false)))
                .actions(a -> a.add(add -> add.index(g).alias(properties.index().readAlias())))
                .actions(a -> a.add(add -> add.index(g).alias(properties.index().writeAlias()).isWriteIndex(true))));
        if (!result.acknowledged()) throw new IllegalStateException("Alias switch not acknowledged");
    }
}
