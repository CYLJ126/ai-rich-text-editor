package com.arte.ainew.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.json.JsonMapper;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * ES 协议适配及第一阶段原型；不负责授权、不开放 HTTP、不启动索引写入。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class ElasticsearchConversationSearchIndex implements ConversationSearchIndex {

    private final ElasticsearchClient client;
    private final NewAiSearchProperties properties;
    private final Scheduler scheduler;
    private final java.util.function.BooleanSupplier ready;
    private final JsonMapper json = JsonMapper.builder().build();

    public ElasticsearchConversationSearchIndex(ElasticsearchClient client, NewAiSearchProperties properties, Scheduler scheduler) {
        this(client, properties, scheduler, () -> true);
    }

    public ElasticsearchConversationSearchIndex(ElasticsearchClient client, NewAiSearchProperties properties, Scheduler scheduler,
                                                java.util.function.BooleanSupplier ready) {
        this.client = Objects.requireNonNull(client);
        this.properties = Objects.requireNonNull(properties);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.ready = Objects.requireNonNull(ready);
    }

    @Override
    public Mono<Pit> open(Duration keepAlive) {
        return io(() -> {
            if (!ready.getAsBoolean()) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
            retention(keepAlive);
            var generations = client.indices().getAlias(b -> b.name(properties.index().readAlias())).aliases().keySet();
            if (generations.size() != 1) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
            String generation = generations.iterator().next();
            var response = client.openPointInTime(b -> b.index(generation).keepAlive(t -> t.time(keepAlive.toMillis() + "ms"))
                    .allowPartialSearchResults(false));
            if (response.shards().failed().intValue() != 0) {
                try {
                    client.closePointInTime(b -> b.id(response.id()));
                } catch (Exception ignored) { /* keep_alive 兜底 */ }
                throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
            }
            return new Pit(response.id(), generation);
        });
    }

    @Override
    public Mono<Batch> read(Read request) {
        return io(() -> {
            if (!ready.getAsBoolean()) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
            retention(request.keepAlive());
            co.elastic.clients.elasticsearch.core.SearchResponse<Map> response;
            try {
                // 逐字短语辅助字段不能把全停用词重新变为命中；仍执行 PIT search 来真实续租。
                boolean hasTerms = !"v2".equals(properties.index().rulesVersion()) || !client.indices().analyze(b -> b.index(request.pit().generation())
                        .analyzer("arte_search_query").text(request.query())).tokens().isEmpty();
                response = client.search(b -> b.withJson(new StringReader(json.writeValueAsString(SearchIndexDsl.search(request, properties, hasTerms))))
                        .allowPartialSearchResults(false), Map.class);
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException failure) {
                if (failure.status() == 404) throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED, failure);
                throw failure;
            }
            requireComplete(response.timedOut(), response.shards().failed().intValue(), Boolean.TRUE.equals(response.terminatedEarly()));
            var hits = new ArrayList<Hit>();
            for (var hit : response.hits().hits()) {
                if (hit.source() == null || !hit.id().equals(hit.source().get("entryKey")))
                    throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
                @SuppressWarnings("unchecked") Map<String, Object> source = (Map<String, Object>) hit.source();
                hits.add(new Hit(hit.id(), source, hit.sort().stream().map(ElasticsearchConversationSearchIndex::sortValue).toList(), hit.highlight()));
            }
            return new Batch(new Pit(response.pitId() == null ? request.pit().id() : response.pitId(), request.pit().generation()), hits);
        });
    }

    @Override
    public Mono<Void> close(Pit pit) {
        return io(() -> {
            try {
                client.closePointInTime(b -> b.id(pit.id()));
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException failure) {
                if (failure.status() != 404) throw failure;
            }
            return Boolean.TRUE;
        }).then();
    }

    public static void requireComplete(boolean timedOut, int failedShards, boolean terminatedEarly) {
        if (timedOut || failedShards != 0 || terminatedEarly)
            throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
    }

    private static Object sortValue(FieldValue value) {
        if (value.isNull()) return null;
        if (value.isLong()) return value.longValue();
        if (value.isDouble()) return value.doubleValue();
        if (value.isString()) return value.stringValue();
        throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
    }

    private void retention(Duration keepAlive) {
        if (keepAlive == null || keepAlive.toMillis() < 1 || keepAlive.compareTo(properties.session().maxLifetime()) > 0)
            throw new IllegalArgumentException("Invalid PIT retention");
    }

    private <T> Mono<T> io(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(scheduler).onErrorMap(error ->
                error instanceof SearchFailure || error instanceof IllegalArgumentException ? error
                        : new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, error));
    }
}
