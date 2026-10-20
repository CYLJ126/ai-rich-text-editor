package com.arte.ainew.tool.chat;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.GenerationJson;
import com.arte.ainew.infrastructure.http.SseFrame;
import com.arte.ainew.infrastructure.provider.deepseek.DeepSeekGenerationProviderAdapter;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.pojo.tool.ToolBindingResolution;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.serialization.ToolCatalogDigests;
import org.junit.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 受控供应商帧；验证协议与固定材料，不访问数据库、网络或业务工具。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 15:20 ✾
 */
public class ToolChatProtocolTest {
    private final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");
    private final Fixture tool = fixture("text", "v1");
    private final ToolSetSnapshot snapshot = snapshot();
    private final CapabilityDescriptor capability = new CapabilityDescriptor(ref("capability", "generation", "v1"),
            CapabilityDescriptor.Kind.GENERATION, ref("schema", "input", "v1"), ref("schema", "output", "v1"),
            Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS),
            CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
    private final DeepSeekGenerationProviderAdapter adapter = new DeepSeekGenerationProviderAdapter(List.of(capability), GenerationJson.mapper(65536));

    private ToolSetSnapshot snapshot() {
        var document = object("$schema", text("urn:arte:ai-tool:schema:v1"), "type", text("object"), "properties", object(
                "text", object("type", text("string")), "amount", object("type", text("number")),
                "meta", object("properties", object("text", object("type", text("string"))))));
        var schema = schema(tool.schema().definition(), document);
        var binding = new ToolBindingResolution(ref("binding", "selected", "p1-" + "1".repeat(64)), ref("binding", "selected", "r1"),
                owner, tool.revision(), tool.revision().tool().checksum(), object("text", text("private-config")),
                ToolCatalogDigests.configuration(object("text", text("private-config"))), tool.revision().tool().defaultPolicy(), Set.of(), Map.of());
        return new ToolSetSnapshot(owner, List.of(new ToolSetSnapshot.Entry("tool_0", binding, binding.effectivePolicy(), schema)));
    }

    private GatewayCall<GenerationRequest> call(List<ChatMessage> messages, long outputBytes) {
        var now = Instant.now();
        var context = new ExecutionContext("generation", "trace", new ExecutionAuthorization(new ExecutionPrincipal("subject", "user", ExecutionPrincipal.Kind.USER),
                "tenant", "workspace", Set.of("ai:invoke"), "grant"), now.plusSeconds(30), null, "budget", "release", "key");
        var binding = new ResolvedBinding(ref("binding", "generation", "v1"), capability, ref("connection", "deepseek", "v1"), "deepseek-chat", 8192L, ref("rate", "generation", "v1"));
        var request = new InvocationRequest<>(capability.definition(), binding.definition(), CapabilityDescriptor.Kind.GENERATION,
                new GenerationRequest(messages, new GenerationOptions(128, null, null, List.of()), snapshot.descriptions(), new GenerationRequest.TextOutput()),
                new ExecutionOptions(context.deadline(), 1, outputBytes, 2, 1, Duration.ofSeconds(30)), context);
        var attempt = new Attempt("attempt", context.executionId(), 1, "worker", 1, now.plusSeconds(60), 1,
                Attempt.State.RUNNING, Attempt.Dispatch.MAY_HAVE_EXECUTED, "remote", "reservation", Usage.unknown(), null, now, now);
        return new GatewayCall<>(request, binding, attempt, ExecutionRuntimeContext.start(context));
    }

    private GatewayCall<GenerationRequest> call() {
        return call(List.of(user()), 65536);
    }

    private ChatMessage user() {
        return new ChatMessage("user", ChatMessage.Role.USER, List.of(new ChatMessage.Text("read")), List.of(), null);
    }

    private ChatMessage assistant(String id, String callId) {
        return new ChatMessage(id, ChatMessage.Role.ASSISTANT, List.of(), List.of(new ToolCall(callId, tool.revision().tool().definition(), object("text", text("hello")))), null);
    }

    private ChatMessage reply(String id, String callId) {
        return new ChatMessage(id, ChatMessage.Role.TOOL, List.of(new ChatMessage.Text("result")), List.of(), callId);
    }

