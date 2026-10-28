package com.arte.ainew.search;

import com.arte.ainew.common.search.SearchCursor;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import com.arte.ainew.spi.search.ConversationSearchReads;
import com.arte.core.pojo.platform.ExecutionOwner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.arte.ainew.search.SearchQueryFixture.query;
import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static org.junit.Assert.*;

public class ConversationSearchServiceTest {
    private SearchQueryFixture q;

    @Before
    public void setup() {
        q = new SearchQueryFixture();
    }

    @After
    public void cleanup() {
        q.close();
    }

    private SearchFailure failure(Runnable work, SearchFailure.Code code) {
        var e = assertThrows(SearchFailure.class, work::run);
        assertEquals(code, e.code());
        return e;
    }

    @Test
    public void forwardBackwardFirstAndRepeatedCursorDoNotSkipPrefetchedHits() {
        for (int n = 0; n < 7; n++) q.title("c" + n);
        var first = q.search(2, null);
        assertEquals(List.of("c0", "c1"), first.records().stream().map(ConversationSearch.Item::conversationId).toList());
        assertNull(first.previousCursor());
        var second = q.search(2, first.nextCursor());
        assertEquals(List.of("c2", "c3"), second.records().stream().map(ConversationSearch.Item::conversationId).toList());
        var retry = q.search(2, first.nextCursor());
        assertEquals(second.records(), retry.records());
        var back = q.search(2, second.previousCursor());
        assertEquals(first.records(), back.records());
        assertEquals(first.records(), q.search(2, second.firstCursor()).records());
        assertEquals(first.searchSessionId(), second.searchSessionId());
        assertTrue(q.index.requests.stream().allMatch(r -> r.pit().generation().equals("conversation-search-v1-g01")));
    }

    @Test
    public void filteringUsesConsumedCandidateBoundariesAndEmptyBudgetPageCanContinue() {
        for (int n = 0; n < 100; n++) q.index.add(SearchIndexRecord.title(OWNER, "missing-" + n, "marker", AT, 1));
        q.title("visible");
        var empty = q.search(2, null);
        assertTrue(empty.records().isEmpty());
        assertTrue(empty.partialPage());
        assertNotNull(empty.nextCursor());
        assertNull(empty.endReason());
        assertNull(empty.total());
        assertEquals(5, q.index.count);
        var last = q.search(2, empty.nextCursor());
        assertEquals("visible", last.records().getFirst().conversationId());
        assertNull(last.nextCursor());
        assertEquals(ConversationSearch.EndReason.END, last.endReason());
        var previous = q.search(2, last.previousCursor());
        assertTrue(previous.records().isEmpty());
        assertTrue(previous.partialPage());
        assertNotNull(previous.previousCursor());
    }

    @Test
    public void zeroCandidateEndCanReverseFromExplicitUnboundedEndWithoutLosingBoundary() {
        for (int n = 0; n < 20; n++) q.title("c" + n);
        var first = q.search(20, null);
        var end = q.search(20, first.nextCursor());
        assertTrue(end.records().isEmpty());
        assertNull(end.nextCursor());
        var back = q.search(20, end.previousCursor());
        assertEquals(first.records(), back.records());
    }

    @Test
    public void corruptOrForeignCursorAndChangedConditionsAreRejected() {
        q.title("one");
        q.title("two");
        var first = q.search(1, null);
        String token = first.nextCursor();
        failure(() -> q.search(1, token.substring(0, token.length() - 2) + "xx"), SearchFailure.Code.INVALID_CURSOR);
        failure(() -> q.search(2, token), SearchFailure.Code.INVALID_CURSOR);
        failure(() -> q.service.search(new ConversationSearch.Query("different", List.of(), 1, token), q.context()).block(), SearchFailure.Code.INVALID_CURSOR);
        var other = new ExecutionOwner(OWNER.tenantId(), OWNER.workspaceId(), "bob");
        failure(() -> q.service.search(query(1, token), q.context(other, "bob")).block(), SearchFailure.Code.SEARCH_EXPIRED);
    }

