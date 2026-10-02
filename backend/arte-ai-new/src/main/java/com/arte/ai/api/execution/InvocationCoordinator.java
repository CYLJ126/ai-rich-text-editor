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
import com.arte.base.validation.ContractChecks;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 单次非流式模型协调：可靠受理、一个尝试、显式费用／输出状态，不自动重试未知结果。
 */
public class InvocationCoordinator {
    private final ModelBindingResolver resolver;
    private final ModelGateway gateway;
    private final ModelAccessPolicy access;
    private final EgressPolicy egress;
    private final AdmissionController admission;
    private final TaskExecutor tasks;
    private final ExecutionStore store;
    private final ExecutionEventStore events;
    private final BudgetService budgets;
    private final AuditSink audit;
    private final Clock clock;
    private final ConcurrentMap<String, TaskHandle<?>> live = new ConcurrentHashMap<>();

    public InvocationCoordinator(ModelBindingResolver resolver, ModelGateway gateway, ModelAccessPolicy access,
                                 EgressPolicy egress, AdmissionController admission, TaskExecutor tasks, ExecutionStore store,
                                 ExecutionEventStore events, BudgetService budgets, AuditSink audit, Clock clock) {
        this.resolver = resolver;
        this.gateway = gateway;
        this.access = access;
        this.egress = egress;
        this.admission = admission;
        this.tasks = tasks;
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
        var plan = resolver.resolve(request);
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
        var waiting = admission.acquire(new AdmissionRequest(request.context(),
                new AdmissionKey(request.context().scope().tenantId(), "ai.interactive", null, null), AdmissionPriority.INTERACTIVE, Duration.ZERO));
        AdmissionPermit permit;
        try {
            permit = waiting.completion().toCompletableFuture().join();
        } catch (CompletionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) throw cause;
            throw failed;
        }
        boolean handedOff = false;
        try {
            String digest = requestDigest;
            var submission = new ModelSubmission(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    prepared.plan(), request.context(), idempotencyKey, digest);
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
                    task = tasks.submit(boundedContext, checkpoint -> run(execution, request, prepared, consent, checkpoint));
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
        var context = request.context();
        String id = execution.executionId();
        if (!store.start(context.scope(), id)) throw fail(request, CommonErrorCode.VERSION_CONFLICT, "attempt");
        try {
            checkpoint.check();
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
            var result = gateway.generate(current, checkpoint);
            checkpoint.check();
            access.requireAllowed(context, current.plan());
            store.finish(context.scope(), id, ExecutionStatus.SUCCEEDED, result, null);
            return result;
        } catch (Exception failure) {
            recordFailure(execution, request, failure);
            throw failure;
        }
    }

    private void recordFailure(ModelExecution execution, InvocationRequest<GenerationRequest> request, Throwable failure) {
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
    }

    /**
     * 查询与控制使用本次已认证的新上下文；不复用原调用的已过期期限。
     */
    public ModelExecution find(ExecutionContext viewer, String executionId) {
        var execution = store.find(viewer.scope(), executionId).orElseThrow(() -> ExecutionFailures.beforeStart(CommonErrorCode.NOT_FOUND, viewer, "query"));
        access.requireAllowed(viewer, resolver.resolve(viewer, execution.capabilityRef(), execution.bindingRef()));
        return execution;
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
