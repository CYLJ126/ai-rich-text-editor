package com.arte.ainew.application.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.ToolObservation;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.ToolExecutionRules;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolInvocation;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.*;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static com.arte.ainew.application.support.ToolObservation.Stage.*;

/**
 * 独立本地免费 TOOL 的一次耐久派发：读取原快照、复核资格、原子准备、调用网关并提交结果／终态。
 * 只使用本次新取得的发送许可；重复领取不调用工具，基础设施故障保留未 ACK 工作项。当前不自动重试或核对业务结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:00 ✾
 */
@Slf4j
public final class ToolDispatcher {

    private final ExecutionStore executions;
    private final ExecutionOutboxStore outbox;
    private final DispatchPreparationStore preparation;
    private final ToolAdmissionStore snapshots;
    private final ToolBindingService bindings;
    private final ToolAdmissionAccess access;
    private final ToolGateway gateway;
    private final ExecutionResultStore results;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final Clock clock;
    private final Duration attemptLease;

    /**
     * 所有存储由组合模块使用同库实现；构造不读库、不授权、不调用工具，调度器生命周期归容器。
     */
    public ToolDispatcher(ExecutionStore executions, ExecutionOutboxStore outbox, DispatchPreparationStore preparation,
                          ToolAdmissionStore snapshots, ToolBindingService bindings, ToolAdmissionAccess access,
                          ToolGateway gateway, ExecutionResultStore results, ExecutionRecordCodec codec,
                          Scheduler scheduler, Clock clock, Duration attemptLease) {
        this.executions = Objects.requireNonNull(executions);
        this.outbox = Objects.requireNonNull(outbox);
        this.preparation = Objects.requireNonNull(preparation);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.bindings = Objects.requireNonNull(bindings);
        this.access = Objects.requireNonNull(access);
        this.gateway = Objects.requireNonNull(gateway);
        this.results = Objects.requireNonNull(results);
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
        ExecutionCommands.validLease(attemptLease);
        if (attemptLease.compareTo(Duration.ofSeconds(3)) < 0)
            throw new IllegalArgumentException("Tool lease must allow heartbeats");
        this.attemptLease = attemptLease;
    }

