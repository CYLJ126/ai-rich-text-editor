package com.arte.ai.api.execution;

import com.arte.ai.api.gateway.ModelGateway;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.model.execution.*;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelResult;
import com.arte.ai.model.generation.PreparedModelCall;
import com.arte.ai.spi.security.ModelAccessPolicy;
import com.arte.ai.spi.store.ExecutionEventStore;
import com.arte.ai.spi.store.ExecutionStore;
import com.arte.ai.spi.store.ModelWorkQueue;
import com.arte.base.api.admission.AdmissionController;
import com.arte.base.api.security.EgressPolicy;
import com.arte.base.exception.BaseException;
import com.arte.base.execution.ExecutionCheckpoint;
import com.arte.base.execution.ExecutionFailures;
import com.arte.base.execution.TaskHandle;
import com.arte.base.model.admission.AdmissionKey;
import com.arte.base.model.admission.AdmissionPermit;
import com.arte.base.model.admission.AdmissionPriority;
import com.arte.base.model.admission.AdmissionRequest;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.*;
import com.arte.base.model.observability.AuditOutcome;
import com.arte.base.model.observability.AuditRecord;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.EgressRequest;
import com.arte.base.spi.execution.TaskExecutor;
import com.arte.base.spi.observability.AuditSink;
import com.arte.base.spi.observability.Telemetry;
import com.arte.base.validation.ContractChecks;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单次非流式模型协调：可靠受理、一个尝试、显式费用／输出状态，不自动重试未知结果。
 */
public class InvocationCoordinator {
    private final ModelBindingResolver modelBindingResolver;
    private final ModelGateway gateway;
    private final ModelAccessPolicy access;
    private final EgressPolicy egress;
    private final AdmissionController admission;
    private final TaskExecutor taskExecutor;
    private final ExecutionStore store;
    private final ExecutionEventStore events;
    private final BudgetService budgets;
    private final AuditSink audit;
    private final Clock clock;
    private final Telemetry telemetry;
    private final ModelWorkQueue workQueue;
    private final ConcurrentMap<String, TaskHandle<?>> live = new ConcurrentHashMap<>();

    public InvocationCoordinator(ModelBindingResolver modelBindingResolver, ModelGateway gateway, ModelAccessPolicy access,
                                 EgressPolicy egress, AdmissionController admission, TaskExecutor taskExecutor, ExecutionStore store,
                                 ExecutionEventStore events, BudgetService budgets, AuditSink audit, Clock clock) {
        this(modelBindingResolver, gateway, access, egress, admission, taskExecutor, store, events, budgets, audit, clock, Telemetry.disabled());
    }

    public InvocationCoordinator(ModelBindingResolver modelBindingResolver, ModelGateway gateway, ModelAccessPolicy access,
                                 EgressPolicy egress, AdmissionController admission, TaskExecutor taskExecutor, ExecutionStore store,
                                 ExecutionEventStore events, BudgetService budgets, AuditSink audit, Clock clock, Telemetry telemetry) {
        this(modelBindingResolver, gateway, access, egress, admission, taskExecutor, store, events, budgets, audit, clock, telemetry, null);
    }