    private SseFrame.Data frame(String calls, String finish) {
        return new SseFrame.Data("{\"id\":\"response\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":" + calls
                + "},\"finish_reason\":" + GenerationJson.mapper(65536).writeValueAsString(finish) + "}]}");
    }

    private String proposal(int index, String id, String name, String arguments) {
        var json = GenerationJson.mapper(65536);
        return "{\"index\":" + index + ",\"id\":" + json.writeValueAsString(id) + ",\"type\":\"function\",\"function\":{\"name\":"
                + json.writeValueAsString(name) + ",\"arguments\":" + json.writeValueAsString(arguments) + "}}";
    }

    private SseFrame.Data full(String arguments) {
        return frame("[" + proposal(0, "call-1", "tool_0", arguments) + "]", "tool_calls");
    }

    private List<GenerationSignal> run(SseFrame... frames) {
        return adapter.mapStream(Flux.fromArray(frames), call(), snapshot).collectList().block();
    }

    private ModelResult result(List<GenerationSignal> signals) {
        assertEquals(1, signals.stream().filter(s -> !(s instanceof GenerationSignal.Delta)).count());
        return ((GenerationSignal.Result) signals.getLast()).result();
    }

    private void failure(String code, SseFrame... frames) {
        var signals = run(frames);
        assertEquals(1, signals.stream().filter(s -> !(s instanceof GenerationSignal.Delta)).count());
        assertTrue(signals.getLast() instanceof GenerationSignal.Failure);
        var failed = (GenerationSignal.Failure) signals.getLast();
        assertEquals(code, failed.error().code());
        assertTrue(failed.partialResult() == null || failed.partialResult().outputs().stream().allMatch(m -> m.toolCalls().isEmpty()));
    }

    @Test
    public void onlyDeclaredSchemaIsSentWithExplicitObjectDefaultsAndNoPrivateConfiguration() {
        var wire = adapter.mapRequest(call(), snapshot);
        var encoded = GenerationJson.mapper(65536).writeValueAsString(wire);
        assertEquals("tool_0", wire.tools().getFirst().function().name());
        assertFalse(encoded.contains("private-config"));
        assertFalse(encoded.contains("$schema"));
        var parameters = (Map<?, ?>) wire.tools().getFirst().function().parameters();
        assertEquals(false, parameters.get("additionalProperties"));
        var meta = (Map<?, ?>) ((Map<?, ?>) parameters.get("properties")).get("meta");
        assertEquals(false, meta.get("additionalProperties"));
        assertEquals("disabled", wire.thinking().type());
    }

    @Test
    public void streamingArgumentsBecomeOneExactProposalOnlyAfterDoneAndKeepDecimals() {
        var first = frame("[" + proposal(0, "call-1", "tool_0", "{\"amount\":0.12345678901234567890123456789,") + "]", null);
        var last = frame("[" + proposal(0, null, null, "\"text\":\"你好\"}") + "]", "tool_calls");
        var events = new ArrayList<GenerationSignal>();
        var source = reactor.core.publisher.Sinks.many().unicast().<SseFrame>onBackpressureBuffer();
        var subscription = adapter.mapStream(source.asFlux(), call(), snapshot).subscribe(events::add);
        source.tryEmitNext(first);
        source.tryEmitNext(last);
        assertTrue(events.stream().allMatch(e -> e instanceof GenerationSignal.Delta));
        source.tryEmitNext(new SseFrame.Done());
        var result = result(events);
        assertTrue(result.complete());
        assertEquals(ModelResult.FinishReason.TOOL_CALLS, result.finishReason());
        var proposal = result.outputs().getFirst().toolCalls().getFirst();
        assertEquals(tool.revision().tool().definition(), proposal.tool());
        assertEquals(new BigDecimal("0.12345678901234567890123456789"), ((StructuredValue.NumberValue) proposal.arguments().fields().get("amount")).value());
        subscription.dispose();
    }

