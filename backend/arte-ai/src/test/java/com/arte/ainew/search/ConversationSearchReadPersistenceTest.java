package com.arte.ainew.search;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.search.SearchFailure;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.search.ConversationSearch;
import com.arte.ainew.pojo.search.SearchIndexRecord;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.search.SearchSyncFixture.OWNER;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

public class ConversationSearchReadPersistenceTest {
    SearchQueryFixture q;

    @Before
    public void setup() {
        q = new SearchQueryFixture();
    }

    @After
    public void cleanup() {
        q.close();
    }

    private void history() {
        q.f.conversation("conv", "title");
        for (int n = 1; n <= 12; n++) {
            var t = new Turn("t" + n, "conv", n, null, null, SearchSyncFixture.message("u" + n, com.arte.ainew.pojo.generation.ChatMessage.Role.USER, "question " + n), List.of(), null, 0, AT, AT);
            q.f.jdbc.update("INSERT INTO arte_ai_turn(id_key,conversation_key,sequence_no,workspace_snapshot) VALUES(?,?,?,?)", key(t.turnId()), key("conv"), n, q.f.codec.encode(t));
        }
    }

    @Test
    public void centeredWindowCompensatesEdgesAndBoundsToTenTurns() {
        history();
        var page = q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t6", 0L, 5, null), q.context()).block();
        assertEquals(List.of("t4", "t5", "t6", "t7", "t8"), page.records().stream().map(Turn::turnId).toList());
        assertNotNull(page.earlierCursor());
        assertNotNull(page.laterCursor());
        assertEquals(List.of("t1", "t2", "t3", "t4", "t5"), q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t1", 0L, 5, null), q.context()).block().records().stream().map(Turn::turnId).toList());
        assertEquals(List.of("t8", "t9", "t10", "t11", "t12"), q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t12", 0L, 5, null), q.context()).block().records().stream().map(Turn::turnId).toList());
        assertEquals("t6", q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t6", 0L, 1, null), q.context()).block().records().getFirst().turnId());
    }

    @Test
    public void historyCursorNavigatesOriginalVersionAndRejectsCrossConversationSizeAndTampering() {
        history();
        q.f.conversation("other", "other");
        var page = q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t6", 0L, 3, null), q.context()).block();
        var later = q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", null, null, 3, page.laterCursor()), q.context()).block();
        assertEquals(List.of("t8", "t9", "t10"), later.records().stream().map(Turn::turnId).toList());
        var earlier = q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", null, null, 3, page.earlierCursor()), q.context()).block();
        assertEquals(List.of("t2", "t3", "t4"), earlier.records().stream().map(Turn::turnId).toList());
        assertEquals(SearchFailure.Code.INVALID_CURSOR, assertThrows(SearchFailure.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("other", null, null, 3, page.laterCursor()), q.context()).block()).code());
        assertEquals(SearchFailure.Code.INVALID_CURSOR, assertThrows(SearchFailure.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", null, null, 2, page.laterCursor()), q.context()).block()).code());
        var c = q.f.codec.decode(q.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_conversation_new WHERE id_key=?", String.class, key("conv")), Conversation.class);
        q.f.jdbc.update("UPDATE arte_ai_conversation_new SET workspace_snapshot=? WHERE id_key=?", q.f.codec.encode(new Conversation(c.conversationId(), c.owner(), c.title(), 1, null, List.of(), c.state(), AT, AT)), key("conv"));
        assertEquals(ResultCodeEnum.AI_VERSION_CONFLICT, assertThrows(AdmissionException.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", null, null, 3, page.laterCursor()), q.context()).block()).getResultCode());
    }

    @Test
    public void missingTurnArchiveAndWrongVersionAreExplicitNotLatestHistoryFallback() {
        history();
        assertEquals(ResultCodeEnum.AI_TURN_NOT_FOUND, assertThrows(AdmissionException.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "missing", 0L, 5, null), q.context()).block()).getResultCode());
        assertEquals(ResultCodeEnum.AI_VERSION_CONFLICT, assertThrows(AdmissionException.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t1", 9L, 5, null), q.context()).block()).getResultCode());
        q.f.archived.add("conv");
        assertEquals(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND, assertThrows(AdmissionException.class, () -> q.service.queryTurnsAround(new ConversationSearch.AroundQuery("conv", "t1", 0L, 5, null), q.context()).block()).getResultCode());
    }

    @Test
    public void allHistoricalCandidatesAndPartialOutputsAreValidatedByRealMessageIdentity() {
        q.f.conversation("conv", "title");
        q.f.turn("conv", "turn", "question", List.of("old", "new"));
        var old = q.f.invocation("conv", "turn", "old", "partial marker", true, true);
        var current = q.f.invocation("conv", "turn", "new", "complete marker", false, true);
        q.index.add(SearchIndexRecord.message(OWNER, "conv", "turn", "answer-old", "old", 1, 1, "partial marker", AT, true, 1));
        q.index.add(SearchIndexRecord.message(OWNER, "conv", "turn", "answer-new", "new", 2, 1, "complete marker", AT, false, 1));
        q.index.add(SearchIndexRecord.message(OWNER, "conv", "turn", "does-not-exist", "new", 2, 1, "leaked marker", AT, false, 1));
        var visible = q.reads.visible(OWNER, q.index.hits);
        assertEquals(2, visible.size());
        assertEquals(1, visible.get(q.index.hits.getFirst().entryKey()).candidateOrdinal().intValue());
        assertEquals(Set.of("old", "new"), q.reads.readableInvocations(OWNER, Map.of("old", old, "new", current)));
        q.f.archived.add("conv");
        assertTrue(q.reads.visible(OWNER, q.index.hits).isEmpty());
        assertTrue(q.reads.readableInvocations(OWNER, Map.of("old", old, "new", current)).isEmpty());
    }

    @Test
    public void staleIndexTextDoesNotRequireSynchronousHashEqualityButDeletedCurrentMessageIsHidden() {
        q.f.conversation("conv", "current title");
        q.f.turn("conv", "turn", "current question", List.of());
        var old = SearchIndexRecord.message(OWNER, "conv", "turn", "user-turn", null, 0, 1, "older marker", AT, false, 1);
        q.index.add(old);
        var page = q.search(1, null);
        assertEquals(1, page.records().size());
        assertEquals("current title", page.records().getFirst().title());
        q.f.jdbc.update("DELETE FROM arte_ai_turn WHERE id_key=?", key("turn"));
        assertTrue(q.reads.visible(OWNER, q.index.hits).isEmpty());
    }
}
