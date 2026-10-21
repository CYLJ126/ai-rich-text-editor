package com.arte.ainew.infrastructure.provider.deepseek;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.SseFrame;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolSetSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.adapter.GenerationProviderAdapter;
import com.arte.core.diagnostics.SafeExceptionDiagnostics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * DeepSeek 单次文本及工具提议映射
 * <p>
 * 每次订阅拥有独立、有界聚合状态，不调用网络、不推进执行状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
@Slf4j
public final class DeepSeekGenerationProviderAdapter implements GenerationProviderAdapter<DeepSeekWire.Request, SseFrame> {

    private final List<CapabilityDescriptor> capabilities;
    private final JsonMapper json;

    public DeepSeekGenerationProviderAdapter(List<CapabilityDescriptor> capabilities, JsonMapper json) {
        this.capabilities = List.copyOf(capabilities);
        this.json = json;
        for (var capability : capabilities) {
            ContractChecks.require(capability.kind() == kind() && Set.of(CapabilityDescriptor.Feature.TEXT_INPUT,
                            CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.USAGE_REPORTING,
                            CapabilityDescriptor.Feature.TOOL_CALLS).containsAll(capability.features()),
                    "Unsupported DeepSeek capability features");
        }
    }

    @Override
    public String providerId() {
        return "deepseek";
    }

    @Override
    public CapabilityDescriptor.Kind kind() {
        return CapabilityDescriptor.Kind.GENERATION;
    }

    @Override
    public List<CapabilityDescriptor> capabilities() {
        return capabilities;
    }