    /**
     * Worker 负责工作项续租及提交后 ACK；这里只处理权威 TOOL 记录，不信任消息或调用方自报能力。
     */
    public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
        return ToolObservation.mono(DISPATCH, runtime.execution(), () -> Mono.defer(() -> {
            require(message.kind() == OutboxMessage.Kind.DISPATCH
                    && message.invocationId().equals(runtime.execution().executionId())
                    && message.owner().equals(ExecutionOwner.from(runtime.execution())), ResultCodeEnum.AI_OWNER_MISMATCH);
            return ToolObservation.mono(DISPATCH_LOAD, runtime.execution(), () -> committed(outbox.validateClaim(message))
                    .then(load(message))).flatMap(invocation -> {
                verifyRuntime(invocation, runtime);
                if (invocation.state().terminal()) return Mono.empty();
                if (invocation.activeAttemptId() != null) {
                    return ToolObservation.mono(RECOVERY, runtime.execution(), () -> recover(message, invocation));
                }
                return ToolObservation.mono(PREFLIGHT, runtime.execution(), () -> preflight(invocation, runtime))
                        .onErrorResume(error -> predictable(error)
                                ? reject(invocation, error).then(Mono.empty()) : Mono.error(error))
                        .flatMap(prepared -> ToolObservation.mono(PREPARE_DISPATCH, runtime.execution(), () -> committed(preparation.prepareDispatch(new DispatchPreparationStore.Command(version(invocation), message,
                                        CanonicalJson.key(message.invocationId(), "tool-attempt-1"), attemptLease, new DispatchPreparationStore.Free()))))
                                .flatMap(ready -> {
                                    if (!ready.ready() || ready.billingMode() != DispatchPreparationStore.BillingMode.FREE)
                                        return Mono.error(new IllegalStateException("Tool dispatch requires fresh FREE preparation"));
                                    // 通过 Session.run() 执行工具调用并处理结果
                                    return new Session(message, prepared, ready.attempt()).run();
                                }));
            });
        }).subscribeOn(scheduler));
    }

    /**
     * 原结果已经落库则校验并关联，不再订阅网关；没有结果时按原派发事实保守结束。
     * 不复核新调用资格或当前绑定启用状态，关闭工具／撤权也不能阻断系统保存已发生的事实。
     */
    private Mono<Void> recover(OutboxMessage message, Invocation invocation) {
        return executions.findAttempt(message.owner(), message.invocationId(), invocation.activeAttemptId())
                .switchIfEmpty(Mono.error(new IllegalStateException("Tool attempt is missing")))
                .flatMap(attempt -> {
                    if (attempt.leaseExpiresAt().isAfter(clock.instant()))
                        return Mono.error(new AdmissionException(ResultCodeEnum.AI_LEASE_LOST));
                    return results.staged(message.owner(), message.invocationId(), attempt.attemptId(), "tool-result")
                            .flatMap(saved -> snapshots.snapshot(message.owner(), message.invocationId())
                                    .switchIfEmpty(Mono.error(new IllegalStateException("Tool recovery snapshot is missing")))
                                    .flatMap(snapshot -> {
                                        if (!snapshot.request().equals(invocation.request()) || !snapshot.requestDigest().equals(invocation.requestDigest())
                                                || !(saved.result() instanceof InvocationResult.Tool tool))
                                            return Mono.error(new IllegalStateException("Tool recovery result mismatch"));
                                        var result = tool.value();
                                        var output = snapshot.schemas().stream().filter(value -> value.definition().equals(
                                                snapshot.binding().tool().tool().outputSchema())).findFirst().orElseThrow();
                                        var max = Math.min(snapshot.policy().maxOutputBytes(), invocation.request().options().maxOutputBytes());
                                        if (!ToolCatalogDigests.schema(output.document()).equals(output.checksum())
                                                || !result.callId().equals(snapshot.request().input().callId())
                                                || !ToolExecutionRules.resultAllowed(snapshot.binding().tool().tool().risk(), result)
                                                || CanonicalJson.normalize(codec.encode(saved.result())).getBytes(StandardCharsets.UTF_8).length > max
                                                || result.output() != null && !new ToolSchemaValidator().compile(output.definition(), output.document(),
                                                ToolSchemaValidator.Purpose.OUTPUT).validate(result.output(), max).valid())
                                            return Mono.error(new IllegalStateException("Tool recovery result is invalid"));
                                        if (!(executions instanceof ToolRecoveryStore recovery))
                                            return Mono.error(new IllegalStateException("Atomic tool recovery is unavailable"));
                                        return committed(recovery.completeStoredToolResult(new ToolRecoveryStore.CompleteStored(version(invocation), message,
                                                new ExecutionPayload.Terminal(terminalState(result), saved.reference(), result.error()))));
                                    }))
                            .switchIfEmpty(Mono.defer(() -> committed(executions.stopExpired(version(invocation))))).then();
                });
    }

    private static Invocation.State terminalState(ToolResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> Invocation.State.SUCCEEDED;
            case UNKNOWN -> Invocation.State.UNKNOWN;
            case FAILED -> result.error().code().equals("TOOL_DEADLINE_EXCEEDED") ? Invocation.State.TIMED_OUT
                    : result.error().code().equals("TOOL_CANCELLED") ? Invocation.State.CANCELLED : Invocation.State.FAILED;
        };
    }

    private Mono<Prepared> preflight(Invocation invocation, ExecutionRuntimeContext runtime) {
        return Mono.defer(() -> {
            require(clock.instant().isBefore(runtime.execution().deadline()), ResultCodeEnum.AI_DEADLINE_EXCEEDED);
            return executions.cancellationRequested(ExecutionOwner.from(runtime.execution()), runtime.execution().executionId())
                    .switchIfEmpty(Mono.error(new IllegalStateException("Tool cancellation facts are missing")))
                    .flatMap(cancelled -> {
                        if (cancelled || runtime.cancellation().isCancelled())
                            throw new CancellationException("TOOL_CANCELLED");
                        return snapshots.snapshot(ExecutionOwner.from(runtime.execution()), runtime.execution().executionId())
                                .switchIfEmpty(Mono.error(new IllegalStateException("Accepted tool snapshot is missing")));
                    });
        }).flatMap(snapshot -> {
            if (!snapshot.request().equals(invocation.request()) || !snapshot.requestDigest().equals(invocation.requestDigest()))
                return Mono.error(new IllegalStateException("Tool dispatch snapshot mismatch"));
            return bindings.fixed(snapshot.binding().owner(), snapshot.binding().definition())
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE)))
                    .flatMap(fixed -> {
                        require(fixed.successful() && snapshot.binding().equals(fixed.value()), ResultCodeEnum.AI_TOOL_NOT_AVAILABLE);
                        // 复用宿主的免费使用资格预检；网关在真正进入业务适配前另通过 ToolExecutionAccess 重验当前资源权限。
                        return Mono.defer(() -> access.authorizeFree(snapshot.input(), runtime.execution(), snapshot.binding()))
                                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_POLICY_DENIED)));
                    }).publishOn(scheduler).map(permit -> {
                        var current = Objects.requireNonNull(permit.authorization());
                        var before = runtime.execution().authorization();
                        require(current.principal().subjectId().equals(before.principal().subjectId()) && current.principal().kind() == before.principal().kind()
                                && current.tenantId().equals(before.tenantId()) && current.workspaceId().equals(before.workspaceId())
                                && before.scopes().containsAll(current.scopes()) && current.scopes().contains(AdmissionAuthorization.INVOKE)
                                && current.scopes().containsAll(snapshot.binding().tool().tool().risk().requiredScopes()), ResultCodeEnum.AI_TOOL_POLICY_DENIED);
                        require(clock.instant().isBefore(runtime.execution().deadline()), ResultCodeEnum.AI_DEADLINE_EXCEEDED);
                        var context = runtime.execution();
                        var effective = new ExecutionContext(context.executionId(), context.traceId(), current, context.deadline(), null, null,
                                context.releaseRef(), context.idempotencyKey());
                        var output = snapshot.schemas().stream().filter(value -> value.definition().equals(snapshot.binding().tool().tool().outputSchema()))
                                .findFirst().orElseThrow();
                        if (!ToolCatalogDigests.schema(output.document()).equals(output.checksum()))
                            throw new IllegalStateException("Tool schema is corrupt");
                        return new Prepared(snapshot, new ExecutionRuntimeContext(effective, runtime.cancellation()),
                                new ToolSchemaValidator().compile(output.definition(), output.document(), ToolSchemaValidator.Purpose.OUTPUT));
                    });
        }).timeout(remaining(runtime)).onErrorMap(TimeoutException.class, expired -> new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
    }

    private Duration remaining(ExecutionRuntimeContext runtime) {
        var value = Duration.between(clock.instant(), runtime.execution().deadline());
        return value.isNegative() || value.isZero() ? Duration.ofNanos(1) : value;
    }

    private static void verifyRuntime(Invocation invocation, ExecutionRuntimeContext runtime) {
        var accepted = invocation.request();
        var current = runtime.execution();
        require(accepted.kind() == CapabilityDescriptor.Kind.TOOL && accepted.input() instanceof ToolInvocation, ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
        require(accepted.context().authorization().principal().kind() == current.authorization().principal().kind()
                && accepted.context().authorization().scopes().containsAll(current.authorization().scopes())
                && Objects.equals(accepted.context().releaseRef(), current.releaseRef())
                && current.budgetRef() == null && accepted.context().budgetRef() == null && current.parentExecutionId() == null
                && accepted.context().parentExecutionId() == null && !current.deadline().isAfter(accepted.options().deadline()), ResultCodeEnum.AI_OWNER_MISMATCH);
    }

    /**
     * 仅把可预期的资格／期限拒绝保存为未派发终态；数据库、快照损坏等故障留待恢复或运维。
     */
    private Mono<Void> reject(Invocation invocation, Throwable rejected) {
        var code = rejected instanceof AdmissionException business ? business.getResultCode().name()
                : rejected instanceof CancellationException ? "TOOL_CANCELLED" : ResultCodeEnum.AI_TOOL_POLICY_DENIED.name();
        var state = code.equals(ResultCodeEnum.AI_DEADLINE_EXCEEDED.name()) ? Invocation.State.TIMED_OUT
                : rejected instanceof CancellationException ? Invocation.State.CANCELLED : Invocation.State.FAILED;
        var error = new ExecutionError(code, rejected instanceof AccessDeniedException ? ExecutionError.Phase.AUTHORIZATION
                : ExecutionError.Phase.ADMISSION, false, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN,
                invocation.request().context().traceId());
        return ToolObservation.mono(TERMINAL_COMMIT, invocation.request().context(), () -> committed(executions.commitCompletion(new ExecutionCommands.CompleteBeforeAttempt(version(invocation), "tool-preflight",
                new ExecutionPayload.Terminal(state, null, error))))).then();
    }

    /**
     * 当前固定快照与缩小后的权限；编译产物仅在进程内使用，不写回耐久快照。
     */
    private record Prepared(ToolExecutionSnapshot snapshot, ExecutionRuntimeContext runtime,
                            ToolSchemaValidator.CompiledSchema output) {
    }

    private final class Session {
        private final OutboxMessage message;
        private final Prepared prepared;
        private final Attempt claimed;
        private final AtomicBoolean completed = new AtomicBoolean();

        private Session(OutboxMessage message, Prepared prepared, Attempt claimed) {
            this.message = message;
            this.prepared = prepared;
            this.claimed = claimed;
        }

        private Mono<Void> run() {
            var call = new GatewayCall<>(prepared.snapshot().request(), prepared.snapshot().binding().resolvedBinding(), claimed, prepared.runtime());
            var remaining = Duration.between(clock.instant(), prepared.runtime().execution().deadline());
            var invoke = Mono.defer(() -> {
                        if (!clock.instant().isBefore(prepared.runtime().execution().deadline()))
                            return Mono.just(notInvoked("TOOL_DEADLINE_EXCEEDED"));
                        if (prepared.runtime().cancellation().isCancelled()) return Mono.just(notInvoked("TOOL_CANCELLED"));
                        // 调用工具（通过 ToolGateway 调用工具适配器，进而执行工具逻辑）
                        return Mono.firstWithSignal(ToolObservation.mono(GATEWAY, prepared.runtime().execution(), () -> gateway.invoke(call)), prepared.runtime().cancellation().signal()
                                .map(ignored -> unknown("TOOL_CANCELLED")));
                    }).switchIfEmpty(Mono.error(new IllegalStateException("Empty tool gateway result")))
                    .timeout(remaining.isNegative() || remaining.isZero() ? Duration.ofNanos(1) : remaining)
                    .onErrorResume(error -> Mono.just(unknown(error instanceof TimeoutException ? "TOOL_DEADLINE_EXCEEDED" : "TOOL_GATEWAY_FAILURE")))
                    .publishOn(scheduler).map(this::checked)
                    .onErrorResume(IllegalArgumentException.class, invalid -> Mono.just(unknown("TOOL_OUTPUT_INVALID"))).flatMap(this::finish);
            // 准备后再读一次耐久停止意图；数据库故障不能被网关异常转换为已完成并 ACK。
            var work = executions.cancellationRequested(message.owner(), message.invocationId())
                    .switchIfEmpty(Mono.error(new IllegalStateException("Tool cancellation facts are missing")))
                    .flatMap(requested -> requested ? finish(notInvoked("TOOL_CANCELLED")) : invoke);
            // 慢数据库下合并定时提醒，保持查询串行；interval 缺少需求会溢出并取消主执行链。
            var heartbeat = Flux.interval(attemptLease.dividedBy(3)).onBackpressureLatest().concatMap(ignored -> completed.get() ? Mono.empty()
                    : load(message).flatMap(invocation -> invocation.state().terminal() ? Mono.empty()
                    : guarded(guard -> executions.renewLease(guard, attemptLease), 3).then()), 1).then();
            var cancellations = Flux.interval(Duration.ofMillis(200)).onBackpressureLatest().concatMap(ignored -> completed.get() ? Mono.empty()
                    : executions.cancellationRequested(message.owner(), message.invocationId())
                    .switchIfEmpty(Mono.error(new IllegalStateException("Tool cancellation facts are missing")))
                    .doOnNext(requested -> {
                        if (requested) prepared.runtime().cancellation().cancel("USER_CANCELLED");
                    }).then(), 1).then();
            return Mono.firstWithSignal(work, heartbeat, cancellations)
                    .doOnError(error -> prepared.runtime().cancellation().cancel("TOOL_DISPATCH_INTERRUPTED"));
        }

        /**
         * 自定义网关也不能提交错身份、越限输出或与固定风险画像冲突的结果。异常事实归为 UNKNOWN，不重发。
         */
        private ToolResult checked(ToolResult result) {
            var snapshot = prepared.snapshot();
            var max = Math.min(snapshot.policy().maxOutputBytes(), snapshot.request().options().maxOutputBytes());
            if (!result.callId().equals(snapshot.request().input().callId()) || !ToolExecutionRules.resultAllowed(snapshot.binding().tool().tool().risk(), result)
                    || result.output() != null && !prepared.output().validate(result.output(), max).valid())
                return unknown("TOOL_OUTPUT_INVALID");
            if (bytes(result) > max) return unknown("TOOL_OUTPUT_LIMIT");
            return result;
        }

        private long bytes(ToolResult result) {
            return CanonicalJson.normalize(codec.encode(new InvocationResult.Tool(result))).getBytes(StandardCharsets.UTF_8).length;
        }

        private ToolResult notInvoked(String code) {
            var fact = new ExecutionError(code, ExecutionError.Phase.DISPATCH, false, ExecutionError.SideEffect.NONE,
                    ExecutionError.Certainty.KNOWN, prepared.runtime().execution().traceId());
            return new ToolResult(prepared.snapshot().request().input().callId(), ToolResult.Outcome.FAILED, null, List.of(), List.of(), fact.sideEffect(), fact);
        }

        private ToolResult unknown(String code) {
            var fact = new ExecutionError(code, ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE,
                    ExecutionError.Certainty.UNKNOWN, prepared.runtime().execution().traceId());
            return new ToolResult(prepared.snapshot().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(), fact.sideEffect(), fact);
        }

        private Mono<Void> finish(ToolResult result) {
            var max = Math.min(prepared.snapshot().policy().maxOutputBytes(), prepared.snapshot().request().options().maxOutputBytes());
            // 极小的请求上限可能连安全失败信封也放不下；仍保存 Invocation 错误事实，不伪造成功或丢弃大业务输出。
            Mono<Optional<ResultRef>> saved = bytes(result) > max ? Mono.just(Optional.empty())
                    : ToolObservation.mono(RESULT_PERSIST, prepared.runtime().execution(), () -> committed(results.put(message.owner(), message.invocationId(), claimed.attemptId(), "tool-result", new InvocationResult.Tool(result))))
                    .map(Optional::of);
            return saved.flatMap(reference -> {
                var state = terminalState(result);
                return ToolObservation.mono(TERMINAL_COMMIT, prepared.runtime().execution(), () -> guarded(guard -> executions.commitCompletion(new ExecutionCommands.Complete(guard,
                                CanonicalJson.key(claimed.attemptId(), "tool-completion"), new ExecutionPayload.Terminal(state, reference.orElse(null), result.error()),
                                Usage.unknown(), null)), 3))
                        .doOnNext(value -> {
                            completed.set(true);
                            log.info("AI tool terminal committed, invocationId={}, traceId={}, attemptId={}, state={}", message.invocationId(),
                                    prepared.runtime().execution().traceId(), claimed.attemptId(), value.state());
                        }).then();
            });
        }

        /**
         * 每次写入读取最新 Guard；只重试版本竞争，绝不重新订阅业务网关。
         */
        private <T> Mono<T> guarded(Function<ExecutionCommands.Guard, Mono<StoreOutcome<T>>> operation, int remaining) {
            return load(message).flatMap(invocation -> executions.findAttempt(message.owner(), message.invocationId(), claimed.attemptId())
                    .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND))).flatMap(current -> {
                        require(current.workerId().equals(claimed.workerId()) && current.fencingToken() == claimed.fencingToken(), ResultCodeEnum.AI_LEASE_LOST);
                        return operation.apply(ExecutionCommands.Guard.from(message.owner(), invocation, current))
                                .switchIfEmpty(Mono.error(new IllegalStateException("Tool guarded write returned no outcome"))).flatMap(result ->
                                        result.code() == StoreOutcome.Code.VERSION_CONFLICT && remaining > 0 ? guarded(operation, remaining - 1) : applied(result));
                    }));
        }
    }

    private Mono<Invocation> load(OutboxMessage message) {
        return executions.find(message.owner(), message.invocationId()).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)));
    }

    private static ExecutionCommands.Version version(Invocation invocation) {
        return new ExecutionCommands.Version(ExecutionOwner.from(invocation.request().context()), invocation.request().context().executionId(), invocation.version());
    }

    /**
     * 写入／领取端口必须给出明确回执，空完成不能被 Worker 当作可靠处理并 ACK。
     */
    private static <T> Mono<T> committed(Mono<StoreOutcome<T>> operation) {
        return operation.switchIfEmpty(Mono.error(new IllegalStateException("Tool persistence returned no outcome"))).flatMap(ToolDispatcher::applied);
    }

    private static <T> Mono<T> applied(StoreOutcome<T> result) {
        return result.successful() ? Mono.just(result.value()) : Mono.error(AdmissionException.fromStoreRejection(result.code()));
    }

    private static boolean predictable(Throwable error) {
        return error instanceof AdmissionException || error instanceof AccessDeniedException || error instanceof CancellationException;
    }

    private static void require(boolean condition, ResultCodeEnum code) {
        if (!condition) throw new AdmissionException(code);
    }
}