    @Test
    public void interleavedIndicesRetainCallIdentityAndOrderedResults() {
        var start = frame("[" + proposal(1, "call-2", "tool_0", "{\"text\":") + "," + proposal(0, "call-1", "tool_0", "{}") + "]", null);
        var end = frame("[" + proposal(1, null, null, "\"second\"}") + "]", "tool_calls");
        assertEquals(List.of("call-1", "call-2"), result(run(start, end, new SseFrame.Done())).outputs().getFirst().toolCalls().stream().map(ToolCall::callId).toList());
    }

    @Test
    public void incompleteStreamAndTruncatedCallsCannotProduceToolCalls() {
        failure("PROVIDER_STREAM_INTERRUPTED", full("{}"));
        failure("INVALID_TOOL_ARGUMENTS", full("{\"text\":"), new SseFrame.Done());
        failure("INCOMPLETE_TOOL_CALLS", frame("[" + proposal(0, "call-1", "tool_0", "{}") + "]", "length"), new SseFrame.Done());
        failure("INVALID_PROVIDER_COMPLETION", frame("[]", "tool_calls"), new SseFrame.Done());
    }

    @Test
    public void invalidJsonAndSchemaViolationsFailClosed() {
        for (String arguments : List.of("[]", "null", "{} {}", "{\"text\":\"a\",\"text\":\"b\"}", "{\"text\":1}", "{\"unknown\":true}", "{\"meta\":{\"unknown\":1}}"))
            failure("INVALID_TOOL_ARGUMENTS", full(arguments), new SseFrame.Done());
    }

    @Test
    public void unknownNamesChangedIdentityAndDuplicateCallsAreRejected() {
        failure("UNKNOWN_MODEL_TOOL", frame("[" + proposal(0, "call-1", "text.count", "{}") + "]", "tool_calls"), new SseFrame.Done());
        failure("DUPLICATE_TOOL_CALL_ID", frame("[" + proposal(0, "same", "tool_0", "{}") + "," + proposal(1, "same", "tool_0", "{}") + "]", "tool_calls"));
        failure("TOOL_CALL_ID_CHANGED", frame("[" + proposal(0, "call-1", "tool_0", "{") + "]", null), frame("[" + proposal(0, "call-2", null, "}") + "]", "tool_calls"));
        failure("INVALID_TOOL_CALL_INDEX", frame("[" + proposal(128, "call-1", "tool_0", "{}") + "]", "tool_calls"));
        failure("INCOMPLETE_TOOL_CALLS", frame("[" + proposal(1, "call-1", "tool_0", "{}") + "]", "tool_calls"), new SseFrame.Done());
        failure("INVALID_TOOL_CALL_INDEX", frame("[" + proposal(0, "call-1", "tool_0", "{}") + "," + proposal(0, "call-2", "tool_0", "{}") + "]", "tool_calls"));
        failure("TOOL_CALL_LIMIT_EXCEEDED", frame("[" + proposal(0, "call-1", "tool_0", "{}") + "," + proposal(1, "call-2", "tool_0", "{}") + "," + proposal(2, "call-3", "tool_0", "{}") + "]", "tool_calls"));
    }

    @Test
    public void aggregateOutputAndPerToolArgumentsAreBounded() {
        var signals = adapter.mapStream(Flux.just(full("{\"text\":\"" + "你".repeat(30) + "\"}"), new SseFrame.Done()), call(List.of(user()), 64), snapshot).collectList().block();
        assertEquals("OUTPUT_LIMIT_EXCEEDED", ((GenerationSignal.Failure) signals.getLast()).error().code());
        failure("TOOL_ARGUMENT_LIMIT_EXCEEDED", full("{\"text\":\"" + "a".repeat(1100) + "\"}"));
        failure("OUTPUT_AFTER_FINISH", full("{}"), frame("[" + proposal(0, null, null, " ") + "]", null));
    }

    @Test
    public void pairedHistoryUsesFixedAliasesAndDoesNotRepeatOldCallIds() {
        var call = call(List.of(user(), assistant("assistant", "old"), reply("reply", "old")), 65536);
        var wire = adapter.mapRequest(call, snapshot);
        assertEquals("tool_0", wire.messages().get(1).toolCalls().getFirst().function().name());
        assertNull(wire.messages().get(1).content());
        assertEquals("old", wire.messages().getLast().toolCallId());
        var signals = adapter.mapStream(Flux.just(frame("[" + proposal(0, "old", "tool_0", "{}") + "]", "tool_calls")), call, snapshot).collectList().block();
        assertEquals("DUPLICATE_TOOL_CALL_ID", ((GenerationSignal.Failure) signals.getLast()).error().code());
    }

