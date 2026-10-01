package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.common.enums.tool.CategoryEnum;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.config.ToolExecutionProperties;
import com.arte.ai.mapper.tool.ToolCallMapper;
import com.arte.ai.mapper.tool.ToolCallResultMapper;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.pojo.tool.po.ToolCallPo;
import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import com.arte.ai.service.tool.cluster.DistributedToolCancellationCoordinator;
import com.arte.ai.service.tool.cluster.ToolDistributedLockExecutor;
import com.arte.ai.service.tool.observability.ToolEventRecorder;
import com.arte.ai.service.tool.security.ToolDataSanitizer;
import com.arte.ai.service.tool.security.ToolInputLimitValidator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * REST、Agent、Workflow 和 MCP 共用的工具调用入口。
 *
 * <p>网关只接受精确版本引用。它依次完成请求规范化、工具解析、绑定检查、输入 Schema
 * 校验和调用记录创建，再交给唯一执行管道。数据库唯一键是幂等性的最终保障，Redisson
 * 锁用于减少同一幂等键在多节点上的竞争。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class DefaultToolGateway implements ToolGateway {

    public static final String ATTR_BINDING_ID = "tool.binding-id";
    public static final String ATTR_WORKSPACE_ID = "tool.workspace-id";
    public static final String ATTR_SOURCE_TYPE = "tool.source-type";
    public static final String ATTR_SOURCE_ID = "tool.source-id";
    public static final String ATTR_EFFECTIVE_CONFIGURATION = "tool.effective-configuration";
    public static final String ATTR_CREDENTIAL_REFERENCE = "tool.credential-reference";

    private final ToolRegistry registry;
    private final ToolLifecycleManager lifecycleManager;
    private final ToolBindingManager bindingManager;
    private final ToolPolicyMerger policyMerger;
    private final ToolSchemaValidator schemaValidator;
    private final ToolExecutor toolExecutor;
    private final ToolTaskManager taskManager;
    private final ToolTaskRepository taskRepository;
    private final ToolCallMapper callMapper;
    private final ToolCallResultMapper resultMapper;
    private final ToolDistributedLockExecutor lockExecutor;
    private final ToolExecutionProperties properties;
    private final ToolEventRecorder events;
    private final ObjectMapper objectMapper;
    private final ToolDataSanitizer sanitizer;
    private final ToolInputLimitValidator inputLimitValidator;
    private final DistributedToolCancellationCoordinator cancellations;
    private final Executor executor;

    public DefaultToolGateway(ToolRegistry registry, ToolLifecycleManager lifecycleManager,
                              ToolBindingManager bindingManager,
                              ToolPolicyMerger policyMerger, ToolSchemaValidator schemaValidator,
                              ToolExecutor toolExecutor, ToolTaskManager taskManager,
                              ToolTaskRepository taskRepository, ToolCallMapper callMapper,
                              ToolCallResultMapper resultMapper,
                              ToolDistributedLockExecutor lockExecutor,
                              ToolExecutionProperties properties, ToolEventRecorder events,
                              ObjectMapper objectMapper, ToolDataSanitizer sanitizer,
                              ToolInputLimitValidator inputLimitValidator,
                              DistributedToolCancellationCoordinator cancellations,
                              @Qualifier("toolCallbackExecutor") Executor executor) {
        this.registry = registry;
        this.lifecycleManager = lifecycleManager;
        this.bindingManager = bindingManager;
        this.policyMerger = policyMerger;
        this.schemaValidator = schemaValidator;
        this.toolExecutor = toolExecutor;
        this.taskManager = taskManager;
        this.taskRepository = taskRepository;
        this.callMapper = callMapper;
        this.resultMapper = resultMapper;
        this.lockExecutor = lockExecutor;
        this.properties = properties;
        this.events = events;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.inputLimitValidator = inputLimitValidator;
        this.cancellations = cancellations;
        this.executor = executor;
    }

    @Override
    public CompletionStage<ToolResult<? extends ToolResponse>> invoke(ToolCallRequest request) {
        Objects.requireNonNull(request, "request");
        ToolCallRequest normalized = withSpan(request);
        try {
            String ownerId = normalized.context().principal().ownerId().trim();
            String idempotencyKey = normalize(normalized.context().idempotencyKey());
            PreparedCall prepared = idempotencyKey == null
                    ? prepare(normalized)
                    : lockExecutor.execute("invoke:" + ownerId + ":" + idempotencyKey,
                    () -> existing(ownerId, idempotencyKey).orElseGet(() -> prepare(normalized)));
            if (prepared.existingResult() != null) {
                return CompletableFuture.completedFuture(prepared.existingResult());
            }
            return execute(prepared);
        } catch (RuntimeException exception) {
            ToolResult.Unsuccessful<DynamicToolResponse> failure = gatewayFailure(exception,
                    normalized.tool());
            events.record(ToolExecutionEvent.Type.FAILED, normalized.callId(), normalized.tool(),
                    normalized.context(), Map.of("code", failure.error().code()));
            return CompletableFuture.completedFuture(failure);
        }
    }

    @Override
    public CompletionStage<ToolResult<? extends ToolResponse>> resume(String resumeToken) {
        return resumeResult(taskManager.resume(resumeToken));
    }

    @Override
    public CompletionStage<ToolResult<? extends ToolResponse>> resume(String resumeToken, String ownerId) {
        return resumeResult(taskManager.resume(resumeToken, ownerId));
    }

    private CompletionStage<ToolResult<? extends ToolResponse>> resumeResult(
            CompletionStage<Boolean> resumeStage) {
        return resumeStage.thenApply(resumed -> {
            if (!resumed) {
                return failure("TOOL_RESUME_REJECTED", CategoryEnum.CONFLICT,
                        "resume token is invalid, undecided or already used");
            }
            return new ToolResult.Succeeded<>(new DynamicToolResponse(Boolean.TRUE, "resumed"),
                    List.of(), List.of(), null, Map.of("resumed", true));
        });
    }

    @Override
    public CompletionStage<Boolean> cancel(String callId) {
        return taskRepository.findByCallId(callId)
                .map(task -> taskManager.cancel(task.taskId()))
                .orElseGet(() -> CompletableFuture.completedFuture(callMapper.selectByCallId(callId)
                        .map(call -> cancellations.cancel(callId)).orElse(false)));
    }

    @Override
    public CompletionStage<Boolean> cancel(String callId, String ownerId) {
        return taskRepository.findByCallId(callId)
                .filter(task -> task.ownerId().equals(ownerId))
                .map(task -> taskManager.cancel(task.taskId()))
                .orElseGet(() -> CompletableFuture.completedFuture(callMapper.selectOwned(callId, ownerId)
                        .map(call -> cancellations.cancel(callId)).orElse(false)));
    }

    @Override
    public CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId) {
        return taskManager.findTask(taskId);
    }

    @Override
    public CompletionStage<Optional<ToolTaskHandle>> findTask(String taskId, String ownerId) {
        if (taskRepository.findTask(taskId).filter(task -> task.ownerId().equals(ownerId)).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return taskManager.findTask(taskId);
    }

    @Override
    public CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findTaskResult(String taskId) {
        return taskManager.findResult(taskId);
    }

    @Override
    public CompletionStage<Optional<ToolResult<? extends ToolResponse>>> findTaskResult(
            String taskId, String ownerId) {
        if (taskRepository.findTask(taskId).filter(task -> task.ownerId().equals(ownerId)).isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return taskManager.findResult(taskId);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompletionStage<ToolResult<? extends ToolResponse>> execute(PreparedCall prepared) {
        Instant started = Instant.now();
        CompletionStage<ToolResult<? extends ToolResponse>> stage = (CompletionStage) toolExecutor.execute(
                (Tool) prepared.tool(), (ToolInvocation) prepared.invocation());
        return stage
                .handleAsync((result, throwable) -> {
                    ToolResult<? extends ToolResponse> finalResult = throwable == null
                            ? result : failure("TOOL_PIPELINE_FAILED", CategoryEnum.INTERNAL,
                            rootMessage(throwable));
                    persistResult(prepared.call(), finalResult, started);
                    recordTerminal(prepared.invocation(), finalResult);
                    if (!(finalResult instanceof ToolResult.Accepted<?> || finalResult
                            instanceof ToolResult.Suspended<?>)) {
                        cancellations.clear(prepared.call().getCallId());
                    }
                    return finalResult;
                }, executor);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private PreparedCall prepare(ToolCallRequest source) {
        ToolCallRequest request = normalize(source);
        inputLimitValidator.validate(request.arguments());
        Tool tool = registry.resolve(request.tool())
                .orElseThrow(() -> new ToolResolutionException(request.tool()));
        events.record(ToolExecutionEvent.Type.RESOLVED, request.callId(), request.tool(),
                request.context(), Map.of());

        String bindingId = string(request.context().attributes().get(ATTR_BINDING_ID));
        String workspaceId = string(request.context().attributes().get(ATTR_WORKSPACE_ID));
        ResolvedToolBinding binding = null;
        if (bindingId != null) {
            binding = bindingManager.resolveRequested(request.context().principal().ownerId(), workspaceId, bindingId, request.tool())
                    .orElseThrow(() -> new SecurityException("tool binding is unavailable"));
            if (!binding.tool().equals(request.tool())) {
                throw new SecurityException("binding does not grant the requested tool version");
            }
        } else if (properties.isBindingRequired()) {
            throw new SecurityException("a user/workspace tool binding is required");
        }

        ToolExecutionPolicy basePolicy = binding == null
                ? tool.getDefinition().defaultPolicy() : binding.effectivePolicy();
        ToolExecutionPolicy effectivePolicy = request.policyOverride() == null ? basePolicy
                : withExecutionMode(policyMerger.tighten(basePolicy,
                        withBaseMode(request.policyOverride(), basePolicy.executionMode())),
                request.policyOverride().executionMode());
        if (!tool.getDefinition().capabilities().executionModes().contains(effectivePolicy.executionMode())) {
            throw new IllegalArgumentException("tool does not support execution mode "
                    + effectivePolicy.executionMode());
        }
        schemaValidator.validate(tool.getDefinition().inputSchema(), request.arguments(), "tool arguments");
        ToolRequest typedRequest = tool.getRequestType() == DynamicToolRequest.class
                ? new DynamicToolRequest(request.arguments())
                : (ToolRequest) objectMapper.convertValue(request.arguments(), tool.getRequestType());
        ToolExecutionContext executionContext = enrichContext(request.context(), binding);
        executionContext = withCancellation(executionContext, request.callId());
        ToolInvocation invocation = new ToolInvocation(request.callId(), request.tool(), typedRequest,
                executionContext, effectivePolicy);
        events.record(ToolExecutionEvent.Type.VALIDATED, request.callId(), request.tool(),
                request.context(), Map.of());
        ToolCallPo call = newCall(request, binding, effectivePolicy);
        try {
            callMapper.insert(call);
        } catch (DuplicateKeyException duplicate) {
            String key = request.context().idempotencyKey();
            if (key != null) {
                return existing(request.context().principal().ownerId(), key)
                        .orElseThrow(() -> duplicate);
            }
            throw duplicate;
        }
        events.record(ToolExecutionEvent.Type.REQUESTED, request.callId(), request.tool(),
                request.context(), Map.of("mode", effectivePolicy.executionMode().getValue()));
        return new PreparedCall(tool, invocation, call, null);
    }

    private Optional<PreparedCall> existing(String ownerId, String idempotencyKey) {
        return callMapper.selectByIdempotencyKey(ownerId, idempotencyKey).map(call -> {
            Optional<ToolCallResultPo> stored = resultMapper.selectByCallId(call.getCallId());
            ToolResult<? extends ToolResponse> result;
            if (stored.isPresent()) {
                result = ResultPersistenceMapper.fromPo(stored.get(), objectMapper);
            } else {
                Optional<ToolTask> task = taskRepository.findByCallId(call.getCallId());
                result = task.<ToolResult<? extends ToolResponse>>map(value ->
                                new ToolResult.Accepted<>(value.snapshot(),
                                        Map.of("idempotentReplay", true)))
                        .orElseGet(() -> failure("TOOL_CALL_IN_PROGRESS", CategoryEnum.CONFLICT,
                                "an invocation with the same idempotency key is in progress"));
            }
            return new PreparedCall(null, null, call, result);
        });
    }

    private ToolCallRequest normalize(ToolCallRequest request) {
        return new ToolCallRequest(request.callId().trim(),
                new ToolReference(request.tool().namespace().trim(), request.tool().name().trim(),
                        request.tool().version().trim()), request.arguments(), request.context(),
                request.policyOverride());
    }

    private ToolExecutionContext enrichContext(ToolExecutionContext context, ResolvedToolBinding binding) {
        if (binding == null) {
            return context;
        }
        Map<String, Object> attributes = new LinkedHashMap<>(context.attributes());
        attributes.put(ATTR_BINDING_ID, binding.bindingId());
        attributes.put(ATTR_EFFECTIVE_CONFIGURATION, binding.effectiveConfiguration());
        if (binding.credentialReference() != null && !binding.credentialReference().isBlank()) {
            attributes.put(ATTR_CREDENTIAL_REFERENCE, binding.credentialReference());
        }
        return new ToolExecutionContext(context.runId(), context.workflowRunId(), context.agentRunId(),
                context.traceId(), context.parentSpanId(), context.principal(), context.deadline(),
                context.cancellation(), context.idempotencyKey(), binding.bindingId(), attributes);
    }

    private ToolExecutionContext withCancellation(ToolExecutionContext context, String callId) {
        return new ToolExecutionContext(context.runId(), context.workflowRunId(), context.agentRunId(),
                context.traceId(), context.parentSpanId(), context.principal(), context.deadline(),
                cancellations.token(callId, context.cancellation()), context.idempotencyKey(),
                context.credentialBindingId(), context.attributes());
    }

    private ToolCallPo newCall(ToolCallRequest request, ResolvedToolBinding binding,
                               ToolExecutionPolicy policy) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("runId", request.context().runId());
        context.put("workflowRunId", request.context().workflowRunId());
        context.put("agentRunId", request.context().agentRunId());
        context.put("attributes", sanitizer.sanitize(request.context().attributes()));
        ToolCallPo po = new ToolCallPo().setCallId(request.callId())
                .setTraceId(request.context().traceId()).setSpanId(request.context().parentSpanId())
                .setOwnerId(request.context().principal().ownerId())
                .setSubjectId(request.context().principal().subjectId())
                .setSourceType(string(request.context().attributes().get(ATTR_SOURCE_TYPE)))
                .setSourceId(string(request.context().attributes().get(ATTR_SOURCE_ID)))
                .setToolId(binding == null
                        ? request.tool().namespace() + ":" + request.tool().name() : binding.toolId())
                .setToolVersion(request.tool().version())
                .setBindingId(binding == null ? null : binding.bindingId())
                .setExecutionMode(policy.executionMode())
                .setArgumentsDigest(digest(request.arguments()))
                .setArgumentsSnapshot(sanitizer.sanitize(request.arguments())).setContextSnapshot(context)
                .setPolicySnapshot(objectMapper.convertValue(policy, Map.class))
                .setStatus(ToolResultStatusEnum.ACCEPTED)
                .setIdempotencyKey(normalize(request.context().idempotencyKey()))
                .setStartedAt(LocalDateTime.now()).setMetadata(Map.of()).setRowVersion(0L);
        po.setCreateBy(request.context().principal().ownerId());
        po.setUpdateBy(request.context().principal().ownerId());
        return po;
    }

    private void persistResult(ToolCallPo call, ToolResult<? extends ToolResponse> result, Instant started) {
        if (result instanceof ToolResult.Accepted<?>) {
            return;
        }
        if (result instanceof ToolResult.Suspended<?>) {
            int changed = callMapper.transitionStatus(call.getCallId(),
                    ToolResultStatusEnum.ACCEPTED.getValue(), result.status().getValue(),
                    call.getRowVersion(), null);
            if (changed != 1) {
                throw new IllegalStateException("tool call changed concurrently while entering "
                        + result.status().getValue());
            }
            return;
        }
        ToolCallResultPo po = ResultPersistenceMapper.toPo(result, call.getCallId(), null,
                objectMapper, sanitizer);
        resultMapper.insert(po);
        String code = null, category = null, message = null;
        if (result instanceof ToolResult.Unsuccessful<?> failed) {
            code = failed.error().code();
            category = failed.error().category().name().toLowerCase();
            message = failed.error().message();
        }
        ToolUsage usage = usage(result);
        int inputTokens = usage == null || usage.inputTokens() == null
                ? 0 : Math.toIntExact(usage.inputTokens());
        int outputTokens = usage == null || usage.outputTokens() == null
                ? 0 : Math.toIntExact(usage.outputTokens());
        callMapper.complete(call.getCallId(), result.status().getValue(), LocalDateTime.now(),
                java.time.Duration.between(started, Instant.now()).toMillis(), code, category, message,
                inputTokens, outputTokens, inputTokens + outputTokens,
                call.getRowVersion());
    }

    private void recordTerminal(ToolInvocation<?> invocation, ToolResult<? extends ToolResponse> result) {
        ToolExecutionEvent.Type type = switch (result.status()) {
            case SUCCEEDED -> ToolExecutionEvent.Type.SUCCEEDED;
            case DENIED -> ToolExecutionEvent.Type.DENIED;
            case CANCELLED -> ToolExecutionEvent.Type.CANCELLED;
            case TIMED_OUT -> ToolExecutionEvent.Type.TIMED_OUT;
            case REQUIRES_APPROVAL, PAUSED -> ToolExecutionEvent.Type.PAUSED;
            case FAILED -> ToolExecutionEvent.Type.FAILED;
            case ACCEPTED -> ToolExecutionEvent.Type.TASK_QUEUED;
        };
        events.record(type, invocation.callId(), invocation.tool(), invocation.context(), Map.of());
    }

    private ToolPolicyOverride withBaseMode(ToolPolicyOverride value,
                                            com.arte.ai.common.enums.tool.ToolExecutionModeEnum baseMode) {
        return new ToolPolicyOverride(baseMode, value.timeout(), value.maxRetries(), value.retryBackoff(),
                value.maxOutputTokens(), value.requiresApproval(), value.allowsResultCache());
    }

    private ToolExecutionPolicy withExecutionMode(ToolExecutionPolicy policy,
                                                  com.arte.ai.common.enums.tool.ToolExecutionModeEnum mode) {
        if (mode == null) {
            return policy;
        }
        return new ToolExecutionPolicy(mode, policy.timeout(), policy.maxRetries(), policy.retryBackoff(),
                policy.maxOutputTokens(), policy.requiresApproval(), policy.allowsResultCache());
    }

    private ToolResult.Unsuccessful<DynamicToolResponse> failure(String code,
                                                                 CategoryEnum category,
                                                                 String message) {
        return new ToolResult.Unsuccessful<>(ToolResultStatusEnum.FAILED,
                new ToolError(code, category, message, false, Map.of()), null, Map.of());
    }

    private String digest(Object value) {
        try {
            byte[] data = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to digest tool arguments", exception);
        }
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String string(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        return text == null || text.isBlank() ? null : text;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ToolUsage usage(ToolResult<?> result) {
        if (result instanceof ToolResult.Succeeded<?> succeeded) return succeeded.usage();
        if (result instanceof ToolResult.Unsuccessful<?> unsuccessful) return unsuccessful.usage();
        return null;
    }

    private ToolCallRequest withSpan(ToolCallRequest request) {
        ToolExecutionContext context = request.context();
        if (context.parentSpanId() != null && !context.parentSpanId().isBlank()) return request;
        ToolExecutionContext traced = new ToolExecutionContext(context.runId(), context.workflowRunId(),
                context.agentRunId(), context.traceId(), UUID.randomUUID().toString(), context.principal(),
                context.deadline(), context.cancellation(), context.idempotencyKey(),
                context.credentialBindingId(), context.attributes());
        return new ToolCallRequest(request.callId(), request.tool(), request.arguments(), traced,
                request.policyOverride());
    }

    private ToolResult.Unsuccessful<DynamicToolResponse> gatewayFailure(
            RuntimeException exception, ToolReference reference) {
        Throwable cause = root(exception);
        if (cause instanceof ToolResolutionException) {
            boolean toolExists = hasKnownTool(reference);
            return failure(toolExists ? "TOOL_VERSION_UNAVAILABLE" : "TOOL_NOT_FOUND",
                    CategoryEnum.DEPENDENCY, toolExists
                            ? "requested tool version is unavailable" : "tool does not exist");
        }
        if (cause instanceof SecurityException) {
            return failure("TOOL_UNAUTHORIZED", CategoryEnum.AUTHORIZATION, cause.getMessage());
        }
        if (cause instanceof IllegalArgumentException) {
            return failure("TOOL_ARGUMENT_INVALID", CategoryEnum.VALIDATION, cause.getMessage());
        }
        return failure("TOOL_SYSTEM_ERROR", CategoryEnum.INTERNAL, rootMessage(cause));
    }

    private boolean hasKnownTool(ToolReference reference) {
        try {
            return !lifecycleManager.listVersions(reference.namespace(), reference.name()).isEmpty();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Throwable root(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private record PreparedCall(Tool<?, ?> tool, ToolInvocation<?> invocation, ToolCallPo call,
                                ToolResult<? extends ToolResponse> existingResult) {
    }

    private static final class ToolResolutionException extends RuntimeException {
        private ToolResolutionException(ToolReference reference) {
            super("unknown or unavailable tool: " + reference);
        }
    }
}
