package com.arte.ainew.contract;

import com.arte.ainew.common.execution.*;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.embedding.EmbeddingRequest;
import com.arte.ainew.pojo.embedding.EmbeddingResult;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.pojo.media.MediaResult;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationResult;
import org.junit.Test;

import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;

/** 验证跨对象身份、恢复事实、深度不可变及有界结构；不启动旧 AI、供应商或数据库。 */
public class DataContractsTest {
    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    @Test
    public void requestCopiesNestedInputAndCannotChangeAfterAcceptance() {
        var parts = new ArrayList<ChatMessage.ContentPart>(List.of(new ChatMessage.Text("hello")));
        var message = new ChatMessage("message", ChatMessage.Role.USER, parts, List.of(), null);
        var messages = new ArrayList<>(List.of(message));
        var request = generation(messages);
        parts.clear();
        messages.clear();
        assertEquals("hello", ((ChatMessage.Text) request.messages().getFirst().content().getFirst()).text());
        assertThrows(UnsupportedOperationException.class, () -> request.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> message.content().clear());

        var arguments = new HashMap<String, StructuredValue>();
        arguments.put("query", new StructuredValue.StringValue("fixed"));
        var object = new StructuredValue.ObjectValue(arguments);
        arguments.put("query", new StructuredValue.StringValue("changed"));
        assertEquals(new StructuredValue.StringValue("fixed"), object.fields().get("query"));
        assertThrows(UnsupportedOperationException.class, () -> object.fields().clear());
    }

