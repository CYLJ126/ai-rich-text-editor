package com.arte.ainew.contract;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.*;
import org.junit.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * 检查跨契约身份／授权上限和输出事实，避免在未来组装链路中混用不同执行的数据。
 */
public class InvocationApiContractsTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final DefinitionRef CAPABILITY = new DefinitionRef("capability", "generate", "v1");
    private static final DefinitionRef BINDING = new DefinitionRef("binding", "default", "v1");
    private static final DefinitionRef CONNECTION = new DefinitionRef("connection", "default", "v1");

    @Test
    public void runtimeMayRefreshGrantButCannotChangeOwnerOrExpandAcceptedScopes() {
        var request = request();
        var refreshed = context("subject", Set.of("ai:invoke"), "refreshed-grant");
        assertEquals(refreshed, call(request, binding(BINDING), dispatched(), refreshed).runtime().execution());
        assertThrows(IllegalArgumentException.class,
                () -> call(request, binding(BINDING), dispatched(), context("other", Set.of("ai:invoke"), "grant")));
        assertThrows(IllegalArgumentException.class,
                () -> call(request, binding(BINDING), dispatched(), context("subject", Set.of("ai:invoke", "admin"), "grant")));
    }

    @Test
    public void gatewayCannotUseUnmarkedAttemptOrSubstituteBindingVersion() {
        var pristine = new Attempt("attempt", "invocation", 1, "worker", 1, NOW.plusSeconds(30), 0,
                Attempt.State.CREATED, Attempt.Dispatch.NOT_STARTED, null, null, Usage.unknown(), null, NOW, NOW);
        assertThrows(IllegalArgumentException.class,
                () -> call(request(), binding(BINDING), pristine, request().context()));
        assertThrows(IllegalArgumentException.class,
                () -> call(request(), binding(new DefinitionRef("binding", "default", "v2")), dispatched(), request().context()));
    }

    @Test
    public void acceptedGenerationMustUseExactlyTheSnapshotMessagesAndBinding() {
        var request = request();
        var snapshot = snapshot(request.input().messages(), BINDING);
        assertEquals(snapshot, new InvocationSubmission<>(request, snapshot, null, null, null).snapshot());
        var different = new ChatMessage("other", ChatMessage.Role.USER, List.of(new ChatMessage.Text("other")), List.of(), null);
        assertThrows(IllegalArgumentException.class,
                () -> new InvocationSubmission<>(request, snapshot(List.of(different), BINDING), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new InvocationSubmission<>(request,
                snapshot(request.input().messages(), new DefinitionRef("binding", "default", "v2")), null, null, null));
    }

    @Test
    public void failurePreservesUnknownUsageAndRejectsContradictoryPartialOutput() {
        var error = new ExecutionError("INTERRUPTED", ExecutionError.Phase.INVOCATION, false,
                ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace");
        var unknown = Usage.unknown();
        assertEquals(unknown, new GenerationSignal.Failure(error, unknown, null).usage());
        var output = new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("partial")), List.of(), null);
        var model = new ModelResult.ModelIdentity("provider", "model", null);
        var partial = new ModelResult("result", model, List.of(output), ModelResult.FinishReason.LENGTH,
                false, null, unknown, List.of());
        assertEquals(partial, new GenerationSignal.Failure(error, unknown, partial).partialResult());
        var reported = new Usage(Usage.Basis.PROVIDER_REPORTED, 1L, 2L, 3L);
        assertThrows(IllegalArgumentException.class, () -> new GenerationSignal.Failure(error, reported, partial));
        var complete = new ModelResult("result", model, List.of(output), ModelResult.FinishReason.STOP,
                true, null, unknown, List.of());
        assertThrows(IllegalArgumentException.class, () -> new GenerationSignal.Failure(error, unknown, complete));
    }

    @Test
    public void connectionConfigurationKeepsCredentialsInReferences() {
        assertEquals(new DefinitionRef("secret", "credential", "v1"), connection("https://api.example.test/v1").credential());
        assertThrows(IllegalArgumentException.class, () -> connection("https://user:password@api.example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> connection("https://api.example.test/v1?key=secret"));
        assertThrows(IllegalArgumentException.class, () -> connection("file:///tmp/model"));
    }

    @Test
    public void chatHistoryCannotSilentlySelectAnotherConversationOrVersion() {
        var request = request();
        var budget = new ContextBudget(1024, 768, 256, 0);
        var otherHistory = new ContextRequest.HistorySelection("other", 1, List.of(), 8);
        var selection = new ContextRequest(request.input().messages(), otherHistory, List.of(), List.of(), null, budget);
        assertThrows(IllegalArgumentException.class, () -> new EntryRequests.Chat("conversation", 1, null, null,
                selection, CAPABILITY, BINDING, request.input().options(), request.options()));
        var changedVersion = new ContextRequest.HistorySelection("conversation", 2, List.of(), 8);
        var changedSelection = new ContextRequest(request.input().messages(), changedVersion, List.of(), List.of(), null, budget);
        assertThrows(IllegalArgumentException.class, () -> new EntryRequests.Chat("conversation", 1, null, null,
                changedSelection, CAPABILITY, BINDING, request.input().options(), request.options()));
    }

    private static GatewayCall<GenerationRequest> call(InvocationRequest<GenerationRequest> request,
                                                       ResolvedBinding binding, Attempt attempt, ExecutionContext runtime) {
        return new GatewayCall<>(request, binding, attempt, ExecutionRuntimeContext.start(runtime));
    }

    private static ExecutionContext context(String subject, Set<String> scopes, String grant) {
        var authorization = new ExecutionAuthorization(new ExecutionPrincipal(subject, "user", ExecutionPrincipal.Kind.USER),
                "tenant", "workspace", scopes, grant);
        return new ExecutionContext("invocation", "trace", authorization, NOW.plusSeconds(60), null, "budget", "release", "key");
    }

    private static InvocationRequest<GenerationRequest> request() {
        var message = new ChatMessage("message", ChatMessage.Role.USER, List.of(new ChatMessage.Text("hello")), List.of(), null);
        var input = new GenerationRequest(List.of(message), new GenerationOptions(128, null, null, List.of()),
                List.of(), new GenerationRequest.TextOutput());
        return new InvocationRequest<>(CAPABILITY, BINDING, input.kind(), input,
                new ExecutionOptions(NOW.plusSeconds(60), 2, 4096, 0, 0), context("subject", Set.of("ai:invoke"), "grant"));
    }

    private static ResolvedBinding binding(DefinitionRef reference) {
        var descriptor = new CapabilityDescriptor(CAPABILITY, CapabilityDescriptor.Kind.GENERATION,
                new DefinitionRef("schema", "generation-input", "v1"), new DefinitionRef("schema", "model-result", "v1"),
                Set.of(CapabilityDescriptor.Feature.TEXT_INPUT), CapabilityDescriptor.SideEffect.READ_ONLY,
                CapabilityDescriptor.Availability.EXECUTABLE);
        return new ResolvedBinding(reference, descriptor, CONNECTION, "model", 1024L, null);
    }

    private static Attempt dispatched() {
        return new Attempt("attempt", "invocation", 1, "worker", 1, NOW.plusSeconds(30), 2,
                Attempt.State.RUNNING, Attempt.Dispatch.MAY_HAVE_EXECUTED, "remote-request", "reservation",
                Usage.unknown(), null, NOW, NOW);
    }

    private static ContextSnapshot snapshot(List<ChatMessage> messages, DefinitionRef binding) {
        return new ContextSnapshot("snapshot", null, binding, messages, List.of(),
                new ContextBudget(1024, 768, 256, 0), 1, true, "tokenizer-v1",
                List.of(), "a".repeat(64), NOW, NOW.plusSeconds(300));
    }

    private static ConnectionDefinition connection(String endpoint) {
        return new ConnectionDefinition(CONNECTION, "provider", new DefinitionRef("protocol", "http", "v1"),
                URI.create(endpoint), new DefinitionRef("secret", "credential", "v1"), ConnectionDefinition.State.ENABLED,
                Duration.ofSeconds(5), Duration.ofSeconds(60), 4096);
    }
}
