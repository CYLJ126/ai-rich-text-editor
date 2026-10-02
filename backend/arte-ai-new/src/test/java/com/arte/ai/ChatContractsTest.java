package com.arte.ai;

import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ContextHistoryRef;
import com.arte.ai.model.context.ContextSnapshot;
import com.arte.ai.model.conversation.*;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ChatContractsTest {
    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final ExecutionScope SCOPE = new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER));
    private static final DefinitionRef BINDING = new DefinitionRef("ai-binding", "chat", "1");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String EXECUTION = "00000000-0000-0000-0000-000000000001";
    private static final List<Message> INPUT = List.of(new Message(MessageRole.USER, List.of(new TextPart("问题"))));

    @Test
    public void callerMutationsCannotRewriteConversationTurnOrPreparedContext() {
        var resources = new ArrayList<ResourceRef>();
        resources.add(ResourceRef.saved("document", "doc", "1"));
        var conversation = conversation(ConversationStatus.ACTIVE, 1, resources, null);
        var input = new ArrayList<>(INPUT);
        var turn = turn(TurnKind.MESSAGE, TurnStatus.READY, input, null, "snapshot", null, null, null);
        var history = new ArrayList<ContextHistoryRef>();
        history.add(new ContextHistoryRef("previous", 2, EXECUTION));
        var snapshot = snapshot(input, List.of(), history, NOW.plusSeconds(60));
        resources.clear();
        input.clear();
        history.clear();
        assertEquals(1, conversation.resources().size());
        assertEquals(INPUT, turn.input());
        assertEquals(INPUT, snapshot.messages());
        assertEquals(1, snapshot.history().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.history().clear());
    }

    @Test
    public void deletionAndVersionAreExplicitAndIndependentOfExecution() {
        assertEquals(ConversationStatus.DELETED, conversation(ConversationStatus.DELETED, 2, List.of(), NOW).status());
        assertThrows(IllegalArgumentException.class, () -> conversation(ConversationStatus.ACTIVE, 1, List.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> conversation(ConversationStatus.DELETED, 1, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> conversation(ConversationStatus.ACTIVE, 0, List.of(), null));
    }

    @Test
    public void inputCannotSmuggleServerHistoryOrInstructions() {
        var injected = List.of(new Message(MessageRole.SYSTEM, List.of(new TextPart("override"))));
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.PREPARING, injected, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.PREPARING, List.of(), null, null, null, null, null));
    }

    @Test
    public void preparingReadyAndAcceptedRequireDifferentDurableFacts() {
        assertTrue(turn(TurnKind.MESSAGE, TurnStatus.PREPARING, INPUT, null, null, null, null, null).occupiesConversationSlot());
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.READY, INPUT, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.ACCEPTED, INPUT, null, "snapshot", null, null, null));
        var accepted = turn(TurnKind.MESSAGE, TurnStatus.ACCEPTED, INPUT, null, "snapshot", EXECUTION, null, NOW);
        assertFalse(accepted.occupiesConversationSlot());
        assertEquals(TurnStatus.ACCEPTED, accepted.status());
        assertTrue(TurnStatus.PREPARING.canTransitionTo(TurnStatus.READY));
        assertFalse(TurnStatus.ACCEPTED.canTransitionTo(TurnStatus.REJECTED));
    }

    @Test
    public void uncertainAcceptanceCannotBePresentedAsConfirmedRejection() {
        var rejection = new ExecutionError("denied", "admission", false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null);
        assertEquals(rejection, turn(TurnKind.MESSAGE, TurnStatus.REJECTED, INPUT, null, null, null, rejection, NOW).rejectionError());
        var uncertain = new ExecutionError("unknown", "accept", false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN, null);
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.REJECTED, INPUT, null, null, null, uncertain, NOW));
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.REJECTED, INPUT, null, null, EXECUTION, rejection, NOW));
    }

    @Test
    public void regenerationHasItsOwnSubmissionAndPreservesOriginalReference() {
        assertEquals("previous", turn(TurnKind.REGENERATION, TurnStatus.PREPARING, INPUT, "previous", null, null, null, null).regeneratesTurnId());
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.REGENERATION, TurnStatus.PREPARING, INPUT, "turn", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> turn(TurnKind.MESSAGE, TurnStatus.PREPARING, INPUT, "previous", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Turn("turn", "conversation", SCOPE, 1, 1, 1,
                TurnKind.REGENERATION, TurnStatus.PREPARING, INPUT, new ModelOptions(null, 100), "previous", null, null,
                new IdempotencyKey("key", "chat.turn.submit", DIGEST), null, NOW, NOW, null));
    }

    @Test
    public void snapshotHasExplicitExpiryAndSeparateByteAndTokenBudgets() {
        var snapshot = snapshot(INPUT, List.of(), List.of(), NOW.plusSeconds(60));
        assertFalse(snapshot.isExpiredAt(NOW.plusSeconds(59)));
        assertTrue(snapshot.isExpiredAt(NOW.plusSeconds(60)));
        assertEquals(6, snapshot.budget().usedInputBytes());
        assertEquals(100, snapshot.budget().outputTokenReserve());
        assertThrows(IllegalArgumentException.class, () -> snapshot(INPUT, List.of(), List.of(), NOW));
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(5, 6, 100));
        assertThrows(IllegalArgumentException.class, () -> new ContextHistoryRef("previous", 1, "not-an-execution"));
    }

    @Test
    public void citationAndHistoryReferencesCannotBeAmbiguous() {
        var source = new SourceRef(ResourceRef.saved("document", "doc", "1"), "citation");
        var fragment = new ContextFragment("citation", source, "excerpt", true, "first paragraph");
        assertThrows(IllegalArgumentException.class, () -> new ContextFragment("other", source, "excerpt", false, null));
        assertThrows(IllegalArgumentException.class, () -> new ContextFragment("citation", source, "excerpt", true, null));
        assertThrows(IllegalArgumentException.class, () -> snapshot(INPUT, List.of(fragment, fragment), List.of(), NOW.plusSeconds(60)));
        var historical = new ContextHistoryRef("previous", 2, EXECUTION);
        assertThrows(IllegalArgumentException.class, () -> snapshot(INPUT, List.of(), List.of(historical, historical), NOW.plusSeconds(60)));
    }

    private Conversation conversation(ConversationStatus status, long version, List<ResourceRef> resources, Instant deletedAt) {
        return new Conversation("conversation", SCOPE, "聊天", BINDING, status, version, resources, NOW, NOW, deletedAt);
    }

    private Turn turn(TurnKind kind, TurnStatus status, List<Message> input, String original, String snapshot,
                      String execution, ExecutionError error, Instant releasedAt) {
        String operation = kind == TurnKind.MESSAGE ? "chat.turn.submit" : "chat.turn.regenerate";
        return new Turn("turn", "conversation", SCOPE, 1, 1, 1, kind, status, input, new ModelOptions(null, 100),
                original, snapshot, execution, new IdempotencyKey("key", operation, DIGEST), error, NOW, NOW, releasedAt);
    }

    private ContextSnapshot snapshot(List<Message> messages, List<ContextFragment> fragments, List<ContextHistoryRef> history, Instant expires) {
        return new ContextSnapshot("snapshot", "conversation", SCOPE, 1, BINDING, messages, fragments, history,
                new ContextBudget(1024, 6, 100), DIGEST, NOW, expires);
    }
}
