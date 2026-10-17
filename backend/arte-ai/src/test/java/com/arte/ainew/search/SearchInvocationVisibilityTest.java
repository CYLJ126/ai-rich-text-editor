package com.arte.ainew.search;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.execution.DefaultExecutionEventService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.spi.conversation.ConversationContentVisibility;
import com.arte.core.enums.ResultCodeEnum;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static com.arte.ainew.search.SearchSyncFixture.AT;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

public class SearchInvocationVisibilityTest {

    SearchQueryFixture q;
    DefaultExecutionEventService events;

    @Before
    public void setup() {
        q = new SearchQueryFixture();
        q.f.conversation("conv", "title");
        q.f.turn("conv", "turn", "question", List.of("old", "new"));
        for (var id : List.of("old", "new")) {
            var i = q.f.invocation("conv", "turn", id, "answer " + id, id.equals("old"), true);
            var a = new Attempt(i.activeAttemptId(), id, 1, "worker", 1, AT.plusSeconds(60), 1, id.equals("old") ? Attempt.State.CANCELLED : Attempt.State.SUCCEEDED,
                    Attempt.Dispatch.CONFIRMED, null, null, Usage.unknown(), null, AT, AT);
            q.f.jdbc.update("INSERT INTO arte_ai_attempt(id_key,invocation_key,attempt_number,purpose,workspace_snapshot) VALUES(?,?,1,'EXECUTE',?)", key(a.attemptId()), key(id), q.f.codec.encode(a));
        }
        var store = new MybatisExecutionPersistence(q.f.ds, q.f.codec, q.scheduler);
        var payloads = new MybatisPayloadPersistence(q.f.ds, q.f.codec, q.scheduler);
        ConversationContentVisibility visibility = (owner, invocations) -> Mono.fromCallable(() -> q.reads.readableInvocations(owner, invocations)).subscribeOn(q.scheduler);
        events = new DefaultExecutionEventService(new AdmissionAuthorization(q.resolver, null, q.clock), store, store, payloads, null, q.clock, null, null, visibility);
    }

    @After
    public void cleanup() {
        q.close();
    }

    @Test
    public void historicalPartialCandidateRemainsReadableButArchiveInvalidatesCachedSnapshots() {
        var normal = events.snapshots(List.of("old", "new"), q.context()).block();
        assertNotNull(normal.getFirst().result());
        assertNotNull(normal.getLast().result());
        q.f.archived.add("conv");
        var hidden = events.snapshots(List.of("old", "new"), Map.of("old", 1L, "new", 1L), q.context()).block();
        assertTrue(hidden.stream().allMatch(s -> s.invocation() == null && s.result() == null));
        assertEquals(ResultCodeEnum.AI_NOT_FOUND, assertThrows(AdmissionException.class, () -> events.result("old", q.context()).block()).getResultCode());
        q.f.archived.clear();
        assertNotNull(events.result("old", q.context()).block());
    }

    @Test
    public void resultRemovedFromCurrentTurnCannotBeOpenedThroughOldSearchIdentity() {
        var t = q.f.codec.decode(q.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_turn WHERE id_key=?", String.class, key("turn")), com.arte.ainew.pojo.conversation.Turn.class);
        var updated = new com.arte.ainew.pojo.conversation.Turn(t.turnId(), t.conversationId(), t.sequence(), null, null, t.userMessage(), List.of("new"), null, 1, AT, AT);
        q.f.jdbc.update("UPDATE arte_ai_turn SET workspace_snapshot=? WHERE id_key=?", q.f.codec.encode(updated), key("turn"));
        var values = events.snapshots(List.of("old", "new"), q.context()).block();
        assertNull(values.getFirst().invocation());
        assertNotNull(values.getLast().result());
    }

    @Test
    public void ordinaryDetailUsedByLowerSearchActionRejectsArchivedParentWithoutDependingOnQueryFlag() {
        var store = new MybatisExecutionPersistence(q.f.ds, q.f.codec, q.scheduler);
        var service = new com.arte.ainew.application.conversation.DefaultConversationService(store,
                new AdmissionAuthorization(q.resolver, null, q.clock), q.clock,
                (owner, ids) -> Mono.just(ids.stream().filter(q.f.archived::contains).collect(java.util.stream.Collectors.toSet())));
        assertEquals("conv", service.find("conv", q.context()).block().conversationId());
        q.f.archived.add("conv");
        assertEquals(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND,
                assertThrows(AdmissionException.class, () -> service.find("conv", q.context()).block()).getResultCode());
    }

    @Test
    public void archiveReadFailureInOrdinaryDetailCannotFallBackToVisible() {
        var store = new MybatisExecutionPersistence(q.f.ds, q.f.codec, q.scheduler);
        var service = new com.arte.ainew.application.conversation.DefaultConversationService(store,
                new AdmissionAuthorization(q.resolver, null, q.clock), q.clock,
                (owner, ids) -> Mono.error(new IllegalStateException("test qualification unavailable")));
        assertThrows(IllegalStateException.class, () -> service.find("conv", q.context()).block());
    }
}