    /**
     * 新发布版本复用已登记 Schema；只判断本地协议契约，授权及固定版本仍由目录校验。
     */
    @Override
    public boolean supports(CapabilityDescriptor capability) {
        return capability.kind() == kind()
                && capability.availability() == CapabilityDescriptor.Availability.EXECUTABLE
                && capability.sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY
                && capability.features().containsAll(Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING))
                && Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING,
                CapabilityDescriptor.Feature.USAGE_REPORTING, CapabilityDescriptor.Feature.TOOL_CALLS).containsAll(capability.features())
                && capabilities.stream().anyMatch(registered -> Objects.equals(registered.inputSchema(), capability.inputSchema())
                && Objects.equals(registered.outputSchema(), capability.outputSchema()));
    }

    @Override
    public boolean supportsToolChat(CapabilityDescriptor capability) {
        return supports(capability) && capability.features().contains(CapabilityDescriptor.Feature.TOOL_CALLS);
    }

    @Override
    public DeepSeekWire.Request mapRequest(GatewayCall<GenerationRequest> call) {
        return mapRequest(call, null);
    }

    @Override
    public DeepSeekWire.Request mapRequest(GatewayCall<GenerationRequest> call, ToolSetSnapshot tools) {
        var input = call.request().input();
        if (!supports(call.binding().capability())
                || !call.binding().capability().features().contains(CapabilityDescriptor.Feature.STREAMING)
                || !(input.outputFormat() instanceof GenerationRequest.TextOutput)) {
            throw GenerationException.beforeSend("UNSUPPORTED_GENERATION_FEATURE");
        }
        var toolProtocol = new DeepSeekToolProtocol(call, tools);
        var messages = toolProtocol.messages(input.messages());
        var options = input.options();
        return new DeepSeekWire.Request(messages, call.binding().remoteOperation(), options.maxOutputTokens(),
                options.temperature(), options.topP(), options.stopSequences().isEmpty() ? null : options.stopSequences(),
                true, new DeepSeekWire.StreamOptions(true), new DeepSeekWire.Thinking("disabled"), toolProtocol.declarations());
    }

    /**
     * 完整 JSON 响应映射，仅用于明确的非流响应；当前 Gateway 的传输固定为 SSE。
     */
    @Override
    public ModelResult mapResult(SseFrame frame, GatewayCall<GenerationRequest> call) {
        return mapResult(frame, call, null);
    }

    public ModelResult mapResult(SseFrame frame, GatewayCall<GenerationRequest> call, ToolSetSnapshot tools) {
        if (!(frame instanceof SseFrame.Data data)) {
            throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
        }
        var state = new State(call, tools);
        state.accept(parse(data), false);
        return state.result(true);
    }

    @Override
    public Flux<GenerationSignal> mapStream(Flux<SseFrame> responses, GatewayCall<GenerationRequest> call) {
        return mapStream(responses, call, null);
    }

    @Override
    public Flux<GenerationSignal> mapStream(Flux<SseFrame> responses, GatewayCall<GenerationRequest> call, ToolSetSnapshot tools) {
        return Flux.defer(() -> {
            var state = new State(call, tools);
            return responses.takeUntil(frame -> frame instanceof SseFrame.Done).concatMap(frame -> {
                        if (frame instanceof SseFrame.Done) {
                            state.done = true;
                            return Flux.just((GenerationSignal) new GenerationSignal.Result(state.result(true)));
                        }
                        return Flux.fromIterable(state.accept(parse((SseFrame.Data) frame), true));
                    }, 1)
                    .concatWith(Flux.defer(() -> state.done ? Flux.empty()
                            : Flux.error(GenerationException.output("PROVIDER_STREAM_INTERRUPTED"))))
                    .onErrorResume(error -> Flux.just(state.failure(error)));
        });
    }

    private DeepSeekWire.Response parse(SseFrame.Data frame) {
        try {
            return json.readValue(frame.json(), DeepSeekWire.Response.class);
        } catch (RuntimeException ignored) {
            throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
        }
    }

    @Override
    public ExecutionError mapError(Throwable failure, GatewayCall<GenerationRequest> call) {
        var error = Exceptions.unwrap(failure);
        if (error instanceof GenerationException known) {
            return known.error(call.request().context().traceId());
        }
        if (error instanceof org.springframework.core.io.buffer.DataBufferLimitException) {
            return GenerationException.output("SSE_FRAME_LIMIT_EXCEEDED").error(call.request().context().traceId());
        }
        if (error instanceof AccessDeniedException) {
            return new ExecutionError("AUTHORIZATION_DENIED", ExecutionError.Phase.AUTHORIZATION, false,
                    ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, call.request().context().traceId());
        }
        String code = error instanceof CancellationException ? "INVOCATION_CANCELLED"
                : error instanceof TimeoutException ? "INVOCATION_TIMED_OUT" : "PROVIDER_INTERACTION_FAILED";
        return new ExecutionError(code, ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE,
                ExecutionError.Certainty.UNKNOWN, call.request().context().traceId());
    }

    private final class State {
        private final GatewayCall<GenerationRequest> call;
        private final StringBuilder text = new StringBuilder();
        private final DeepSeekToolProtocol tools;
        private final TreeMap<Integer, Proposal> proposals = new TreeMap<>();
        private final Set<String> callIds = new HashSet<>();
        private long characters;
        private long bytes;
        private String responseId;
        private String model;
        private ModelResult.FinishReason finish;
        private Usage usage = Usage.unknown();
        private boolean done;

        State(GatewayCall<GenerationRequest> call, ToolSetSnapshot snapshot) {
            this.call = call;
            tools = new DeepSeekToolProtocol(call, snapshot);
            tools.messages(call.request().input().messages());
        }

        List<GenerationSignal> accept(DeepSeekWire.Response response, boolean streaming) {
            if (done || response == null || response.error() != null || response.id() == null || response.model() == null) {
                throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
            }
            try {
                ContractChecks.id(response.id(), "providerResponseId");
                ContractChecks.id(response.model(), "providerModel");
            } catch (IllegalArgumentException ignored) {
                throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
            }
            if (responseId == null) {
                responseId = response.id();
                model = response.model();
            }
            if (!responseId.equals(response.id()) || !model.equals(response.model())) {
                throw GenerationException.output("PROVIDER_RESPONSE_IDENTITY_CHANGED");
            }
            var signals = new ArrayList<GenerationSignal>(3);
            if (response.choices() == null || response.choices().size() > 1) {
                throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
            }
            for (var choice : response.choices()) {
                if (choice.index() == null || choice.index() != 0) {
                    throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
                }
                var delta = streaming ? choice.delta() : choice.message();
                if (delta != null) {
                    if (delta.role() != null && !delta.role().equals("assistant")
                            || delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()) {
                        throw GenerationException.output("UNSUPPORTED_PROVIDER_OUTPUT");
                    }
                    if (delta.content() != null && !delta.content().isEmpty()) {
                        if (finish != null) {
                            throw GenerationException.output("OUTPUT_AFTER_FINISH");
                        }
                        consume(delta.content());
                        text.append(delta.content());
                        signals.add(new GenerationSignal.Delta(new GenerationEvent.TextDelta(delta.content())));
                    }
                    if (delta.toolCalls() != null) {
                        if (delta.toolCalls().size() > ContractChecks.MAX_TOOLS)
                            throw GenerationException.output("TOOL_CALL_LIMIT_EXCEEDED");
                        var indices = new HashSet<Integer>();
                        for (int i = 0; i < delta.toolCalls().size(); i++) {
                            var proposal = delta.toolCalls().get(i);
                            if (proposal == null) throw GenerationException.output("INVALID_TOOL_CALL");
                            int index = streaming && proposal.index() != null ? proposal.index() : streaming ? -1 : i;
                            if (!indices.add(index)) throw GenerationException.output("INVALID_TOOL_CALL_INDEX");
                            signals.add(acceptProposal(index, proposal));
                        }
                    }
                }
                if (choice.finishReason() != null) {
                    var reason = switch (choice.finishReason()) {
                        case "stop" -> ModelResult.FinishReason.STOP;
                        case "length" -> ModelResult.FinishReason.LENGTH;
                        case "content_filter" -> ModelResult.FinishReason.CONTENT_FILTER;
                        case "tool_calls" -> ModelResult.FinishReason.TOOL_CALLS;
                        default -> ModelResult.FinishReason.OTHER;
                    };
                    if (finish != null && finish != reason) {
                        throw GenerationException.output("PROVIDER_FINISH_CHANGED");
                    }
                    if (finish == null) {
                        signals.add(new GenerationSignal.Delta(new GenerationEvent.Finished(reason)));
                    }
                    finish = reason;
                }
            }
            if (response.usage() != null) {
                var tokens = response.usage();
                Usage reported;
                try {
                    reported = tokens.input() == null && tokens.output() == null && tokens.total() == null ? Usage.unknown()
                            : new Usage(Usage.Basis.PROVIDER_REPORTED, tokens.input(), tokens.output(), tokens.total());
                } catch (IllegalArgumentException ignored) {
                    throw GenerationException.output("INVALID_PROVIDER_USAGE");
                }
                if (usage.basis() != Usage.Basis.UNKNOWN && !reported.equals(usage)) {
                    throw GenerationException.output("PROVIDER_USAGE_CHANGED");
                }
                if (!reported.equals(usage)) {
                    usage = reported;
                    signals.add(new GenerationSignal.Delta(new GenerationEvent.UsageReported(usage)));
                }
            }
            return signals;
        }

        private void consume(String fragment) {
            long added = fragment.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + added > call.request().options().maxOutputBytes()
                    || characters + fragment.length() > ContractChecks.MAX_TEXT_CHARS)
                throw GenerationException.output("OUTPUT_LIMIT_EXCEEDED");
            bytes += added;
            characters += fragment.length();
        }

        private GenerationSignal acceptProposal(int index, DeepSeekWire.ToolProposal delta) {
            if (finish != null) throw GenerationException.output("OUTPUT_AFTER_FINISH");
            if (index < 0 || index >= ContractChecks.MAX_TOOLS)
                throw GenerationException.output("INVALID_TOOL_CALL_INDEX");
            if (!proposals.containsKey(index) && proposals.size() >= call.request().options().maxToolSteps())
                throw GenerationException.output("TOOL_CALL_LIMIT_EXCEEDED");
            if (delta.type() != null && !delta.type().equals("function"))
                throw GenerationException.output("INVALID_TOOL_CALL");
            var proposal = proposals.computeIfAbsent(index, ignored -> new Proposal());
            if (delta.id() != null) {
                if (delta.id().isBlank() || delta.id().length() > 256)
                    throw GenerationException.output("INVALID_TOOL_CALL");
                if (proposal.id != null && !proposal.id.equals(delta.id()))
                    throw GenerationException.output("TOOL_CALL_ID_CHANGED");
                if (proposal.id == null) {
                    tools.requireNewId(delta.id());
                    if (!callIds.add(delta.id())) throw GenerationException.output("DUPLICATE_TOOL_CALL_ID");
                    proposal.id = delta.id();
                }
                consume(delta.id());
            }
            if (delta.type() != null) proposal.function = true;
            var function = delta.function();
            String fragment = function == null || function.arguments() == null ? "" : function.arguments();
            String name = function == null ? null : function.name();
            if (name != null) {
                tools.named(name);
                if (proposal.name != null && !proposal.name.equals(name))
                    throw GenerationException.output("TOOL_CALL_NAME_CHANGED");
                proposal.name = name;
                consume(name);
            }
            if (delta.id() == null && name == null && fragment.isEmpty())
                throw GenerationException.output("INVALID_TOOL_CALL");
            consume(fragment);
            proposal.argumentBytes += fragment.getBytes(StandardCharsets.UTF_8).length;
            long limit = proposal.name == null ? ToolExecutionPolicy.MAX_INPUT_BYTES
                    : tools.named(proposal.name).policy().maxInputBytes();
            if (proposal.argumentBytes > limit) throw GenerationException.output("TOOL_ARGUMENT_LIMIT_EXCEEDED");
            proposal.arguments.append(fragment);
            return new GenerationSignal.Delta(new GenerationEvent.ToolCallDelta(index, delta.id(), name, fragment));
        }

        private final class Proposal {
            String id;
            String name;
            boolean function;
            long argumentBytes;
            final StringBuilder arguments = new StringBuilder();
        }

        ModelResult result(boolean terminal) {
            if (terminal && (finish == null || model == null || finish == ModelResult.FinishReason.STOP && text.toString().isBlank())) {
                throw GenerationException.output("INVALID_PROVIDER_COMPLETION");
            }
            var completeCalls = new ArrayList<ToolCall>();
            if (terminal) {
                if (!proposals.isEmpty() && finish != ModelResult.FinishReason.TOOL_CALLS)
                    throw GenerationException.output("INCOMPLETE_TOOL_CALLS");
                if (finish == ModelResult.FinishReason.TOOL_CALLS) {
                    if (proposals.isEmpty()) throw GenerationException.output("INVALID_PROVIDER_COMPLETION");
                    int expected = 0;
                    for (var entry : proposals.entrySet()) {
                        var proposal = entry.getValue();
                        if (entry.getKey() != expected++ || proposal.id == null || proposal.name == null || !proposal.function)
                            throw GenerationException.output("INCOMPLETE_TOOL_CALLS");
                        completeCalls.add(tools.complete(proposal.id, proposal.name, proposal.arguments.toString()));
                    }
                }
            }
            var outputs = text.toString().isBlank() && completeCalls.isEmpty() ? List.<ChatMessage>of() : List.of(new ChatMessage(
                    CanonicalJson.key(call.attempt().attemptId(), "assistant-output"), ChatMessage.Role.ASSISTANT,
                    text.toString().isBlank() ? List.of() : List.of(new ChatMessage.Text(text.toString())), completeCalls, null));
            return new ModelResult(CanonicalJson.key(call.request().context().executionId(), call.attempt().attemptId(), "model-result"),
                    new ModelResult.ModelIdentity(providerId(), model, null), outputs,
                    finish == null || !terminal && finish == ModelResult.FinishReason.TOOL_CALLS ? ModelResult.FinishReason.OTHER : finish,
                    terminal && (finish == ModelResult.FinishReason.STOP || finish == ModelResult.FinishReason.TOOL_CALLS), null, usage, List.of());
        }

        GenerationSignal.Failure failure(Throwable error) {
            var mapped = mapError(error, call);
            var root = Exceptions.unwrap(error);
            log.warn("AI provider interaction failed, invocationId={}, traceId={}, attemptId={}, providerId={}, code={}, phase={}, certainty={}, sideEffect={}, outputBytes={}, usageBasis={}, type={}",
                    call.request().context().executionId(), call.request().context().traceId(), call.attempt().attemptId(), providerId(),
                    mapped.code(), mapped.phase(), mapped.certainty(), mapped.sideEffect(), bytes, usage.basis(), root.getClass().getName());
            if (!(root instanceof GenerationException || root instanceof TimeoutException || root instanceof CancellationException
                    || root instanceof AccessDeniedException || root instanceof org.springframework.core.io.buffer.DataBufferLimitException)) {
                log.error("AI provider unexpected failure, invocationId={}, traceId={}, attemptId={}, providerId={}, code={}, type={}",
                        call.request().context().executionId(), call.request().context().traceId(), call.attempt().attemptId(), providerId(),
                        mapped.code(), root.getClass().getName(), SafeExceptionDiagnostics.forLogging(root));
            }
            return new GenerationSignal.Failure(mapped, usage, model == null || text.toString().isBlank() ? null : result(false));
        }
    }
}
