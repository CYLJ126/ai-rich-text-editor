package com.arte.ainew.application.search;

import com.arte.ainew.api.conversation.ConversationSearchService;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.common.search.SearchPreview;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.pojo.search.SearchSession;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchReads;
import com.arte.ainew.spi.search.ConversationSearchSessions;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.apache.commons.text.StringEscapeUtils;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.arte.ainew.pojo.search.ConversationSearch.*;

/**
 * Redis 只协调 PIT 和活动期限；游标自身携带扫描边界，重试不消费共享分页位置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class DefaultConversationSearchService implements ConversationSearchService {

    private final ConversationSearchIndex searchIndex;
    private final ConversationSearchSessions searchSessions;
    private final ConversationSearchReads searchReads;
    private final SearchReadAuthorization authorization;
    private final NewAiSearchProperties properties;
    private final Scheduler scheduler;
    private final Scheduler cleanup;
    private final Clock clock;

    public DefaultConversationSearchService(ConversationSearchIndex searchIndex, ConversationSearchSessions searchSessions, ConversationSearchReads searchReads,
                                            SearchReadAuthorization authorization, NewAiSearchProperties properties, Scheduler scheduler, Scheduler cleanup, Clock clock) {
        this.searchIndex = searchIndex;
        this.searchSessions = searchSessions;
        this.searchReads = searchReads;
        this.authorization = authorization;
        this.properties = properties;
        this.scheduler = scheduler;
        this.cleanup = cleanup;
        this.clock = clock;
    }

    private final class Operation {
        final long deadline = System.nanoTime() + properties.limits().requestTimeout().toNanos();
        final AtomicBoolean cancelled = new AtomicBoolean();
        SearchSession lease;
        ConversationSearchIndex.Pit latest;
        String newId;
        long baseNano;

        Duration remaining() {
            long ns = deadline - System.nanoTime();
            if (cancelled.get() || ns <= 0) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
            return Duration.ofNanos(ns);
        }

        long serverNow() {
            return lease.serverNow() + Math.max(0, (System.nanoTime() - baseNano) / 1_000_000);
        }

        Duration retention() {
            long remaining = lease.hardExpiresAt() - serverNow();
            long wanted = properties.session().idleTimeout().toMillis() + properties.limits().requestTimeout().toMillis() + properties.session().redisTimeout().toMillis() * 2;
            long ms = Math.min(remaining, wanted);
            if (ms <= 1) throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
            return Duration.ofMillis(ms);
        }

        void remember(ExecutionOwner owner, ConversationSearchIndex.Pit pit, long expires) {
            if (latest != null && !latest.generation().equals(pit.generation()))
                throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
            latest = pit;
            lease = searchSessions.pit(owner, lease, pit, expires, remaining());
            baseNano = System.nanoTime();
        }
    }

    private <T> Mono<T> execute(Function<Operation, T> work) {
        return Mono.defer(() -> {
            if (!properties.enabled() || !properties.queryEnabled())
                return Mono.error(new SearchFailure(SearchFailure.Code.SEARCH_NOT_ENABLED));
            var op = new Operation();
            return Mono.fromCallable(() -> {
                        try {
                            return work.apply(op);
                        } catch (SearchFailure | AccessDeniedException | com.arte.ainew.application.support.AdmissionException |
                                 IllegalArgumentException known) {
                            throw known;
                        } catch (Exception failure) {
                            throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE, failure);
                        }
                    }).subscribeOn(scheduler).doOnCancel(() -> op.cancelled.set(true))
                    .timeout(properties.limits().requestTimeout(), Mono.error(new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE)));
        });
    }

    @Override
    public Mono<Page> search(Query query, ExecutionContext context) {
        return execute(op -> {
            var owner = ExecutionOwners.from(context);
            boolean success = false;
            boolean expire = false;
            try {
                if (query.size() > properties.limits().maxPageSize())
                    throw new IllegalArgumentException("Search page exceeds configured limit");
                authorization.require(context, true, op.remaining());
                String hash = CanonicalJson.sha256(CanonicalJson.encode(Map.of("owner", SearchCursor.owner(owner), "query", query.query(), "groups", query.groupIds(), "size", query.size())));
                SearchCursor.Page cursor;
                if (query.cursor() == null) {
                    op.newId = UUID.randomUUID().toString();
                    op.lease = searchSessions.reserve(owner, op.newId, hash, query.size(), properties.index().rulesVersion(), op.remaining());
                    op.baseNano = System.nanoTime();
                    var retention = op.retention();
                    long expires = op.serverNow() + retention.toMillis();
                    var pit = searchIndex.open(retention).doOnDiscard(ConversationSearchIndex.Pit.class, this::closePit).block(op.remaining());
                    if (pit == null) throw new SearchFailure(SearchFailure.Code.DEPENDENCY_UNAVAILABLE);
                    op.remember(owner, pit, expires);
                    cursor = new SearchCursor.Page(op.lease.id(), op.lease.rulesVersion(), Direction.FIRST, List.of(), false);
                } else {
                    var hint = SearchCursor.peek(query.cursor());
                    op.lease = searchSessions.acquire(owner, hint.session(), hash, query.size(), op.remaining());
                    op.baseNano = System.nanoTime();
                    op.latest = op.lease.pit();
                    cursor = SearchCursor.verify(query.cursor(), op.lease.secret(), SearchCursor.Page.class);
                    if (!cursor.rules().equals(op.lease.rulesVersion()))
                        throw new SearchFailure(SearchFailure.Code.INVALID_CURSOR);
                    if (!op.lease.rulesVersion().equals(properties.index().rulesVersion()))
                        throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
                }
                var page = page(op, owner, query, cursor, context);
                op.remaining();
                searchSessions.complete(owner, op.lease, op.remaining());
                success = true;
                return page;
            } catch (SearchFailure failure) {
                expire = failure.code() == SearchFailure.Code.SEARCH_EXPIRED;
                throw failure;
            } finally {
                if (op.newId != null && !success) abortReservation(owner, op.newId, op.latest);
                else if (expire && op.lease != null) closeSession(owner, op.lease.id());
                else if (!success && op.lease != null) release(owner, op.lease);
            }
        });
    }

    private Page page(Operation op, ExecutionOwner owner, Query query, SearchCursor.Page cursor, ExecutionContext context) {
        var visibleHits = new ArrayList<ConversationSearchIndex.Hit>();
        List<Object> boundary = cursor.after(), first = null, last = null;
        int examined = 0, requests = 0;
        boolean ended = false;
        var seen = new HashSet<String>();
        while (visibleHits.size() < query.size() && examined < properties.limits().candidateBudget() && requests < properties.limits().maxEsRequests()) {
            op.remaining();
            var keepAlive = op.retention();
            long expires = op.serverNow() + keepAlive.toMillis();
            int take = Math.min(properties.limits().maxPageSize(), properties.limits().candidateBudget() - examined);
            var batch = searchIndex.read(new ConversationSearchIndex.Read(owner, query.query(), query.groupIds(), op.latest, cursor.direction() == Direction.FIRST ? Direction.FORWARD : cursor.direction(), boundary, take, keepAlive)).block(op.remaining());
            if (batch == null) throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
            op.remember(owner, batch.pit(), expires);
            requests++;
            if (batch.hits().size() > take) throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
            var allowed = searchReads.visible(owner, batch.hits());
            op.remaining();
            int consumed = 0;
            for (var hit : batch.hits()) {
                if (!seen.add(hit.entryKey())) throw new SearchFailure(SearchFailure.Code.INCOMPLETE_RESPONSE);
                if (first == null) first = hit.sort();
                last = hit.sort();
                boundary = hit.sort();
                examined++;
                consumed++;
                var item = allowed.get(hit.entryKey());
                if (item != null) visibleHits.add(hit);
                if (visibleHits.size() == query.size()) break;
            }
            if (consumed == batch.hits().size() && batch.hits().size() < take) {
                ended = true;
                break;
            }
            if (visibleHits.size() == query.size()) break;
        }
        // 最后再核验当前资格；撤权／归档／删除不能用请求早先的判断放行。
        authorization.require(context, true, op.remaining());
        var current = searchReads.visible(owner, visibleHits);
        op.remaining();
        var items = new ArrayList<Item>();
        for (var hit : visibleHits) {
            var allowed = current.get(hit.entryKey());
            if (allowed != null) items.add(item(hit, allowed));
        }
        if (cursor.direction() == Direction.BACKWARD) Collections.reverse(items);
        boolean reverse = cursor.direction() == Direction.BACKWARD;
        List<Object> consumedStart = first == null ? cursor.after() : first, consumedEnd = last == null ? cursor.after() : last;
        String same = ended ? null : token(op, reverse ? Direction.BACKWARD : Direction.FORWARD, consumedEnd, true);
        String opposite = cursor.opposite() ? token(op, reverse ? Direction.FORWARD : Direction.BACKWARD, first == null && ended ? List.of() : consumedStart, true) : null;
        String previous = reverse ? same : opposite, next = reverse ? opposite : same;
        String start = token(op, Direction.FIRST, List.of(), false);
        boolean partial = !ended && items.size() < query.size();
        return new Page(op.lease.id(), List.copyOf(items), previous, next, start, ended ? EndReason.END : null, partial, null);
    }

    private String token(Operation op, Direction direction, List<Object> after, boolean opposite) {
        return SearchCursor.sign(new SearchCursor.Page(op.lease.id(), op.lease.rulesVersion(), direction, after, opposite), op.lease.secret());
    }

    private Item item(ConversationSearchIndex.Hit hit, ConversationSearchReads.Visible current) {
        Kind kind = "TITLE".equals(hit.source().get("recordKind")) ? Kind.TITLE : Kind.MESSAGE;
        var fragments = hit.highlights().getOrDefault(kind == Kind.TITLE ? "title" : "body", List.of());
        String encoded = fragments.isEmpty() ? StringEscapeUtils.escapeHtml4(current.fallbackText()) : fragments.getFirst();
        var preview = SearchPreview.fromEncodedHighlight(encoded, properties.limits().previewMaxChars());
        return new Item(hit.entryKey(), (String) hit.source().get("conversationId"), current.title(), kind, preview, kind == Kind.MESSAGE,
                current.turnId(), current.messageId(), current.invocationId(), current.candidateOrdinal(), current.partial(), current.candidate());
    }

    @Override
    public Mono<Void> close(String id, ExecutionContext context) {
        return execute(op -> {
            authorization.require(context, false, op.remaining());
            var closed = searchSessions.close(ExecutionOwners.from(context), id, op.remaining());
            if (closed != null) closePit(closed.pit());
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public Mono<AroundPage> queryTurnsAround(AroundQuery query, ExecutionContext context) {
        return execute(op -> {
            authorization.require(context, false, op.remaining());
            var owner = ExecutionOwners.from(context);
            String signing = searchSessions.historySigningKey(op.remaining());
            SearchCursor.History history = null;
            ConversationSearchReads.Around request;
            if (query.historyCursor() == null)
                request = new ConversationSearchReads.Around(query.conversationId(), query.turnId(), query.expectedVersion(), query.size(), false, null);
            else {
                history = SearchCursor.verify(query.historyCursor(), signing, SearchCursor.History.class);
                if (!SearchCursor.owner(owner).equals(history.owner()) || !query.conversationId().equals(history.conversation()) || query.size() != history.size()
                        || history.direction() == Direction.FIRST || history.sequence() < 1 || history.version() < 0)
                    throw new SearchFailure(SearchFailure.Code.INVALID_CURSOR);
                if (history.expiresAt() <= clock.millis()) throw new SearchFailure(SearchFailure.Code.SEARCH_EXPIRED);
                request = new ConversationSearchReads.Around(query.conversationId(), history.turn(), history.version(), query.size(), history.direction() == Direction.BACKWARD, history.sequence());
            }
            var confirmed = searchReads.around(owner, request);
            authorization.require(context, false, op.remaining());
            searchReads.requireConversation(owner, request.conversationId(), request.version());
            op.remaining();
            long expires = history == null ? clock.millis() + properties.session().maxLifetime().toMillis() : history.expiresAt();
            var first = confirmed.turns().isEmpty() ? null : confirmed.turns().getFirst();
            var last = confirmed.turns().isEmpty() ? null : confirmed.turns().getLast();
            String earlier = confirmed.hasEarlier() && first != null ? history(owner, query, confirmed.version(), Direction.BACKWARD, first, expires, signing) : null;
            String later = confirmed.hasLater() && last != null ? history(owner, query, confirmed.version(), Direction.FORWARD, last, expires, signing) : null;
            return new AroundPage(confirmed.version(), confirmed.turns(), earlier, later);
        });
    }

    private String history(ExecutionOwner owner, AroundQuery query, long version, Direction direction, com.arte.ainew.pojo.conversation.Turn turn, long expires, String key) {
        return SearchCursor.sign(new SearchCursor.History(SearchCursor.owner(owner), query.conversationId(), version, query.size(), direction, turn.sequence(), turn.turnId(), expires), key);
    }

    private void closePit(ConversationSearchIndex.Pit pit) {
        if (pit == null) return;
        searchIndex.close(pit).timeout(properties.session().redisTimeout()).onErrorResume(e -> Mono.empty()).subscribe();
    }

    private void closeSession(ExecutionOwner owner, String id) {
        Mono.fromRunnable(() -> {
            try {
                var old = searchSessions.close(owner, id, properties.session().redisTimeout());
                if (old != null) closePit(old.pit());
            } catch (Exception ignored) {
            }
        }).subscribeOn(cleanup).subscribe();
    }

    private void abortReservation(ExecutionOwner owner, String id, ConversationSearchIndex.Pit pit) {
        try {
            var old = searchSessions.cancelReservation(owner, id, properties.session().redisTimeout());
            if (old != null) closePit(old.pit());
        } catch (Exception ignored) {
        } finally {
            closePit(pit);
        }
    }

    private void release(ExecutionOwner owner, SearchSession lease) {
        try {
            searchSessions.release(owner, lease, properties.session().redisTimeout());
        } catch (Exception ignored) {
        }
    }
}