    @Test
    public void incompleteLaterInternalResponsePublishesNoPageAndRetryStartsOriginalBoundary() {
        for (int n = 0; n < 20; n++) q.index.add(SearchIndexRecord.title(OWNER, "missing-" + n, "marker", AT, 1));
        q.title("visible");
        q.index.failAt = 2;
        failure(() -> q.search(2, null), SearchFailure.Code.INCOMPLETE_RESPONSE);
        assertTrue(q.sessions.values.isEmpty());
        q.index.failAt = 0;
        var page = q.search(2, null);
        assertEquals("visible", page.records().getFirst().conversationId());
    }

    @Test
    public void failedContinuationKeepsOriginalBoundaryLatestPitAndDoesNotRenewIdle() {
        q.title("one");
        q.title("two");
        q.title("three");
        var first = q.search(1, null);
        var original = q.sessions.values.get(first.searchSessionId());
        q.clock.advance(Duration.ofMinutes(1));
        q.index.failAt = q.index.count + 1;
        failure(() -> q.search(1, first.nextCursor()), SearchFailure.Code.INCOMPLETE_RESPONSE);
        assertEquals(original.lastSuccessAt(), q.sessions.values.get(first.searchSessionId()).lastSuccessAt());
        q.index.failAt = 0;
        var next = q.search(1, first.nextCursor());
        assertEquals("two", next.records().getFirst().conversationId());
    }

    @Test
    public void successfulInternalBatchBeforeFailureRetainsLatestPitWithoutConsumingBoundary() {
        q.title("one");
        for (int n = 0; n < 25; n++) q.index.add(SearchIndexRecord.title(OWNER, "missing-" + n, "marker", AT, 1));
        q.title("two");
        var first = q.search(1, null);
        var original = q.sessions.values.get(first.searchSessionId());
        q.clock.advance(Duration.ofMinutes(1));
        q.index.failAt = q.index.count + 2;
        failure(() -> q.search(1, first.nextCursor()), SearchFailure.Code.INCOMPLETE_RESPONSE);
        var retained = q.sessions.values.get(first.searchSessionId());
        assertNotEquals(original.pitId(), retained.pitId());
        assertEquals(original.lastSuccessAt(), retained.lastSuccessAt());
        q.index.failAt = 0;
        assertEquals("two", q.search(1, first.nextCursor()).records().getFirst().conversationId());
    }

    @Test
    public void cursorPreservesNullAndNumericPrecisionAndDetectsChangedPayload() {
        var sort = Arrays.<Object>asList(new java.math.BigDecimal("0.12345678901234567890123456789"), null, "entry", new java.math.BigInteger("9007199254740993"));
        var page = new SearchCursor.Page(UUID.randomUUID().toString(), "v1", ConversationSearch.Direction.FORWARD, sort, true);
        String key = SearchCursor.randomKey(), token = SearchCursor.sign(page, key);
        assertEquals(sort, SearchCursor.verify(token, key, SearchCursor.Page.class).after());
        var changed = token.toCharArray();
        changed[10] = changed[10] == 'A' ? 'B' : 'A';
        failure(() -> SearchCursor.verify(new String(changed), key, SearchCursor.Page.class), SearchFailure.Code.INVALID_CURSOR);
    }

    @Test
    public void expiredReadDeadlineUsesExistingDeadlineErrorAndDoesNotReserveSession() {
        var context = q.context();
        q.clock.advance(Duration.ofMinutes(2));
        var e = assertThrows(com.arte.ainew.application.support.AdmissionException.class, () -> q.service.search(query(1, null), context).block());
        assertEquals(com.arte.core.enums.ResultCodeEnum.AI_DEADLINE_EXCEEDED, e.getResultCode());
        assertTrue(q.sessions.values.isEmpty());
    }

    @Test
    public void permissionRevokedBeforePublishingReturnsNoTextAndReleasesNewReservation() {
        q.title("secret");
        q.index.onRead = () -> q.denied.set(true);
        assertThrows(AccessDeniedException.class, () -> q.search(1, null));
        assertTrue(q.sessions.values.isEmpty());
    }

