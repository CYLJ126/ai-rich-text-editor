package com.arte.ainew.application.execution;

import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.InvocationTiming;
import com.arte.ainew.application.support.TextInputs;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.LiveTextDelta;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.persistence.*;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * 一次 Attempt 的耐久生成处理。只重试数据库 CAS，不重试模型交互。
 * 长交互续租与输出提交均读取新 Guard；失去归属即停止，过期执行由数据库原子停止。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 16:14 ✾
 */
public final class GenerationDispatcher {

    private final AdmissionAuthorization admissionAuthorization;
    private final FixedControlCatalog fixedControlCatalog;
    private final ExecutionStore executionStore;
    private final ExecutionEventStore executionEventStore;
    private final ExecutionOutboxStore executionOutboxStore;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ExecutionResultStore executionResultStore;
    private final BudgetService budgetService;
    private final ModelGateway modelGateway;
    private final NewAiProperties newAiProperties;
    private final NewAiExecutionProperties newAiExecutionProperties;
    private final Clock clock;
    private final LiveTextNotifier liveTextNotifier;

    public GenerationDispatcher(AdmissionAuthorization admissionAuthorization, FixedControlCatalog fixedControlCatalog, ExecutionStore executionStore,
                                ExecutionEventStore executionEventStore, ExecutionOutboxStore executionOutboxStore, ContextSnapshotStore contextSnapshotStore,
                                ExecutionResultStore executionResultStore, BudgetService budgetService, ModelGateway modelGateway,
                                NewAiProperties newAiProperties, NewAiExecutionProperties newAiExecutionProperties, Clock clock) {
        this(admissionAuthorization, fixedControlCatalog, executionStore, executionEventStore, executionOutboxStore,
                contextSnapshotStore, executionResultStore, budgetService, modelGateway, newAiProperties, newAiExecutionProperties, clock, null);
    }

    public GenerationDispatcher(AdmissionAuthorization admissionAuthorization, FixedControlCatalog fixedControlCatalog, ExecutionStore executionStore,
                                ExecutionEventStore executionEventStore, ExecutionOutboxStore executionOutboxStore, ContextSnapshotStore contextSnapshotStore,
                                ExecutionResultStore executionResultStore, BudgetService budgetService, ModelGateway modelGateway,
                                NewAiProperties newAiProperties, NewAiExecutionProperties newAiExecutionProperties, Clock clock, LiveTextNotifier liveTextNotifier) {
        this.liveTextNotifier = liveTextNotifier;
        this.admissionAuthorization = admissionAuthorization;
        this.fixedControlCatalog = fixedControlCatalog;
        this.executionStore = executionStore;
        this.executionEventStore = executionEventStore;
        this.executionOutboxStore = executionOutboxStore;
        this.contextSnapshotStore = contextSnapshotStore;
        this.executionResultStore = executionResultStore;
        this.budgetService = budgetService;
        this.modelGateway = modelGateway;
        this.newAiProperties = newAiProperties;
        this.newAiExecutionProperties = newAiExecutionProperties;
        this.clock = clock;
        if (newAiExecutionProperties.reservationRetention().compareTo(newAiProperties.limits().maximumTimeout()) < 0) {
            throw new IllegalArgumentException("Reservation retention must cover maximum execution timeout");
        }
    }

