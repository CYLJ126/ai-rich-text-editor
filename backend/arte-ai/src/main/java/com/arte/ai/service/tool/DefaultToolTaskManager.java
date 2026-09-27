package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.common.enums.tool.CategoryEnum;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.ToolCallMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.cluster.DistributedToolCancellationCoordinator;
import com.arte.ai.service.tool.observability.ToolEventRecorder;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 数据库任务 + Worker 租约的延迟工具任务管理器。
 *
 * <p>数据库领取语句是任务归属的最终依据，定时恢复扫描允许节点宕机后由其他节点接管。
 * 执行语义为至少一次；有副作用的工具必须使用调用上下文中的幂等键保护外部副作用。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class DefaultToolTaskManager implements ToolTaskManager {

    private static final String META_ATTRIBUTES = "contextAttributes";
    private static final String META_WORKFLOW_RUN = "workflowRunId";
    private static final String META_AGENT_RUN = "agentRunId";
    private static final String META_SPAN_ID = "spanId";
    private static final String META_APPROVAL = "approvalRequestId";
    private static final String META_ROLES = "principalRoles";
    private static final String META_SCOPES = "principalScopes";

    private final ToolTaskRepository repository;
    private final ToolCallMapper callMapper;
    private final ToolRegistry registry;
    private final ToolBindingManager bindingManager;
    private final ToolExecutor toolExecutor;
    private final ToolExecutionProperties properties;
    private final ToolClusterIdentity identity;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ToolApprovalService> approvalServiceProvider;
    private final ToolEventRecorder events;
    private final ToolDataSanitizer sanitizer;
    private final DistributedToolCancellationCoordinator cancellations;
    private final TransactionTemplate transactionTemplate;
    private final Executor executor;
    private final ScheduledExecutorService leaseScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("tool-task-lease-", 0).factory());

    public DefaultToolTaskManager(ToolTaskRepository repository, ToolCallMapper callMapper,
                                  ToolRegistry registry, ToolBindingManager bindingManager,
                                  ToolExecutor toolExecutor, ToolExecutionProperties properties,
                                  ToolClusterIdentity identity, ObjectMapper objectMapper,
                                  ObjectProvider<ToolApprovalService> approvalServiceProvider,
                                  ToolEventRecorder events, ToolDataSanitizer sanitizer,
                                  DistributedToolCancellationCoordinator cancellations,
                                  TransactionTemplate transactionTemplate,
                                  @Qualifier("toolCallbackExecutor") Executor executor) {
        this.repository = repository;
        this.callMapper = callMapper;
        this.registry = registry;
        this.bindingManager = bindingManager;
        this.toolExecutor = toolExecutor;
        this.properties = properties;
        this.identity = identity;
        this.objectMapper = objectMapper;
        this.approvalServiceProvider = approvalServiceProvider;
        this.events = events;
        this.sanitizer = sanitizer;
        this.cancellations = cancellations;
        this.transactionTemplate = transactionTemplate;
        this.executor = executor;
    }

    @Override
    public <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolTaskHandle> submit(
            Tool<I, O> tool, ToolInvocation<I> invocation) {
        ToolTask task = newTask(tool, invocation, Map.of());
        repository.saveTask(task);
        dispatch(task.taskId());
        return CompletableFuture.completedFuture(task.snapshot());
    }

    @Override
    public <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolTaskHandle> suspend(
            Tool<I, O> tool, ToolInvocation<I> invocation, String approvalRequestId) {
        String rawToken = UUID.randomUUID().toString() + UUID.randomUUID();
        ToolTask task = newTask(tool, invocation, Map.of(META_APPROVAL, approvalRequestId));
        task.queueForApproval(hash(rawToken), Instant.now());
        ToolApprovalService approvalService = approvalServiceProvider.getIfAvailable();
        if (approvalService == null) {
            throw new IllegalStateException("tool approval service is unavailable");
        }
        transactionTemplate.executeWithoutResult(status -> {
            repository.saveTask(task);
            approvalService.attachTask(approvalRequestId, task.taskId());
        });
        ToolTaskHandle stored = task.snapshot();
        ToolTaskHandle exposed = new ToolTaskHandle(stored.taskId(), stored.callId(), stored.tool(),
                stored.status(), stored.progress(), stored.progressMessage(), rawToken,
                stored.createdAt(), stored.updatedAt(), stored.version(), stored.metadata());
        return CompletableFuture.completedFuture(exposed);
    }

    @Override
    public CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId) {
        return CompletableFuture.completedFuture(repository.findTask(taskId).map(this::publicSnapshot));
    }

    @Override
    public CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findResult(String taskId) {
        return CompletableFuture.completedFuture(repository.findResult(taskId));
    }

    @Override
    public CompletionStage<Boolean> cancel(String taskId) {
        return CompletableFuture.supplyAsync(() -> repository.findTask(taskId).map(task -> {
            if (task.terminal()) {
                return false;
            }
            long expected = task.version();
            task.cancel("cancelled by caller", Instant.now());
            boolean saved = repository.saveState(task, expected);
            if (saved) {
                cancellations.cancel(task.callId());
                ToolResult.Unsuccessful<DynamicToolResponse> result = new ToolResult.Unsuccessful<>(
                        ToolResultStatusEnum.CANCELLED,
                        new ToolError("TOOL_TASK_CANCELLED", CategoryEnum.CANCELLED,
                                "cancelled by caller", false, Map.of()), null,
                        Map.of("taskId", task.taskId()));
                saveResultOnce(task.taskId(), result);
                completeCall(task.callId(), result);
                events.record(ToolExecutionEvent.Type.CANCELLED, task.callId(), task.tool(),
                        context(task, Map.of()), Map.of("taskId", task.taskId()));
                cancellations.clear(task.callId());
            }
            return saved;
        }).orElse(false), executor);
    }

    @Override
    public CompletionStage<Boolean> resume(String resumeToken) {
        return resume(resumeToken, null);
    }

    @Override
    public CompletionStage<Boolean> resume(String resumeToken, String ownerId) {
        return CompletableFuture.supplyAsync(() -> {
            ToolTask task = repository.findByResumeTokenHash(hash(requireText(resumeToken, "resumeToken")))
                    .orElse(null);
            if (task == null || (ownerId != null && !ownerId.equals(task.ownerId()))) {
                return false;
            }
            Object approvalId = task.metadata().get(META_APPROVAL);
            if (approvalId != null) {
                ToolApprovalService service = approvalServiceProvider.getIfAvailable();
                if (service == null || service.findDecision(String.valueOf(approvalId))
                        .filter(ToolApprovalDecision::approved).isEmpty()) {
                    return false;
                }
            }
            long expected = task.version();
            task.resume(Instant.now());
            if (!repository.saveState(task, expected)) {
                return false;
            }
            events.record(ToolExecutionEvent.Type.RESUMED, task.callId(), task.tool(),
                    context(task, Map.of()), Map.of("taskId", task.taskId()));
            dispatch(task.taskId());
            return true;
        }, executor);
    }

    @Override
    public CompletionStage<Boolean> updateProgress(String taskId, double progress, String message) {
        return CompletableFuture.supplyAsync(() -> repository.findTask(taskId).map(task -> {
            long expected = task.version();
            task.updateProgress(progress, message, Instant.now());
            boolean saved = repository.saveState(task, expected);
            if (saved) {
                events.record(ToolExecutionEvent.Type.TASK_PROGRESS_CHANGED, task.callId(), task.tool(),
                        context(task, Map.of()), Map.of("taskId", taskId, "progress", progress));
            }
            return saved;
        }).orElse(false), executor);
    }

    @Override
    public CompletionStage<Boolean> resumeApproved(String taskId) {
        return CompletableFuture.supplyAsync(() -> resumeTask(taskId), executor);
    }

    @Override
    public CompletionStage<Boolean> terminateApproval(String taskId, String reason) {
        String terminalReason = reason == null || reason.isBlank()
                ? "approval terminated" : reason;
        return CompletableFuture.supplyAsync(() -> Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            ToolTask task = repository.findTask(taskId).orElse(null);
            if (task == null || task.terminal()
                    || task.status() != ToolTaskHandle.Status.WAITING_APPROVAL) {
                return false;
            }
            long expected = task.version();
            task.fail(terminalReason, Instant.now());
            if (!repository.saveState(task, expected)) {
                return false;
            }
            boolean expired = terminalReason.toLowerCase(Locale.ROOT).contains("expired");
            ToolResult.Unsuccessful<DynamicToolResponse> result = new ToolResult.Unsuccessful<>(
                    ToolResultStatusEnum.DENIED,
                    new ToolError(expired ? "TOOL_APPROVAL_EXPIRED" : "TOOL_APPROVAL_REJECTED",
                            CategoryEnum.APPROVAL,
                            terminalReason, false, Map.of()), null, Map.of("taskId", taskId));
            saveResultOnce(taskId, result);
            completeCall(task.callId(), result);
            ToolExecutionEvent.Type approvalType = expired
                    ? ToolExecutionEvent.Type.APPROVAL_EXPIRED
                    : ToolExecutionEvent.Type.APPROVAL_REJECTED;
            events.record(approvalType, task.callId(), task.tool(), context(task, Map.of()),
                    Map.of("taskId", taskId, "reason", terminalReason));
            events.record(ToolExecutionEvent.Type.DENIED, task.callId(), task.tool(),
                    context(task, Map.of()), Map.of("taskId", taskId, "reason", terminalReason));
            cancellations.clear(task.callId());
            return true;
        })), executor);
    }

    @Scheduled(initialDelayString = "${arte.ai.tool.execution.recovery-initial-delay:5s}",
            fixedDelayString = "${arte.ai.tool.execution.recovery-interval:10s}")
    public void recoverTasks() {
        repository.findRecoverable(properties.getRecoveryBatchSize())
                .forEach(task -> dispatch(task.taskId()));
    }

    private void dispatch(String taskId) {
        CompletableFuture.runAsync(() -> executeClaimed(taskId), executor);
    }

    private boolean resumeTask(String taskId) {
        ToolTask task = repository.findTask(taskId).orElse(null);
        if (task == null || task.status() != ToolTaskHandle.Status.WAITING_APPROVAL) {
            return false;
        }
        long expected = task.version();
        task.resume(Instant.now());
        if (!repository.saveState(task, expected)) {
            return false;
        }
        events.record(ToolExecutionEvent.Type.APPROVAL_APPROVED, task.callId(), task.tool(),
                context(task, Map.of()), Map.of("taskId", task.taskId()));
        events.record(ToolExecutionEvent.Type.RESUMED, task.callId(), task.tool(),
                context(task, Map.of()), Map.of("taskId", task.taskId(), "approved", true));
        dispatch(task.taskId());
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeClaimed(String taskId) {
        Instant leaseUntil = Instant.now().plus(properties.getWorkerLease());
        if (!repository.tryClaim(taskId, identity.instanceId(), leaseUntil)) {
            return;
        }
        ToolTask task = repository.findTask(taskId).orElseThrow();
        ScheduledFuture<?> renewal = leaseScheduler.scheduleAtFixedRate(
                () -> repository.renewClaim(taskId, identity.instanceId(),
                        Instant.now().plus(properties.getWorkerLease())),
                properties.getLeaseRenewInterval().toMillis(),
                properties.getLeaseRenewInterval().toMillis(), TimeUnit.MILLISECONDS);
        try {
            Tool tool = registry.resolve(task.tool()).orElseThrow(
                    () -> new IllegalStateException("tool is no longer available: " + task.tool()));
            ToolRequest request = toRequest(tool, task.arguments());
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put(DefaultToolExecutor.DEFERRED_WORKER_ATTRIBUTE, true);
            if (task.metadata().get(META_APPROVAL) != null) {
                extra.put(DefaultToolExecutor.APPROVAL_REQUEST_ATTRIBUTE,
                        task.metadata().get(META_APPROVAL));
            }
            ToolExecutionPolicy taskPolicy = task.executionPolicy();
            ToolExecutionPolicy workerPolicy = new ToolExecutionPolicy(taskPolicy.executionMode(),
                    taskPolicy.timeout(), 0, taskPolicy.retryBackoff(), taskPolicy.maxOutputTokens(),
                    taskPolicy.requiresApproval(), taskPolicy.allowsResultCache());
            ToolInvocation invocation = new ToolInvocation(task.callId(), task.tool(), request,
                    context(task, extra), workerPolicy);
            ToolResult result = (ToolResult) toolExecutor.execute(tool, invocation)
                    .toCompletableFuture().get();
            finish(taskId, result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failOrRetry(taskId, "worker interrupted", true);
        } catch (SecurityException exception) {
            finish(taskId, new ToolResult.Unsuccessful<>(ToolResultStatusEnum.DENIED,
                    new ToolError("TOOL_UNAUTHORIZED", CategoryEnum.AUTHORIZATION,
                            rootMessage(exception), false, Map.of()), null, Map.of()));
        } catch (Exception exception) {
            failOrRetry(taskId, rootMessage(exception), true);
        } finally {
            renewal.cancel(false);
            cancellations.clear(task.callId());
        }
    }

    private void finish(String taskId, ToolResult<? extends ToolResponse> result) {
        ToolTask task = repository.findTask(taskId).orElseThrow();
        if (task.terminal()) {
            return;
        }
        if (result instanceof ToolResult.Succeeded<?>) {
            repository.saveResult(taskId, result);
            long expected = task.version();
            task.succeed(Instant.now());
            if (repository.saveState(task, expected)) {
                completeCall(task.callId(), result);
                events.record(ToolExecutionEvent.Type.SUCCEEDED, task.callId(), task.tool(),
                        context(task, Map.of()), Map.of("taskId", taskId));
            }
        } else if (result instanceof ToolResult.Unsuccessful<?> unsuccessful) {
            if (unsuccessful.error().retryable()) {
                failOrRetry(taskId, unsuccessful.error().message(), true);
                return;
            }
            repository.saveResult(taskId, result);
            long expected = task.version();
            if (unsuccessful.status() == ToolResultStatusEnum.CANCELLED) {
                task.cancel(unsuccessful.error().message(), Instant.now());
            } else if (unsuccessful.status() == ToolResultStatusEnum.TIMED_OUT) {
                task.timeout(unsuccessful.error().message(), Instant.now());
            } else {
                task.fail(unsuccessful.error().message(), Instant.now());
            }
            if (repository.saveState(task, expected)) {
                completeCall(task.callId(), result);
            }
        }
    }

    private void failOrRetry(String taskId, String message, boolean retryable) {
        ToolTask task = repository.findTask(taskId).orElse(null);
        if (task == null || task.terminal()) {
            return;
        }
        long expected = task.version();
        if (retryable && task.attempt() <= task.executionPolicy().maxRetries()
                && registry.resolve(task.tool()).map(tool -> tool.getDefinition().riskProfile().idempotent())
                .orElse(false)) {
            task.scheduleRetry(Instant.now().plus(task.executionPolicy().retryBackoff()), message, Instant.now());
            if (repository.saveState(task, expected)) {
                events.record(ToolExecutionEvent.Type.RETRIED, task.callId(), task.tool(),
                        context(task, Map.of()), Map.of("taskId", taskId, "attempt", task.attempt()));
            }
            return;
        }
        ToolResult.Unsuccessful<DynamicToolResponse> failed = new ToolResult.Unsuccessful<>(
                ToolResultStatusEnum.FAILED,
                new ToolError("TOOL_TASK_FAILED", CategoryEnum.INTERNAL, message, retryable, Map.of()),
                null, Map.of("taskId", taskId));
        saveResultOnce(taskId, failed);
        task.fail(message, Instant.now());
        if (repository.saveState(task, expected)) {
            completeCall(task.callId(), failed);
            events.record(ToolExecutionEvent.Type.FAILED, task.callId(), task.tool(),
                    context(task, Map.of()), Map.of("taskId", taskId));
        }
    }

    private void saveResultOnce(String taskId, ToolResult<? extends ToolResponse> result) {
        if (repository.findResult(taskId).isEmpty()) {
            repository.saveResult(taskId, result);
        }
    }

    private void completeCall(String callId, ToolResult<? extends ToolResponse> result) {
        callMapper.selectByCallId(callId).ifPresent(call -> {
            String code = null;
            String category = null;
            String message = null;
            if (result instanceof ToolResult.Unsuccessful<?> failed) {
                code = failed.error().code();
                category = failed.error().category().name().toLowerCase(Locale.ROOT);
                message = failed.error().message();
            }
            long latency = call.getStartedAt() == null ? 0L
                    : java.time.Duration.between(call.getStartedAt(), java.time.LocalDateTime.now()).toMillis();
            ToolUsage usage = usage(result);
            int inputTokens = usage == null || usage.inputTokens() == null ? 0
                    : Math.toIntExact(usage.inputTokens());
            int outputTokens = usage == null || usage.outputTokens() == null ? 0
                    : Math.toIntExact(usage.outputTokens());
            callMapper.complete(callId, result.status().getValue(), java.time.LocalDateTime.now(),
                    latency, code, category, message,
                    inputTokens, outputTokens, inputTokens + outputTokens,
                    call.getRowVersion());
        });
    }

    private ToolUsage usage(ToolResult<?> result) {
        if (result instanceof ToolResult.Succeeded<?> succeeded) return succeeded.usage();
        if (result instanceof ToolResult.Unsuccessful<?> unsuccessful) return unsuccessful.usage();
        return null;
    }

    private <I extends ToolRequest, O extends ToolResponse> ToolTask newTask(
            Tool<I, O> tool, ToolInvocation<I> invocation, Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
        metadata.put(META_ATTRIBUTES, sanitizer.sanitize(invocation.context().attributes()));
        metadata.put(META_ROLES, invocation.context().principal().roles());
        metadata.put(META_SCOPES, invocation.context().principal().scopes());
        put(metadata, META_WORKFLOW_RUN, invocation.context().workflowRunId());
        put(metadata, META_AGENT_RUN, invocation.context().agentRunId());
        put(metadata, META_SPAN_ID, invocation.context().parentSpanId());
        Map<String, Object> arguments = invocation.request() instanceof DynamicToolRequest dynamic
                ? dynamic.arguments() : objectMapper.convertValue(invocation.request(), Map.class);
        return ToolTask.enqueue(UUID.randomUUID().toString(), invocation.callId(), invocation.tool(),
                arguments, invocation.effectivePolicy(), invocation.context().principal().ownerId(),
                invocation.context().principal().subjectId(), invocation.context().traceId(),
                invocation.context().idempotencyKey(), invocation.context().credentialBindingId(),
                metadata, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionContext context(ToolTask task, Map<String, Object> extra) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        Object stored = task.metadata().get(META_ATTRIBUTES);
        if (stored instanceof Map<?, ?> map) {
            attributes.putAll((Map<String, Object>) map);
        }
        attributes.putAll(extra);
        if (task.credentialBindingId() != null) {
            String workspaceId = string(attributes.get(DefaultToolGateway.ATTR_WORKSPACE_ID));
            bindingManager.resolve(task.ownerId(), workspaceId, task.credentialBindingId())
                    .ifPresentOrElse(binding -> {
                        attributes.put(DefaultToolGateway.ATTR_EFFECTIVE_CONFIGURATION,
                                binding.effectiveConfiguration());
                        if (binding.credentialReference() != null
                                && !binding.credentialReference().isBlank()) {
                            attributes.put(DefaultToolGateway.ATTR_CREDENTIAL_REFERENCE,
                                    binding.credentialReference());
                        }
                    }, () -> {
                        throw new SecurityException("tool binding is unavailable or has been revoked");
                    });
        }
        return new ToolExecutionContext(task.callId(), string(task.metadata().get(META_WORKFLOW_RUN)),
                string(task.metadata().get(META_AGENT_RUN)), task.traceId(),
                string(task.metadata().get(META_SPAN_ID)),
                new ToolPrincipal(task.ownerId(), task.subjectId(),
                        stringSet(task.metadata().get(META_ROLES)),
                        stringSet(task.metadata().get(META_SCOPES))),
                Instant.now().plus(task.executionPolicy().timeout()),
                cancellations.token(task.callId(), new RepositoryCancellation(task.taskId())),
                task.idempotencyKey(), task.credentialBindingId(), attributes);
    }

    private ToolRequest toRequest(Tool<?, ?> tool, Map<String, Object> arguments) {
        return tool.getRequestType() == DynamicToolRequest.class
                ? new DynamicToolRequest(arguments)
                : objectMapper.convertValue(arguments, tool.getRequestType());
    }

    private ToolTaskHandle publicSnapshot(ToolTask task) {
        ToolTaskHandle value = task.snapshot();
        return new ToolTaskHandle(value.taskId(), value.callId(), value.tool(), value.status(),
                value.progress(), value.progressMessage(), null, value.createdAt(), value.updatedAt(),
                value.version(), value.metadata());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Set<String> stringSet(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        collection.forEach(item -> result.add(String.valueOf(item)));
        return Set.copyOf(result);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    @PreDestroy
    public void close() {
        leaseScheduler.close();
    }

    private final class RepositoryCancellation implements ToolCancellation {
        private final String taskId;

        private RepositoryCancellation(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public boolean isCancellationRequested() {
            return repository.findTask(taskId).map(task -> task.status() == ToolTaskHandle.Status.CANCELLED)
                    .orElse(true);
        }

        @Override
        public void onCancellation(Runnable callback) {
            // 持久化取消采用协作式轮询，工具可主动调用 isCancellationRequested。
        }
    }
}
