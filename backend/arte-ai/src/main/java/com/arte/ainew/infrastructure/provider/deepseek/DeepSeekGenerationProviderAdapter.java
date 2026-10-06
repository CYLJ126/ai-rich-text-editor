package com.arte.ainew.infrastructure.provider.deepseek;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.SseFrame;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.adapter.GenerationProviderAdapter;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * DeepSeek 单次文本生成映射
 * <p>
 * 每次订阅拥有独立、有界聚合状态，不调用网络、不推进执行状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class DeepSeekGenerationProviderAdapter implements GenerationProviderAdapter<DeepSeekWire.Request, SseFrame> {

    private final List<CapabilityDescriptor> capabilities;
    private final JsonMapper json;

    public DeepSeekGenerationProviderAdapter(List<CapabilityDescriptor> capabilities, JsonMapper json) {
        this.capabilities = List.copyOf(capabilities);
        this.json = json;
        for (var capability : capabilities) {
            ContractChecks.require(capability.kind() == kind() && Set.of(CapabilityDescriptor.Feature.TEXT_INPUT,
                            CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.USAGE_REPORTING).containsAll(capability.features()),
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

    @Override
    public DeepSeekWire.Request mapRequest(GatewayCall<GenerationRequest> call) {
        var input = call.request().input();
        if (!capabilities.contains(call.binding().capability())
                || !call.binding().capability().features().contains(CapabilityDescriptor.Feature.STREAMING)
                || !(input.outputFormat() instanceof GenerationRequest.TextOutput) || !input.tools().isEmpty()) {
            throw GenerationException.beforeSend("UNSUPPORTED_GENERATION_FEATURE");
        }
        var messages = input.messages().stream().map(message -> {
            if (message.role() == ChatMessage.Role.TOOL || !message.toolCalls().isEmpty()
                    || message.content().stream().anyMatch(part -> !(part instanceof ChatMessage.Text))) {
                throw GenerationException.beforeSend("UNSUPPORTED_GENERATION_FEATURE");
            }
            String text = message.content().stream().map(part -> ((ChatMessage.Text) part).text()).reduce("", String::concat);
            return new DeepSeekWire.Message(message.role().name().toLowerCase(java.util.Locale.ROOT), text);
        }).toList();
        var options = input.options();
        return new DeepSeekWire.Request(messages, call.binding().remoteOperation(), options.maxOutputTokens(),
                options.temperature(), options.topP(), options.stopSequences().isEmpty() ? null : options.stopSequences(),
                true, new DeepSeekWire.StreamOptions(true), new DeepSeekWire.Thinking("disabled"));
    }

    /**
     * 完整 JSON 响应映射，仅用于明确的非流响应；当前 Gateway 的传输固定为 SSE。
     */
    @Override
    public ModelResult mapResult(SseFrame frame, GatewayCall<GenerationRequest> call) {
        if (!(frame instanceof SseFrame.Data data)) {
            throw GenerationException.output("INVALID_PROVIDER_RESPONSE");
        }
        var state = new State(call);
        state.accept(parse(data), false);
        return state.result(true);
    }

    @Override
    public Flux<GenerationSignal> mapStream(Flux<SseFrame> responses, GatewayCall<GenerationRequest> call) {
        return Flux.defer(() -> {
            var state = new State(call);
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
        private long bytes;
        private String responseId;
        private String model;
        private ModelResult.FinishReason finish;
        private Usage usage = Usage.unknown();
        private boolean done;

        State(GatewayCall<GenerationRequest> call) {
            this.call = call;
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
                            || delta.reasoningContent() != null && !delta.reasoningContent().isEmpty()
                            || delta.toolCalls() != null && !delta.toolCalls().isEmpty()) {
                        throw GenerationException.output("UNSUPPORTED_PROVIDER_OUTPUT");
                    }
                    if (delta.content() != null && !delta.content().isEmpty()) {
                        if (finish != null) {
                            throw GenerationException.output("OUTPUT_AFTER_FINISH");
                        }
                        long added = delta.content().getBytes(StandardCharsets.UTF_8).length;
                        if (bytes + added > call.request().options().maxOutputBytes()
                                || text.length() + delta.content().length() > ContractChecks.MAX_TEXT_CHARS) {
                            throw GenerationException.output("OUTPUT_LIMIT_EXCEEDED");
                        }
                        bytes += added;
                        text.append(delta.content());
                        signals.add(new GenerationSignal.Delta(new GenerationEvent.TextDelta(delta.content())));
                    }
                }
                if (choice.finishReason() != null) {
                    var reason = switch (choice.finishReason()) {
                        case "stop" -> ModelResult.FinishReason.STOP;
                        case "length" -> ModelResult.FinishReason.LENGTH;
                        case "content_filter" -> ModelResult.FinishReason.CONTENT_FILTER;
                        case "tool_calls" -> throw GenerationException.output("UNSUPPORTED_PROVIDER_OUTPUT");
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

        ModelResult result(boolean terminal) {
            if (terminal && (finish == null || model == null || finish == ModelResult.FinishReason.STOP && text.toString().isBlank())) {
                throw GenerationException.output("INVALID_PROVIDER_COMPLETION");
            }
            var outputs = text.toString().isBlank() ? List.<ChatMessage>of() : List.of(new ChatMessage(
                    CanonicalJson.key(call.attempt().attemptId(), "assistant-output"), ChatMessage.Role.ASSISTANT,
                    List.of(new ChatMessage.Text(text.toString())), List.of(), null));
            return new ModelResult(CanonicalJson.key(call.request().context().executionId(), call.attempt().attemptId(), "model-result"),
                    new ModelResult.ModelIdentity(providerId(), model, null), outputs,
                    finish == null ? ModelResult.FinishReason.OTHER : finish,
                    terminal && finish == ModelResult.FinishReason.STOP, null, usage, List.of());
        }

        GenerationSignal.Failure failure(Throwable error) {
            return new GenerationSignal.Failure(mapError(error, call), usage, model == null || text.toString().isBlank() ? null : result(false));
        }
    }
}