    public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
        return Mono.defer(() -> {
            if (message.kind() != OutboxMessage.Kind.DISPATCH
                    || !message.invocationId().equals(runtime.execution().executionId())
                    || !message.owner().equals(ExecutionOwner.from(runtime.execution()))) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_OWNER_MISMATCH));
            }
            var timing = InvocationTiming.start(runtime.execution(), null);
            timing.mark("DISPATCH_START");
            return executionOutboxStore.validateClaim(message).flatMap(GenerationDispatcher::applied)
                    .then(load(message.owner(), message.invocationId()))
                    .flatMap(invocation -> {
                        timing.mark("DISPATCH_LOADED");
                        if (!invocation.request().context().authorization().scopes().containsAll(runtime.execution().authorization().scopes())
                                || !Objects.equals(invocation.request().context().budgetRef(), runtime.execution().budgetRef())
                                || !Objects.equals(invocation.request().context().releaseRef(), runtime.execution().releaseRef())
                                || runtime.execution().deadline().isAfter(invocation.request().options().deadline())) {
                            return Mono.error(new AdmissionException(ResultCodeEnum.AI_OWNER_MISMATCH));
                        }
                        if (invocation.state().terminal()) {
                            return settle(invocation);
                        }
                        if (invocation.activeAttemptId() != null) {
                            // 活跃租约不可接管；过期且可能发送的执行只收敛 UNKNOWN，绝不重发。
                            return executionStore.stopExpired(version(invocation)).flatMap(GenerationDispatcher::applied).flatMap(this::settle);
                        }
                        return start(invocation, message, runtime, timing);
                    }).doFinally(signal -> timing.mark("DISPATCH_END_" + signal.name()));
        });
    }

    private Mono<Void> start(Invocation invocation, OutboxMessage message, ExecutionRuntimeContext runtime, InvocationTiming timing) {
        var persisted = invocation.request();
        if (!(persisted.input() instanceof GenerationRequest generation)) {
            return rejectBeforeAttempt(invocation, new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY));
        }
        return admissionAuthorization.require(runtime.execution(), AdmissionAuthorization.INVOKE)
                .flatMap(current -> {
                    var request = new InvocationRequest<>(persisted.capability(), persisted.binding(), persisted.kind(),
                            generation, persisted.options(), current);
                    return fixedControlCatalog.validate(request).then(fixedControlCatalog.resolve(request.binding(), request.capability(), current))
                            .flatMap(binding -> contextSnapshotStore.find(message.owner(), invocation.contextSnapshotId())
                                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTEXT_NOT_FOUND)))
                                    .map(snapshot -> {
                                        TextInputs.verify(snapshot, binding, newAiProperties.limits());
                                        if (!snapshot.messages().equals(generation.messages()) || !snapshot.expiresAt().isAfter(clock.instant())) {
                                            throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_EXPIRED_OR_INVALID);
                                        }
                                        return new Prepared(request, binding, snapshot,
                                                new ExecutionRuntimeContext(current, runtime.cancellation()));
                                    }));
                })
                .onErrorResume(error -> predictable(error)
                        ? rejectBeforeAttempt(invocation, error).then(Mono.empty()) : Mono.error(error))
                .doOnNext(prepared -> timing.mark("PREFLIGHT_READY"))
                .flatMap(prepared -> executionOutboxStore.validateClaim(message).flatMap(GenerationDispatcher::applied)
                        .then(executionStore.createAttempt(new ExecutionCommands.CreateAttempt(version(invocation),
                                CanonicalJson.key(message.invocationId(), "attempt-1"), message.workerId(), newAiExecutionProperties.attemptLease())))
                        .flatMap(GenerationDispatcher::applied)
                        .flatMap(attempt -> new Session(invocation, message, prepared, attempt, timing.withAttempt(attempt.attemptId())).run()));
    }

    private record Prepared(InvocationRequest<GenerationRequest> request, ResolvedBinding binding,
                            ContextSnapshot snapshot, ExecutionRuntimeContext runtime) {
    }

    private Mono<Void> rejectBeforeAttempt(Invocation invocation, Throwable error) {
        var state = error instanceof AdmissionException rejected && rejected.getResultCode() == ResultCodeEnum.AI_DEADLINE_EXCEEDED
                ? Invocation.State.TIMED_OUT : Invocation.State.FAILED;
        var code = error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "AUTHORIZATION_DENIED";
        var fact = new ExecutionError(code, error instanceof AccessDeniedException ? ExecutionError.Phase.AUTHORIZATION
                : ExecutionError.Phase.VALIDATION, false, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN,
                invocation.request().context().traceId());
        return executionStore.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(version(invocation), "dispatch-preflight",
                new ExecutionPayload.Terminal(state, null, fact))).flatMap(GenerationDispatcher::applied).then();
    }

    private static boolean predictable(Throwable error) {
        return error instanceof AdmissionException || error instanceof AccessDeniedException || error instanceof IllegalArgumentException;
    }

    private Mono<Invocation> load(ExecutionOwner owner, String id) {
        return executionStore.find(owner, id).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    private static ExecutionCommands.Version version(Invocation invocation) {
        return new ExecutionCommands.Version(ExecutionOwner.from(invocation.request().context()),
                invocation.request().context().executionId(), invocation.version());
    }

    private static <T> Mono<T> applied(StoreOutcome<T> value) {
        return value.successful() ? Mono.just(value.value()) : Mono.error(AdmissionException.fromStoreRejection(value.code()));
    }

    private NewAiProperties.Rate rate(com.arte.ainew.common.reference.DefinitionRef reference) {
        return newAiProperties.rates().stream().filter(value -> value.definition().equals(reference)).findFirst()
                .orElseThrow(() -> new AdmissionException(ResultCodeEnum.AI_RATE_MISMATCH));
    }

    /**
     * 终态后结算失败可通过同一个 Outbox 重投恢复；时间与防重键取自耐久事实，不随重投变化。
     */
    private Mono<Void> settle(Invocation invocation) {
        if (invocation.activeAttemptId() == null) {
            return Mono.empty();
        }
        var owner = ExecutionOwner.from(invocation.request().context());
        return executionStore.findAttempt(owner, invocation.request().context().executionId(), invocation.activeAttemptId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(attempt -> attempt.budgetReservationId() == null ? Mono.empty()
                        : budgetService.reservation(owner, attempt.budgetReservationId())
                        .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                        .flatMap(reservation -> {
                            if (reservation.state() != BudgetReservation.State.RESERVED) {
                                return Mono.empty();
                            }
                            boolean notSent = attempt.dispatch() == Attempt.Dispatch.NOT_STARTED;
                            boolean reported = (invocation.state() == Invocation.State.SUCCEEDED
                                    || invocation.error() != null && invocation.error().code().equals("MODEL_OUTPUT_INCOMPLETE"))
                                    && GenerationPricing.known(attempt.usage());
                            var price = rate(reservation.rateVersion());
                            var charge = notSent ? GenerationPricing.amount(price, 0, 0)
                                    : reported ? GenerationPricing.amount(price, attempt.usage().inputTokens(), attempt.usage().outputTokens()) : null;
                            var state = notSent ? BudgetSettlement.State.RELEASED
                                    : reported ? BudgetSettlement.State.SETTLED : BudgetSettlement.State.PENDING_RECONCILIATION;
                            var settlement = new BudgetSettlement(CanonicalJson.key(attempt.attemptId(), "dispatch-settlement"),
                                    reservation.reservationId(), state, attempt.usage(), charge, invocation.updatedAt());
                            var evidence = notSent ? BudgetCommands.Evidence.PROVEN_NOT_DISPATCHED
                                    : reported ? BudgetCommands.Evidence.PROVIDER_BILL : BudgetCommands.Evidence.UNKNOWN_COST;
                            return budgetService.settle(new BudgetCommands.Settle(owner, reservation.version(), settlement, evidence,
                                            notSent ? "attempt:" + attempt.attemptId()
                                                    : reported ? "provider-usage:" + attempt.attemptId() : null))
                                    .flatMap(GenerationDispatcher::applied).then();
                        }));
    }

    private final class Session {
        final Invocation initial;
        final OutboxMessage message;
        final Prepared prepared;
        final Attempt claimed;
        final InvocationTiming timing;
        final AtomicBoolean firstTextReceived = new AtomicBoolean();
        final AtomicBoolean firstTextCommitted = new AtomicBoolean();
        final AtomicBoolean completed = new AtomicBoolean();
        final StringBuilder text = new StringBuilder();
        Usage usage = Usage.unknown();
        GenerationSignal terminal;
        long batchNumber;
        boolean hasTextDelta;
        int liveTextOffset;
        boolean liveTextEnded;

        Session(Invocation initial, OutboxMessage message, Prepared prepared, Attempt claimed, InvocationTiming timing) {
            this.timing = timing;
            timing.mark("ATTEMPT_CREATED");
            this.initial = initial;
            this.message = message;
            this.prepared = prepared;
            this.claimed = claimed;
        }

        Mono<Void> run() {
            Mono<Void> work = reserveAndSend().onErrorResume(AdmissionException.class, error -> {
                if (error.getResultCode() != ResultCodeEnum.AI_INSUFFICIENT_BUDGET) {
                    return Mono.error(error);
                }
                return finish(new GenerationSignal.Failure(new ExecutionError("INSUFFICIENT_BUDGET",
                        ExecutionError.Phase.ADMISSION, false, ExecutionError.SideEffect.NONE,
                        ExecutionError.Certainty.KNOWN, prepared.request().context().traceId()), Usage.unknown(), null));
            });
            var heartbeats = Flux.interval(newAiExecutionProperties.attemptLease().dividedBy(3)).concatMap(ignored ->
                    completed.get() ? Mono.empty() : load(message.owner(), message.invocationId()).flatMap(value ->
                            value.state().terminal() ? Mono.empty() : guarded(guard -> executionStore.renewLease(guard, newAiExecutionProperties.attemptLease()), 3).then()), 1).then();
            return Mono.firstWithSignal(work, heartbeats);
        }

        Mono<Void> reserveAndSend() {
            var pricing = rate(prepared.binding().rate());
            var amount = GenerationPricing.amount(pricing, prepared.snapshot().budget().maxInputTokens(),
                    prepared.request().input().options().maxOutputTokens());
            return guarded(guard -> budgetService.reserve(new BudgetCommands.Reserve(guard,
                    CanonicalJson.key(claimed.attemptId(), "reservation"), amount, prepared.binding().rate(),
                    newAiExecutionProperties.reservationRetention())), 3)
                    .doOnNext(ignored -> timing.mark("BUDGET_RESERVED"))
                    .then(executionOutboxStore.validateClaim(message).flatMap(GenerationDispatcher::applied))
                    .then(guarded(guard -> executionStore.markDispatch(new ExecutionCommands.Dispatch(guard,
                            CanonicalJson.key(claimed.attemptId(), "remote-request"))), 3))
                    .doOnNext(ignored -> timing.mark("DISPATCH_MARKED"))
                    .flatMap(attempt -> consume(new GatewayCall<>(prepared.request(), prepared.binding(), attempt, prepared.runtime())))
                    .flatMap(this::finish);
        }

        <T> Mono<T> guarded(Function<ExecutionCommands.Guard, Mono<StoreOutcome<T>>> operation, int retries) {
            return load(message.owner(), message.invocationId()).flatMap(invocation ->
                    executionStore.findAttempt(message.owner(), message.invocationId(), claimed.attemptId())
                            .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                            .flatMap(attempt -> {
                                if (!attempt.workerId().equals(claimed.workerId()) || attempt.fencingToken() != claimed.fencingToken()) {
                                    return Mono.error(new AdmissionException(ResultCodeEnum.AI_LEASE_LOST));
                                }
                                return operation.apply(ExecutionCommands.Guard.from(message.owner(), invocation, attempt))
                                        .flatMap(outcome -> outcome.code() == StoreOutcome.Code.VERSION_CONFLICT && retries > 0
                                                ? guarded(operation, retries - 1) : applied(outcome));
                            }));
        }

        Mono<GenerationSignal> consume(GatewayCall<GenerationRequest> call) {
            var signals = Flux.defer(() -> {
                        timing.mark("GATEWAY_SUBSCRIBED");
                        return modelGateway.generate(call);
                    }).doOnNext(signal -> {
                        if (signal instanceof GenerationSignal.Delta(GenerationEvent.TextDelta delta)) {
                            if (firstTextReceived.compareAndSet(false, true)) timing.mark("FIRST_TEXT_DELTA");
                            preview(delta.text());
                        } else if (!(signal instanceof GenerationSignal.Delta)) {
                            liveTextEnded = true;
                        }
                    }).doOnComplete(() -> timing.mark("GATEWAY_COMPLETED"))
                    .onErrorResume(error -> Flux.just(failure("PROVIDER_FLOW_INTERRUPTED")));
            return GenerationOutputBatches.batch(signals)
                    .concatMap(this::saveBatch, 1)
                    .then(Mono.defer(() -> Mono.just(terminal == null ? failure("MISSING_GENERATION_TERMINAL") : terminal)))
                    .timeout(Duration.between(clock.instant(), prepared.runtime().execution().deadline()).isNegative()
                            ? Duration.ofMillis(1) : Duration.between(clock.instant(), prepared.runtime().execution().deadline()).plusMillis(1))
                    .onErrorResume(TimeoutException.class, error -> Mono.just(failure("INVOCATION_TIMED_OUT")))
                    .onErrorResume(GenerationException.class, error -> Mono.just(new GenerationSignal.Failure(
                            error.error(prepared.request().context().traceId()), usage, null)));
        }

        void preview(String value) {
            if (liveTextNotifier == null || liveTextEnded || completed.get()
                    || !clock.instant().isBefore(prepared.runtime().execution().deadline())) return;
            if (liveTextOffset + value.length() > com.arte.ainew.common.validation.ContractChecks.MAX_TEXT_CHARS) {
                liveTextEnded = true;
                return;
            }
            // 大片段只做有界拆分，不增加定时等待，也不切断 UTF-16 代理对。
            for (int start = 0; start < value.length(); ) {
                int end = Math.min(value.length(), start + LiveTextDelta.MAX_CHARS);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                liveTextNotifier.emit(new LiveTextDelta(message.owner(), message.invocationId(), claimed.attemptId(),
                        liveTextOffset + start, value.substring(start, end)));
                start = end;
            }
            liveTextOffset += value.length();
        }

        Mono<Void> saveBatch(List<GenerationSignal> signals) {
            return Mono.defer(() -> {
                var deltas = new java.util.ArrayList<GenerationEvent>();
                for (var signal : signals) {
                    if (terminal != null) {
                        throw GenerationException.output("SIGNAL_AFTER_GENERATION_TERMINAL");
                    }
                    if (signal instanceof GenerationSignal.Delta(GenerationEvent event)) {
                        if (event instanceof GenerationEvent.TextDelta(String text1)) {
                            if (text.length() + text1.length() > com.arte.ainew.common.validation.ContractChecks.MAX_TEXT_CHARS) {
                                throw GenerationException.output("OUTPUT_LIMIT_EXCEEDED");
                            }
                            text.append(text1);
                            hasTextDelta = true;
                        } else if (event instanceof GenerationEvent.ToolCallDelta) {
                            throw GenerationException.output("UNSUPPORTED_PROVIDER_OUTPUT");
                        } else if (event instanceof GenerationEvent.UsageReported(Usage usage1)) {
                            usage = usage1;
                        }
                        deltas.add(event);
                    } else {
                        terminal = signal;
                    }
                }
                if (deltas.isEmpty()) {
                    return Mono.empty();
                }
                String batchKey = claimed.attemptId() + ":" + batchNumber++;
                long started = System.nanoTime();
                return guarded(guard -> executionEventStore.appendBatch(new ExecutionCommands.Append(guard, batchKey,
                        List.of(new ExecutionPayload.OutputBatch(deltas)))), 3).doOnNext(events -> {
                    long sequence = events.getFirst().sequence();
                    if (deltas.stream().anyMatch(GenerationEvent.TextDelta.class::isInstance)
                            && firstTextCommitted.compareAndSet(false, true))
                        timing.mark("FIRST_OUTPUT_COMMITTED", started, sequence);
                    timing.batch("OUTPUT_BATCH_COMMITTED", started, sequence);
                }).then();
            });
        }

        GenerationSignal.Failure failure(String code) {
            return new GenerationSignal.Failure(new ExecutionError(code, ExecutionError.Phase.INVOCATION, false,
                    ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN,
                    prepared.request().context().traceId()), usage, null);
        }

        Mono<Void> finish(GenerationSignal ending) {
            return Mono.defer(() -> {
                var result = ending instanceof GenerationSignal.Result(ModelResult result1) ? result1
                        : ((GenerationSignal.Failure) ending).partialResult();
                var finalUsage = ending instanceof GenerationSignal.Result(ModelResult result1) ? result1.usage()
                        : ((GenerationSignal.Failure) ending).usage();
                if (result != null) {
                    String output = result.outputs().stream().flatMap(value -> value.content().stream())
                            .filter(ChatMessage.Text.class::isInstance).map(ChatMessage.Text.class::cast)
                            .map(ChatMessage.Text::text).reduce("", String::concat);
                    boolean valid = result.structuredOutput() == null && result.sources().isEmpty()
                            && result.model().providerId().equals(newAiProperties.connections().stream()
                            .filter(connection -> connection.definition().equals(prepared.binding().connection())).findFirst().orElseThrow().providerId())
                            && result.outputs().stream().allMatch(value -> value.toolCalls().isEmpty()
                            && value.content().stream().allMatch(ChatMessage.Text.class::isInstance))
                            && (!hasTextDelta || output.contentEquals(text))
                            && (usage.basis() != Usage.Basis.PROVIDER_REPORTED || usage.equals(finalUsage));
                    if (!valid) {
                        return finish(failure("INVALID_MODEL_OUTPUT"));
                    }
                }
                Mono<java.util.Optional<ResultRef>> reference = result == null ? Mono.just(java.util.Optional.empty())
                        : executionResultStore.put(message.owner(), message.invocationId(), claimed.attemptId(), "generation-result",
                        new InvocationResult.Generation(result)).flatMap(GenerationDispatcher::applied).map(java.util.Optional::of);
                long resultStart = System.nanoTime();
                return reference.doOnNext(saved -> timing.mark(saved.isPresent() ? "RESULT_STORED" : "RESULT_NOT_AVAILABLE", resultStart, 0)).flatMap(saved -> {
                    var ref = saved.orElse(null);
                    Invocation.State state;
                    ExecutionError error;
                    String evidence = null;
                    if (ending instanceof GenerationSignal.Result(ModelResult result1) && result1.complete()) {
                        state = Invocation.State.SUCCEEDED;
                        error = null;
                    } else if (ending instanceof GenerationSignal.Result) {
                        state = Invocation.State.FAILED;
                        error = new ExecutionError("MODEL_OUTPUT_INCOMPLETE", ExecutionError.Phase.OUTPUT, false,
                                ExecutionError.SideEffect.CONFIRMED, ExecutionError.Certainty.KNOWN, prepared.request().context().traceId());
                        assert ref != null;
                        evidence = "model-result:" + ref.resultId();
                    } else {
                        error = ((GenerationSignal.Failure) ending).error();
                        boolean unknown = error.certainty() == ExecutionError.Certainty.UNKNOWN
                                || error.sideEffect() != ExecutionError.SideEffect.NONE;
                        if (unknown && error.certainty() != ExecutionError.Certainty.UNKNOWN) {
                            error = new ExecutionError(error.code(), error.phase(), false, error.sideEffect(),
                                    ExecutionError.Certainty.UNKNOWN, error.correlationId());
                        }
                        state = unknown ? Invocation.State.UNKNOWN : Invocation.State.FAILED;
                    }
                    var payload = new ExecutionPayload.Terminal(state, ref, error);
                    var proof = evidence;
                    long terminalStart = System.nanoTime();
                    return guarded(guard -> executionStore.commitCompletion(new ExecutionCommands.Complete(guard,
                            "generation-completion", payload, finalUsage, proof)), 3)
                            .doOnNext(ignored -> {
                                completed.set(true);
                                timing.mark("TERMINAL_COMMITTED", terminalStart, 0);
                            })
                            .flatMap(invocation -> {
                                long budgetStart = System.nanoTime();
                                return GenerationDispatcher.this.settle(invocation)
                                        .doOnSuccess(ignored -> timing.mark("BUDGET_PROCESSING_DONE", budgetStart, 0));
                            });
                });
            });
        }
    }
}
