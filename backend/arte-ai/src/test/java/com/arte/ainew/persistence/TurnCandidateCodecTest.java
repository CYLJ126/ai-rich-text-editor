package com.arte.ainew.persistence;

import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.generation.ChatMessage;
import org.junit.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;

import static org.junit.Assert.*;

public class TurnCandidateCodecTest {

    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private final JsonMapper json = JsonMapper.builder().build();

    private Turn turn() {
        var at = Instant.parse("2026-10-20T00:00:00Z");
        return Turn.typed("turn", "conversation", 1, null, null,
                new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("question")), List.of(), null),
                List.of(AnswerCandidate.invocation("same-id"), AnswerCandidate.run("same-id")), AnswerCandidate.run("same-id"), 1, at, at);
    }

    @Test
    public void runCandidateNeverLeaksIntoLegacyProjectionAndRoundTripsV2() {
        var t = turn();
        assertEquals(List.of("same-id"), t.invocationIds());
        assertNull(t.selectedInvocationId());
        assertEquals(AnswerCandidate.run("same-id"), t.effectiveCandidate());
        assertEquals(t, codec.decode(codec.encode(t), Turn.class));
        assertEquals(2, json.readTree(codec.encode(t)).path("schemaVersion").asInt());
    }

    @Test
    public void oldV1TurnsUpgradeExplicitlyToInvocationReferences() {
        var original = turn();
        var old = new Turn(original.turnId(), original.conversationId(), 1, null, null, original.userMessage(), List.of("old"), "old", 0, original.createdAt(), original.updatedAt());
        var root = (ObjectNode) json.readTree(codec.encode(old));
        root.put("schemaVersion", 1);
        var body = (ObjectNode) root.path("body");
        body.remove("candidates");
        body.remove("selectedCandidate");
        assertEquals(old, codec.decode(json.writeValueAsString(root), Turn.class));
        assertEquals(AnswerCandidate.invocation("old"), old.effectiveCandidate());
    }

    @Test
    public void versionShapeProjectionAndSelectionCorruptionAreRejected() {
        String bytes = codec.encode(turn());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), Turn.class));
        for (String field : List.of("candidates", "selectedCandidate")) {
            var root = (ObjectNode) json.readTree(bytes);
            ((ObjectNode) root.path("body")).remove(field);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(json.writeValueAsString(root), Turn.class));
        }
        var root = (ObjectNode) json.readTree(bytes);
        ((ObjectNode) root.path("body")).put("selectedInvocationId", "same-id");
        assertThrows(RuntimeException.class, () -> codec.decode(json.writeValueAsString(root), Turn.class));
        assertThrows(IllegalArgumentException.class, () -> Turn.typed("turn", "conversation", 1, null, null, turn().userMessage(),
                List.of(AnswerCandidate.run("one")), AnswerCandidate.run("other"), 0, turn().createdAt(), turn().updatedAt()));
    }
}