    @Test
    public void structuredInputCannotExhaustMemoryThroughUnboundedTrees() {
        StructuredValue nested = new StructuredValue.StringValue("x");
        for (int i = 1; i < StructuredValue.MAX_DEPTH; i++) {
            nested = new StructuredValue.ArrayValue(List.of(nested));
        }
        var deepest = nested;
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(List.of(deepest)));
        var large = new StructuredValue.StringValue("x".repeat(600_000));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(List.of(large, large)));
        var leaf = new StructuredValue.ArrayValue(Collections.nCopies(100, StructuredValue.NullValue.INSTANCE));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ArrayValue(Collections.nCopies(100, leaf)));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.ObjectValue(Map.of(
                "k".repeat(256), new StructuredValue.StringValue("x".repeat(999_900)))));
    }

    @Test
    public void capacityIncludesToolArgumentsAndScalarNumericPrecision() {
        var arguments = new StructuredValue.ObjectValue(Map.of("value", new StructuredValue.StringValue("x".repeat(600_000))));
        var calls = List.of(new ToolCall("one", new DefinitionRef("tool", "tool", "v1"), arguments),
                new ToolCall("two", new DefinitionRef("tool", "tool", "v1"), arguments));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage("assistant", ChatMessage.Role.ASSISTANT,
                List.of(), calls, null));
        assertThrows(IllegalArgumentException.class, () -> new GenerationOptions(128, null,
                new BigDecimal("1E-100"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StructuredValue.NumberValue(new BigDecimal("10E+128")));
    }

    @Test
    public void invocationChecksTypeIdempotencyAndAbsoluteDeadline() {
        var generation = generation(List.of(user("message", "hello")));
        var request = invocation("invocation", generation);
        assertEquals(CapabilityDescriptor.Kind.GENERATION, request.kind());
        assertThrows(IllegalArgumentException.class, () -> new InvocationRequest<>(capability(), binding(),
                CapabilityDescriptor.Kind.TOOL, generation, options(), context("invocation", "key")));
        assertThrows(IllegalArgumentException.class, () -> new InvocationRequest<>(capability(), binding(),
                generation.kind(), generation, options(), context("invocation", null)));
        var extended = new ExecutionOptions(NOW.plusSeconds(61), 2, 4096, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new InvocationRequest<>(capability(), binding(),
                generation.kind(), generation, extended, context("invocation", "key")));
        assertThrows(IllegalArgumentException.class, () -> new DefinitionRef("binding", "binding", "latest"));
    }

    @Test
    public void regenerationDoesNotReopenSuccessfulExecutionOrRewriteUserInput() {
        var result = new ResultRef("result", "model-result", 1, DIGEST, false);
        var original = new Invocation(invocation("original", generation(List.of(user("message", "hello")))),
                DIGEST, new Invocation.ConversationLink("conversation", 3, "turn"), "snapshot", null,
                Invocation.State.SUCCEEDED, 2, "attempt-1", result, null, NOW, NOW.plusSeconds(1));
        assertFalse(original.state().canTransitionTo(Invocation.State.RUNNING));
        assertFalse(Invocation.State.UNKNOWN.canTransitionTo(Invocation.State.QUEUED));
        assertTrue(Invocation.State.RUNNING.canTransitionTo(Invocation.State.SUCCEEDED));
        assertTrue(Invocation.State.UNKNOWN.canTransitionTo(Invocation.State.SUCCEEDED));
        var replacement = new Invocation(invocation("replacement", generation(List.of(user("message", "hello")))),
                DIGEST, original.conversation(), "snapshot", "original", Invocation.State.ACCEPTED,
                0, null, null, null, NOW, NOW);
        assertNotEquals(original.request().context().executionId(), replacement.request().context().executionId());
        var turn = new Turn("turn", "conversation", 1, null, null, user("message", "hello"),
                List.of("original", "replacement"), "original", 1, NOW, NOW);
        assertEquals("original", turn.selectedInvocationId());
        assertThrows(IllegalArgumentException.class, () -> new Turn("turn", "conversation", 1, null,
                null, turn.userMessage(), turn.invocationIds(), "unrelated", 1, NOW, NOW));
    }

    @Test
    public void unknownAttemptKeepsDispatchFactsAndCannotClaimKnownFailure() {
        var error = unknownError();
        var attempt = new Attempt("attempt", "invocation", 1, "worker", 7, NOW.plusSeconds(5), 1,
                Attempt.State.UNKNOWN, Attempt.Dispatch.MAY_HAVE_EXECUTED, "remote-request", "reservation",
                Usage.unknown(), error, NOW, NOW.plusSeconds(10));
        assertTrue(attempt.leaseExpiresAt().isBefore(attempt.updatedAt()));
        assertThrows(IllegalArgumentException.class, () -> new Attempt("attempt", "invocation", 1,
                "worker", 7, NOW.plusSeconds(5), 1, Attempt.State.UNKNOWN, Attempt.Dispatch.NOT_STARTED,
                null, null, Usage.unknown(), error, NOW, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPayload.Terminal(
                Invocation.State.SUCCEEDED, new ResultRef("partial", "model-result", 1, DIGEST, true), null));
    }

    @Test
    public void unknownChargeIsNotSilentlyReleasedAfterCancel() {
        assertNull(Usage.unknown().totalTokens());
        assertEquals(Long.valueOf(0), new Usage(Usage.Basis.PROVIDER_REPORTED, 0L, 0L, 0L).totalTokens());
        assertThrows(IllegalArgumentException.class, () -> new Usage(Usage.Basis.UNKNOWN, 0L, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Usage(Usage.Basis.PROVIDER_REPORTED,
                Long.MAX_VALUE, 1L, 0L));
        var pending = new BudgetSettlement("settlement", "reservation", BudgetSettlement.State.PENDING_RECONCILIATION,
                Usage.unknown(), null, NOW);
        assertNull(pending.charge());
        for (var state : List.of(BudgetSettlement.State.SETTLED, BudgetSettlement.State.RELEASED)) {
            assertThrows(IllegalArgumentException.class, () -> new BudgetSettlement("settlement", "reservation",
                    state, Usage.unknown(), null, NOW));
        }
        var money = new Money(new BigDecimal("0.0010"), Currency.getInstance("CNY"));
        assertEquals(new Money(new BigDecimal("0.001"), Currency.getInstance("CNY")), money);
        assertThrows(IllegalArgumentException.class, () -> new BudgetSettlement("settlement", "reservation",
                BudgetSettlement.State.RELEASED, Usage.unknown(), money, NOW));
        var cancel = new ControlReceipt("command", "invocation", ControlReceipt.Command.CANCEL,
                ControlReceipt.Outcome.ACCEPTED, NOW);
        assertEquals(ControlReceipt.Outcome.ACCEPTED, cancel.outcome());
    }

    @Test
    public void uncertainExecutionCannotBeHiddenAsOrdinaryFailure() {
        assertThrows(IllegalArgumentException.class, () -> new Attempt("attempt", "invocation", 1,
                "worker", 7, NOW.plusSeconds(5), 1, Attempt.State.FAILED, Attempt.Dispatch.MAY_HAVE_EXECUTED,
                null, null, Usage.unknown(), unknownError(), NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPayload.Terminal(
                Invocation.State.FAILED, null, unknownError()));
        assertThrows(IllegalArgumentException.class, () -> new com.arte.ainew.pojo.tool.ToolResult("call",
                com.arte.ainew.pojo.tool.ToolResult.Outcome.FAILED, null, List.of(), List.of(),
                ExecutionError.SideEffect.POSSIBLE, unknownError()));
    }

    @Test
    public void snapshotFixesActualHistoryCapacityAndDraftIdentity() {
        var budget = new ContextBudget(1000, 700, 200, 100);
        assertThrows(IllegalArgumentException.class, () -> new ContextBudget(1000, 800, 200, 100));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("resource", "id", "v1",
                "draft", "range", null));
        var source = new SourceRef("citation", new ResourceRef("resource", "id", "v1", null, "range", null), DIGEST);
        var fragment = new ContextSnapshot.Fragment(ContextSnapshot.Origin.USER_SELECTED, source, "selected", 100);
        assertTrue(fragment.truncated());
        var history = new ContextRequest.HistorySelection("conversation", 3, List.of("turn-1"), 2);
        var snapshot = new ContextSnapshot("snapshot", history, binding(), List.of(user("message", "hello")),
                List.of(fragment), budget, 100, true, "tokenizer-v1", List.of(
                new ContextSnapshot.Truncation("citation", ContextSnapshot.TruncationReason.CAPACITY, 92)),
                DIGEST, NOW, NOW.plusSeconds(300));
        assertEquals(List.of("turn-1"), snapshot.history().turnIds());
        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot("snapshot", history, binding(),
                snapshot.messages(), List.of(fragment), budget, 100, true, "tokenizer-v1", List.of(),
                DIGEST, NOW, NOW.plusSeconds(300)));
        var ambiguous = new ContextRequest.HistorySelection("conversation", 3, List.of(), 2);
        assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot("snapshot", ambiguous, binding(),
                snapshot.messages(), List.of(), budget, 100, true, "tokenizer-v1", List.of(),
                DIGEST, NOW, NOW.plusSeconds(300)));
    }

    @Test
    public void executableCapabilityAndToolProposalsNeedExplicitContracts() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDescriptor(capability(),
                CapabilityDescriptor.Kind.GENERATION, null, null, Set.of(),
                CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE));
        var toolCall = new ToolCall("call", new DefinitionRef("tool", "search", "v1"),
                new StructuredValue.ObjectValue(Map.of()));
        var assistant = new ChatMessage("assistant", ChatMessage.Role.ASSISTANT, List.of(), List.of(toolCall), null);
        var result = new ModelResult("result", new ModelResult.ModelIdentity("provider", "model", null),
                List.of(assistant), ModelResult.FinishReason.TOOL_CALLS, true, null, Usage.unknown(), List.of());
        assertEquals("call", result.outputs().getFirst().toolCalls().getFirst().callId());
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage("user", ChatMessage.Role.USER,
                List.of(new ChatMessage.Text("hello")), List.of(toolCall), null));
        assertThrows(IllegalArgumentException.class, () -> new ModelResult("result", result.model(), result.outputs(),
                ModelResult.FinishReason.LENGTH, true, null, Usage.unknown(), List.of()));
    }

    @Test
    public void embeddingResultCannotMixDimensionsOrMismatchInputOrder() {
        var request = new EmbeddingRequest(List.of(new EmbeddingRequest.Input("one", "first"),
                new EmbeddingRequest.Input("two", "second")));
        var vector = new ArrayList<>(List.of(0.1F, 0.2F));
        var one = new EmbeddingResult.Vector("one", vector);
        vector.clear();
        var two = new EmbeddingResult.Vector("two", List.of(0.3F, 0.4F));
        var result = new EmbeddingResult("space-v1", new DefinitionRef("model", "embedding", "v1"),
                2, List.of(one, two), Usage.unknown());
        result.validateAgainst(request);
        assertEquals(2, one.values().size());
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingResult("space", result.model(),
                3, result.vectors(), Usage.unknown()));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddingResult.Vector("one", List.of(Float.NaN)));
        var reordered = new EmbeddingResult("space-v1", result.model(), 2, List.of(two, one), Usage.unknown());
        assertThrows(IllegalArgumentException.class, () -> reordered.validateAgainst(request));
    }

    @Test
    public void remoteLifecycleCannotMixOwnersOrPretendFinishedTaskIsPending() {
        var connection = new DefinitionRef("connection", "connection", "v1");
        var owner = new ExecutionOwner("tenant", "workspace", "subject");
        var task = new RemoteTaskRef("task", connection, owner, RemoteTaskRef.State.RUNNING,
                Set.of(RemoteTaskRef.Control.QUERY));
        var otherSession = new RemoteApplicationRequest.SessionRef("session", connection,
                new ExecutionOwner("tenant", "workspace", "other-subject"));
        assertThrows(IllegalArgumentException.class, () -> new RemoteApplicationResult.Pending(task, otherSession));
        var finished = new RemoteTaskRef("task", connection, owner, RemoteTaskRef.State.SUCCEEDED, Set.of());
        assertThrows(IllegalArgumentException.class, () -> new MediaResult.Pending(finished));
        assertThrows(IllegalArgumentException.class, () -> new RemoteApplicationRequest(
                RemoteApplicationRequest.Operation.CONTINUE, new StructuredValue.ObjectValue(Map.of()), null));
        var foreign = new RemoteApplicationRequest(RemoteApplicationRequest.Operation.CONTINUE,
                new StructuredValue.ObjectValue(Map.of()), otherSession);
        assertThrows(IllegalArgumentException.class, () -> new InvocationRequest<>(capability(), binding(),
                foreign.kind(), foreign, options(), context("invocation", "key")));
    }

    @Test
    public void eventReplayIdentitySurvivesAttemptChangeAndSerialization() throws Exception {
        var batch = new ExecutionPayload.OutputBatch(List.of(new GenerationEvent.TextDelta("persisted")));
        var first = new ExecutionEvent<>(1, "invocation", "attempt-1", 1, ExecutionEvent.Kind.OUTPUT,
                NOW, "ai.output-batch", 1, batch);
        var second = new ExecutionEvent<>(1, "invocation", "attempt-2", 2, ExecutionEvent.Kind.OUTPUT,
                NOW, "ai.output-batch", 1, batch);
        assertEquals(first, roundTrip(first));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent<>(1, "invocation", "attempt-1",
                1, ExecutionEvent.Kind.TERMINAL, NOW, "ai.output-batch", 1, batch));
        assertTrue(first.sequence() < second.sequence());
        assertEquals(new ExecutionEvent.Cursor("invocation", 1), roundTrip(new ExecutionEvent.Cursor("invocation", 1)));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEvent.Cursor("invocation", -1));
        var invocation = new Invocation(invocation("invocation", generation(List.of(user("message", "hello")))),
                DIGEST, null, "snapshot", null, Invocation.State.ACCEPTED, 0, null, null, null, NOW, NOW);
        assertEquals(invocation, roundTrip(invocation));
        var structured = new StructuredValue.ObjectValue(Map.of("number", new StructuredValue.NumberValue(new BigDecimal("10E+127")),
                "empty", StructuredValue.NullValue.INSTANCE, "items", new StructuredValue.ArrayValue(List.of(new StructuredValue.BooleanValue(true)))));
        assertEquals(structured, roundTrip(structured));
        var amount = new Money(new BigDecimal("100.0000000000000000000000"), Currency.getInstance("CNY"));
        assertEquals(amount, roundTrip(amount));
    }

    private static ChatMessage user(String id, String text) {
        return new ChatMessage(id, ChatMessage.Role.USER, List.of(new ChatMessage.Text(text)), List.of(), null);
    }
    private static GenerationRequest generation(List<ChatMessage> messages) {
        return new GenerationRequest(messages, new GenerationOptions(128, null, null, List.of()),
                List.of(), new GenerationRequest.TextOutput());
    }
    private static ExecutionContext context(String id, String key) {
        var authorization = new ExecutionAuthorization(new ExecutionPrincipal("subject", "alice", ExecutionPrincipal.Kind.USER),
                "tenant", "workspace", Set.of("ai:invoke"), "grant");
        return new ExecutionContext(id, "trace", authorization, NOW.plusSeconds(60), null, "budget", "release", key);
    }
    private static DefinitionRef capability() { return new DefinitionRef("capability", "generate", "v1"); }
    private static DefinitionRef binding() { return new DefinitionRef("binding", "default", "v1"); }
    private static ExecutionOptions options() { return new ExecutionOptions(NOW.plusSeconds(60), 2, 4096, 0, 0); }
    private static InvocationRequest<GenerationRequest> invocation(String id, GenerationRequest input) {
        return new InvocationRequest<>(capability(), binding(), input.kind(), input, options(), context(id, "key-" + id));
    }
    private static ExecutionError unknownError() {
        return new ExecutionError("RESULT_UNKNOWN", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace");
    }
    private static Object roundTrip(Serializable value) throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(buffer)) { output.writeObject(value); }
        // 仅对本测试创建的可信对象验证数据可序列化；生产采用显式 Schema 编码器。
        try (var input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            return input.readObject();
        }
    }
}