    @Test
    public void missingOrRepeatedToolRepliesAndUnresolvedHistoryAreRejectedBeforeSend() {
        for (var messages : List.of(List.of(user(), reply("reply", "missing")), List.of(user(), assistant("assistant", "old")),
                List.of(user(), assistant("assistant", "old"), reply("r1", "old"), reply("r2", "old")))) {
            assertEquals("INVALID_TOOL_MESSAGE_PAIRING", assertThrows(GenerationException.class, () -> adapter.mapRequest(call(messages, 65536), snapshot)).getMessage());
        }
    }

    @Test
    public void fixedSetIsMandatoryAndCannotBeSubstitutedAcrossOwnersOrCapabilities() {
        assertEquals("TOOL_SET_REQUIRED", assertThrows(GenerationException.class, () -> adapter.mapRequest(call())).getMessage());
        var wrongOwner = new ExecutionOwner("tenant", "other", "subject");
        var original = snapshot.entries().getFirst();
        var b = original.binding();
        var changed = new ToolBindingResolution(b.definition(), b.revision(), wrongOwner, b.tool(), b.contractDigest(), b.effectiveConfiguration(), b.configurationDigest(), b.effectivePolicy(), b.networkOrigins(), b.secrets());
        var other = new ToolSetSnapshot(wrongOwner, List.of(new ToolSetSnapshot.Entry("tool_0", changed, original.policy(), original.inputSchema())));
        assertEquals("TOOL_SET_MISMATCH", assertThrows(GenerationException.class, () -> adapter.mapRequest(call(), other)).getMessage());
        var cap = new CapabilityDescriptor(capability.definition(), capability.kind(), capability.inputSchema(), capability.outputSchema(), Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING), capability.sideEffect(), capability.availability());
        var call = call();
        var binding = new ResolvedBinding(call.binding().definition(), cap, call.binding().target(), call.binding().contextWindowTokens(), call.binding().rate());
        var textAdapter = new DeepSeekGenerationProviderAdapter(List.of(cap), GenerationJson.mapper(65536));
        assertEquals("TOOL_SET_MISMATCH", assertThrows(GenerationException.class, () -> textAdapter.mapRequest(new GatewayCall<>(call.request(), binding, call.attempt(), call.runtime()), snapshot)).getMessage());
    }

    @Test
    public void coldSubscriptionsHaveIndependentAggregationAndCancellationHasNoCompleteProposal() {
        var stream = adapter.mapStream(Flux.just(full("{}"), new SseFrame.Done()), call(), snapshot);
        assertEquals(result(stream.collectList().block()), result(stream.collectList().block()));
        var cancelled = stream.take(1).collectList().block();
        assertTrue(cancelled.stream().allMatch(s -> s instanceof GenerationSignal.Delta));
    }

    @Test
    public void partialTextIsPreservedWithoutAnyUsableProposalAndOneBadCallRejectsTheWholeRound() {
        var textFrame = new SseFrame.Data("{\"id\":\"response\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"checking\"}}]}");
        var signals = run(textFrame, full("{}"));
        var partial = ((GenerationSignal.Failure) signals.getLast()).partialResult();
        assertNotNull(partial);
        assertFalse(partial.complete());
        assertTrue(partial.outputs().getFirst().toolCalls().isEmpty());
        assertEquals("checking", ((ChatMessage.Text) partial.outputs().getFirst().content().getFirst()).text());
        failure("INVALID_TOOL_ARGUMENTS", frame("[" + proposal(0, "call-1", "tool_0", "{}") + "," + proposal(1, "call-2", "tool_0", "{\"bad\":true}") + "]", "tool_calls"), new SseFrame.Done());
    }

    @Test
    public void completeNonStreamingResponseHasSameToolSemantics() {
        var frame = full("{}");
        var response = new SseFrame.Data(frame.json().replace("\"delta\"", "\"message\""));
        assertTrue(adapter.mapResult(response, call(), snapshot).complete());
    }
}