    @Test
    public void archiveDeleteAndOtherOwnerNeverExposeIndexFragments() {
        q.title("archived");
        q.f.archived.add("archived");
        var foreign = new ExecutionOwner(OWNER.tenantId(), OWNER.workspaceId(), "bob");
        q.index.add(SearchIndexRecord.title(foreign, "foreign", "secret marker", AT, 1));
        var page = q.search(10, null);
        assertTrue(page.records().isEmpty());
        assertEquals(ConversationSearch.EndReason.END, page.endReason());
    }

    @Test
    public void finalVisibilityCheckRemovesContentInvalidatedDuringPage() {
        q.title("one");
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        ConversationSearchReads changing = new ConversationSearchReads() {
            public Map<String, Visible> visible(ExecutionOwner owner, List<ConversationSearchIndex.Hit> hits) {
                if (counter.incrementAndGet() == 2) q.f.archived.add("one");
                return q.reads.visible(owner, hits);
            }

            public Window around(ExecutionOwner owner, Around request) {
                return q.reads.around(owner, request);
            }

            public void requireConversation(ExecutionOwner owner, String c, long v) {
                q.reads.requireConversation(owner, c, v);
            }

            public Set<String> readableInvocations(ExecutionOwner o, Map<String, com.arte.ainew.pojo.execution.Invocation> i) {
                return q.reads.readableInvocations(o, i);
            }
        };
        var service = q.service(q.index, q.sessions, changing, q.properties);
        assertTrue(service.search(query(1, null), q.context()).block().records().isEmpty());
    }

    @Test
    public void capDoesNotEvictOtherSearchesAndCloseIsIdempotent() {
        q.title("one");
        var one = q.search(1, null);
        q.search(1, null);
        q.search(1, null);
        failure(() -> q.search(1, null), SearchFailure.Code.SEARCH_LIMIT_REACHED);
        assertEquals(3, q.sessions.values.size());
        q.service.close(one.searchSessionId(), q.context()).block();
        q.service.close(one.searchSessionId(), q.context()).block();
        assertNotNull(q.search(1, null));
    }

    @Test
    public void expiredPitDoesNotPreventIndependentMessageHistoryLocation() {
        q.title("conv");
        q.f.turn("conv", "turn", "marker question", List.of());
        var first = q.search(1, null);
        q.clock.advance(Duration.ofMinutes(11));
        failure(() -> q.search(1, first.firstCursor()), SearchFailure.Code.SEARCH_EXPIRED);
        var around = q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "turn", 0L, 1, null), q.context()).block();
        assertEquals("turn", around.records().getFirst().turnId());
    }

    @Test
    public void closeWhileEsIsRunningCannotPublishOrResurrectSession() throws Exception {
        q.title("one");
        q.title("two");
        var first = q.search(1, null);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        q.index.onRead = () -> {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        };
        var future = q.service.search(query(1, first.nextCursor()), q.context()).toFuture();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        q.service.close(first.searchSessionId(), q.context()).block();
        release.countDown();
        var error = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof SearchFailure);
        assertFalse(q.sessions.values.containsKey(first.searchSessionId()));
    }

    @Test
    public void hardExpiryWinsOverSuccessfulPaginationAndRetentionCoversRenewal() {
        q.title("one");
        q.title("two");
        var first = q.search(1, null);
        q.clock.advance(Duration.ofMinutes(9));
        q.search(1, first.firstCursor());
        var request = q.index.requests.getLast();
        assertTrue(request.keepAlive().compareTo(Duration.ofMinutes(10)) > 0);
        q.clock.advance(Duration.ofMinutes(9));
        q.search(1, first.firstCursor());
        q.clock.advance(Duration.ofMinutes(9));
        q.search(1, first.firstCursor());
        q.clock.advance(Duration.ofMinutes(4));
        failure(() -> q.search(1, first.firstCursor()), SearchFailure.Code.SEARCH_EXPIRED);
    }
}