    public InvocationCoordinator(ModelBindingResolver modelBindingResolver, ModelGateway gateway, ModelAccessPolicy access,
                                 EgressPolicy egress, AdmissionController admission, TaskExecutor taskExecutor, ExecutionStore store,
                                 ExecutionEventStore events, BudgetService budgets, AuditSink audit, Clock clock, Telemetry telemetry,
                                 ModelWorkQueue workQueue) {
        this.workQueue = workQueue;
        this.telemetry = java.util.Objects.requireNonNull(telemetry);
        this.modelBindingResolver = modelBindingResolver;
        this.gateway = gateway;
        this.access = access;
        this.egress = egress;
        this.admission = admission;
        this.taskExecutor = taskExecutor;
        this.store = store;
        this.events = events;
        this.budgets = budgets;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * 服务端准备最终发送正文，供已认证入口展示目的地并取得明确同意。此方法不外发。
     */
    public PreparedModelCall prepare(InvocationRequest<GenerationRequest> request) {
        ContractChecks.required(request, "request");
        if (request.context().isExpiredAt(clock.instant()))
            throw fail(request, CommonErrorCode.DEADLINE_EXCEEDED, "prepare");
        // 解析模型能力、绑定和连接
        var plan = modelBindingResolver.resolve(request);
        access.requireAllowed(request.context(), plan);
        try {
            return gateway.prepare(plan, request.input());
        } catch (IllegalArgumentException invalid) {
            throw fail(request, CommonErrorCode.INVALID_ARGUMENT, "model-input");
        }
    }

    public EgressRequest egressRequest(InvocationRequest<GenerationRequest> request, PreparedModelCall prepared, ResourceRef consent) {
        return EgressRequest.of(request.context(), List.of(), prepared.plan().destination(), "model.generate", prepared.contentDigest(), consent);
    }

    /**
     * 消息来源为空的最小链路；业务来源后续经 ContextService 解析并逐项授权，不接受伪造来源。
     */
    public AcceptedExecution submitModel(InvocationRequest<GenerationRequest> request, ResourceRef consent, String idempotencyKey) {
        ContractChecks.identifier(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > 128) throw new IllegalArgumentException("idempotencyKey is too long");
        Instant executionDeadline = clock.instant().plus(request.options().timeout());
        var prepared = prepare(request);
        egress.requireAllowed(egressRequest(request, prepared, consent), clock);
        String requestDigest = fingerprint(prepared, request.options());
        var duplicate = store.findIdempotent(request.context().scope(), idempotencyKey, requestDigest);
        if (duplicate.isPresent()) return receipt(duplicate.get());
        if (workQueue != null) {
            var context = request.context();
            var deadline = context.deadline() != null && context.deadline().isBefore(executionDeadline) ? context.deadline() : executionDeadline;
            var submission = new ModelSubmission(UUID.randomUUID().toString(), UUID.randomUUID().toString(), prepared.plan(), context, idempotencyKey, requestDigest);
            return receipt(workQueue.accept(submission, budgets.quote(), new QueuedModelCall(request, consent, requestDigest, deadline)).execution());
        }
        AdmissionPermit permit;
        try {
            permit = telemetry.observe(request.context(), "model.admission", () -> admission.acquire(new AdmissionRequest(request.context(),
                            new AdmissionKey(request.context().scope().tenantId(), "ai.interactive", null, null), AdmissionPriority.INTERACTIVE, Duration.ZERO))
                    .completion().toCompletableFuture().join());
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            throw failed;
        }
        boolean handedOff = false;
        try {
            String digest = requestDigest;
            var submission = new ModelSubmission(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    prepared.plan(), request.context(), idempotencyKey, digest);
            // 登记执行、预留预算
            var accepted = store.accept(submission, budgets.quote());
            var execution = accepted.execution();
            if (accepted.created()) {
                var context = request.context();
                Instant deadline = executionDeadline;
                if (context.deadline() != null && context.deadline().isBefore(deadline)) deadline = context.deadline();
                var boundedContext = new ExecutionContext(context.scope(), context.traceId(), context.parentExecutionId(), deadline,
                        context.cancellation(), context.authorizationScopes(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
                TaskHandle<ModelResult> task;
                try {
                    // 提交异步任务，执行模型调用
                    long queuedAt = System.nanoTime();
                    task = taskExecutor.submit(boundedContext, checkpoint -> {
                        telemetry.duration("arte.ai.execution.queue.wait", Duration.ofNanos(System.nanoTime() - queuedAt),
                                Map.of(Telemetry.Label.COMPONENT, "ai-new", Telemetry.Label.OPERATION, "model.execute"));
                        return run(execution, request, prepared, consent, checkpoint);
                    });
                } catch (RuntimeException rejected) {
                    store.finish(context.scope(), execution.executionId(), ExecutionStatus.FAILED, null,
                            ExecutionFailures.beforeStart(CommonErrorCode.BUSY, context, "dispatch").error());
                    throw rejected;
                }
                live.put(execution.executionId(), task);
                handedOff = true;
                task.completion().whenComplete((result, failure) -> {
                    try {
                        // 包含提交拒绝、排队取消与排队超时；存储终态写入幂等，已完成任务不会覆盖。
                        if (failure != null) recordFailure(execution, request, failure);
                    } finally {
                        live.remove(execution.executionId(), task);
                        permit.close();
                    }
                });
            }
            return receipt(execution);
        } finally {
            if (!handedOff) permit.close();
        }
    }

    private static AcceptedExecution receipt(ModelExecution execution) {
        return new AcceptedExecution(execution.executionId(), "ai.model", "ACCEPTED",
                URI.create("/api/ai-new/model/executions/" + execution.executionId()),
                URI.create("/api/ai-new/model/executions/" + execution.executionId() + "/events"));
    }

    private ModelResult run(ModelExecution execution, InvocationRequest<GenerationRequest> request,
                            PreparedModelCall prepared, ResourceRef consent, ExecutionCheckpoint checkpoint) throws Exception {
        return run(execution, request, prepared, consent, checkpoint, store);
    }

    /**
     * Worker 内部入口；恢复时重新解析定义、验证正文指纹及当前权限，再经过受围栏保护的派发边界。
     */
    public ModelResult executeQueued(ModelExecution execution, QueuedModelCall call, ExecutionStore fencedStore,
                                     ExecutionCheckpoint checkpoint) throws Exception {
        checkpoint.check();
        var prepared = prepare(call.request());
        if (!call.fingerprint().equals(fingerprint(prepared, call.request().options()))
                || !execution.connectionRef().equals(prepared.plan().connection().ref()))
            throw fail(call.request(), CommonErrorCode.VERSION_CONFLICT, "queued-definition");
        return run(execution, call.request(), prepared, call.consent(), checkpoint, fencedStore);
    }

    public void queuedFailure(ModelExecution execution, QueuedModelCall call, ExecutionStore fencedStore, Throwable failure) {
        recordFailure(execution, call.request(), failure, fencedStore);
    }

    private ModelResult run(ModelExecution execution, InvocationRequest<GenerationRequest> request,
                            PreparedModelCall prepared, ResourceRef consent, ExecutionCheckpoint checkpoint, ExecutionStore store) throws Exception {
        var context = request.context();
        String id = execution.executionId();
        if (!store.start(context.scope(), id)) throw fail(request, CommonErrorCode.VERSION_CONFLICT, "attempt");
        boolean dispatched = false;
        try (var span = telemetry.startSpan(context, "model.execute")) {
            try {
                checkpoint.check();
                // 准备发送内容：解析模型能力、绑定和连接
                var current = prepare(request);
                if (!current.plan().equals(prepared.plan()) || !current.contentDigest().equals(prepared.contentDigest()))
                    throw fail(request, CommonErrorCode.VERSION_CONFLICT, "definition");
                var decision = egress.requireAllowed(egressRequest(request, current, consent), clock);
                audit.append(new AuditRecord(id + ":dispatch", "ai.model.dispatch", context.scope(), context.scope().principal(),
                        context.traceId(), id, clock.instant(), AuditOutcome.ALLOWED,
                        List.of(current.plan().capability().descriptor().ref().resource(), current.plan().binding().ref().resource(), current.plan().connection().ref().resource()),
                        Set.of(), decision.policy().policyVersions()));
                checkpoint.check();
                // 审计后在发送边界再次核对；任何未确认策略均停止外发。
                access.requireAllowed(context, current.plan());
                egress.requireAllowed(egressRequest(request, current, consent), clock);
                store.markDispatched(context.scope(), id);
                dispatched = true;
                // 与模型交互，生成结果；记录网络阶段成功和失败耗时。
                ModelResult result;
                long providerStarted = System.nanoTime();
                try {
                    result = gateway.generate(current, checkpoint);
                } finally {
                    telemetry.duration("arte.ai.provider.duration", Duration.ofNanos(System.nanoTime() - providerStarted),
                            Map.of(Telemetry.Label.COMPONENT, "ai-new", Telemetry.Label.OPERATION, "model.generate"));
                }
                checkpoint.check();
                access.requireAllowed(context, current.plan());
                store.finish(context.scope(), id, ExecutionStatus.SUCCEEDED, result, null);
                telemetry.increment("arte.ai.execution.finished", 1, Map.of(Telemetry.Label.OUTCOME, ExecutionStatus.SUCCEEDED.name()));
                if (result.usage().inputTokens() != null)
                    telemetry.increment("arte.ai.tokens", result.usage().inputTokens(), Map.of(Telemetry.Label.OPERATION, "input"));
                if (result.usage().outputTokens() != null)
                    telemetry.increment("arte.ai.tokens", result.usage().outputTokens(), Map.of(Telemetry.Label.OPERATION, "output"));
                span.outcome(AuditOutcome.SUCCEEDED);
                return result;
            } catch (Exception failure) {
                span.outcome(dispatched ? AuditOutcome.UNKNOWN : AuditOutcome.FAILED);
                recordFailure(execution, request, failure, store);
                throw failure;
            }
        }
    }

    private void recordFailure(ModelExecution execution, InvocationRequest<GenerationRequest> request, Throwable failure) {
        recordFailure(execution, request, failure, store);
    }

    private void recordFailure(ModelExecution execution, InvocationRequest<GenerationRequest> request, Throwable failure, ExecutionStore store) {
        var existing = store.find(execution.scope(), execution.executionId()).orElseThrow();
        if (existing.status() != ExecutionStatus.ACCEPTED && existing.status() != ExecutionStatus.RUNNING) return;
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        ExecutionError error = failure instanceof BaseException known ? known.error()
                : (existing.dispatched() ? ExecutionFailures.afterStart(CommonErrorCode.OUTCOME_UNKNOWN, request.context(), "model", failure)
                : ExecutionFailures.beforeStart(CommonErrorCode.INTERNAL_ERROR, request.context(), "model")).error();
        if (existing.dispatched())
            error = new ExecutionError(error.code(), error.failureStage(), false, SideEffectStatus.UNKNOWN, ResultCertainty.UNKNOWN, error.correlationId());
        else
            error = new ExecutionError(error.code(), error.failureStage(), error.retryable(), SideEffectStatus.NONE, ResultCertainty.CONFIRMED, error.correlationId());
        var status = existing.dispatched() ? ExecutionStatus.OUTCOME_UNKNOWN
                : CommonErrorCode.DEADLINE_EXCEEDED.code().equals(error.code()) ? ExecutionStatus.TIMED_OUT
                : CommonErrorCode.INTERRUPTED.code().equals(error.code()) ? ExecutionStatus.CANCELLED : ExecutionStatus.FAILED;
        store.finish(execution.scope(), execution.executionId(), status, null, error);
        telemetry.increment("arte.ai.execution.finished", 1, Map.of(Telemetry.Label.OUTCOME, status.name()));
    }

    /**
     * 查询与控制使用本次已认证的新上下文；不复用原调用的已过期期限。
     */
    public ModelExecution find(ExecutionContext viewer, String executionId) {
        var execution = store.find(viewer.scope(), executionId).orElseThrow(() -> ExecutionFailures.beforeStart(CommonErrorCode.NOT_FOUND, viewer, "query"));
        access.requireAllowed(viewer, modelBindingResolver.resolve(viewer, execution.capabilityRef(), execution.bindingRef()));
        return execution;
    }

    /**
     * 批量读取仍检查当前权限；授权去重只限本次调用，不跨请求缓存。
     */
    public Map<String, ModelExecution> findAll(ExecutionContext viewer, List<String> ids) {
        if (ids.size() > 256) throw new IllegalArgumentException("execution batch exceeds limit");
        ids.forEach(id -> ContractChecks.identifier(id, "executionId"));
        var result = new HashMap<String, ModelExecution>();
        var requested = Set.copyOf(ids);
        var authorized = new HashSet<List<com.arte.ai.model.definition.DefinitionRef>>();
        for (var execution : store.findAll(viewer.scope(), ids)) {
            if (!viewer.scope().equals(execution.scope()) || !requested.contains(execution.executionId()))
                throw ExecutionFailures.beforeStart(CommonErrorCode.NOT_FOUND, viewer, "query");
            if (authorized.add(List.of(execution.capabilityRef(), execution.bindingRef())))
                access.requireAllowed(viewer, modelBindingResolver.resolve(viewer, execution.capabilityRef(), execution.bindingRef()));
            result.put(execution.executionId(), execution);
        }
        if (!result.keySet().containsAll(ids))
            throw ExecutionFailures.beforeStart(CommonErrorCode.NOT_FOUND, viewer, "query");
        return Map.copyOf(result);
    }

    /**
     * 按服务端派生键恢复可靠受理关联，使用当前权限查询已有执行，不重放模型请求。
     */
    public java.util.Optional<ModelExecution> findIdempotent(ExecutionContext viewer, String key) {
        ContractChecks.identifier(key, "idempotencyKey");
        return store.findIdempotent(viewer.scope(), key).map(execution -> find(viewer, execution.executionId()));
    }

    public List<ExecutionEvent<ModelEvent>> events(ExecutionContext viewer, String executionId, long after, int limit) {
        find(viewer, executionId);
        var batch = events.read(viewer.scope(), executionId, after, limit);
        find(viewer, executionId);
        return batch;
    }

    public CancellationStatus cancel(ExecutionContext viewer, String executionId) {
        var execution = find(viewer, executionId);
        if (workQueue != null) return workQueue.requestCancellation(viewer.scope(), executionId);
        var task = live.get(executionId);
        if (task != null) return task.requestCancellation();
        return execution.status() == ExecutionStatus.ACCEPTED || execution.status() == ExecutionStatus.RUNNING
                ? CancellationStatus.UNCONFIRMED : CancellationStatus.ALREADY_COMPLETED;
    }

    private static BaseException fail(InvocationRequest<?> request, CommonErrorCode code, String stage) {
        return ExecutionFailures.beforeStart(code, request.context(), stage);
    }

    public static String fingerprint(PreparedModelCall prepared, ExecutionOptions options) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bytes);
            out.writeUTF("arte.model.invocation.v1");
            for (var ref : List.of(prepared.plan().capability().descriptor().ref(), prepared.plan().binding().ref(), prepared.plan().connection().ref())) {
                out.writeUTF(ref.definitionType());
                out.writeUTF(ref.definitionId());
                out.writeUTF(ref.version());
            }
            out.writeUTF(prepared.contentDigest());
            out.writeUTF(options.timeout().toString());
            out.writeBoolean(options.streaming());
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.security.NoSuchAlgorithmException | java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
