package com.arte.ai.service.tool;

import com.arte.ai.api.tool.*;
import com.arte.ai.api.tool.security.GuardrailDecision;
import com.arte.ai.api.tool.security.ToolApprovalService;
import com.arte.ai.api.tool.security.ToolAuthorizer;
import com.arte.ai.api.tool.security.ToolGuardrail;
import com.arte.ai.common.enums.tool.*;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.observability.ToolEventRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * 默认工具执行管道。
 *
 * <p>解析、绑定和输入 Schema 校验由网关先完成；本类固定执行授权、输入 Guardrail、
 * 审批、集群限流、超时、重试、工具调用和输出校验。DEFERRED 只持久化任务，Worker
 * 通过受信标记再次进入同一管道执行。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Service
public class DefaultToolExecutor implements ToolExecutor {

    public static final String DEFERRED_WORKER_ATTRIBUTE = "tool.deferred.worker";
    public static final String APPROVAL_REQUEST_ATTRIBUTE = "tool.approval.request-id";

    private final List<ToolAuthorizer> authorizers;
    private final List<ToolGuardrail> guardrails;
    private final List<ToolExecutionInterceptor> interceptors;
    private final ObjectProvider<ToolApprovalService> approvalServiceProvider;
    private final ObjectProvider<ToolTaskManager> taskManagerProvider;
    private final ToolRateLimiter rateLimiter;
    private final ToolSchemaValidator schemaValidator;
    private final ToolEventRecorder events;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public DefaultToolExecutor(List<ToolAuthorizer> authorizers,
                               List<ToolGuardrail> guardrails,
                               List<ToolExecutionInterceptor> interceptors,
                               ObjectProvider<ToolApprovalService> approvalServiceProvider,
                               ObjectProvider<ToolTaskManager> taskManagerProvider,
                               ToolRateLimiter rateLimiter,
                               ToolSchemaValidator schemaValidator,
                               ToolEventRecorder events,
                               ObjectMapper objectMapper,
                               @Qualifier("toolCallbackExecutor") Executor executor) {
        this.authorizers = List.copyOf(authorizers);
        this.guardrails = List.copyOf(guardrails);
        this.interceptors = List.copyOf(interceptors);
        this.approvalServiceProvider = approvalServiceProvider;
        this.taskManagerProvider = taskManagerProvider;
        this.rateLimiter = rateLimiter;
        this.schemaValidator = schemaValidator;
        this.events = events;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    public <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolResult<O>> execute(
            Tool<I, O> tool, ToolInvocation<I> invocation) {
        return CompletableFuture.supplyAsync(() -> executePipeline(tool, invocation), executor);
    }

    @SuppressWarnings("unchecked")
    private <I extends ToolRequest, O extends ToolResponse> ToolResult<O> executePipeline(
            Tool<I, O> tool, ToolInvocation<I> invocation) {
        ToolDefinition definition = tool.getDefinition();
        try {
            if (invocation.context().cancellation().isCancellationRequested()) {
                return failure(ToolResultStatusEnum.CANCELLED, "TOOL_CANCELLED",
                        CategoryEnum.CANCELLED, "tool invocation was cancelled", false);
            }
            for (ToolAuthorizer authorizer : authorizers) {
                ToolAuthorizationDecision decision = await(authorizer.authorize(definition, invocation));
                if (!decision.allowed()) {
                    events.record(ToolExecutionEvent.Type.DENIED, invocation.callId(), invocation.tool(),
                            invocation.context(), Map.of("reason", safe(decision.reason())));
                    return failure(ToolResultStatusEnum.DENIED, "TOOL_FORBIDDEN",
                            CategoryEnum.AUTHORIZATION, safe(decision.reason()), false);
                }
            }
            events.record(ToolExecutionEvent.Type.AUTHORIZED, invocation.callId(), invocation.tool(),
                    invocation.context(), Map.of());

            ToolInvocation<I> effectiveInvocation = invocation;
            GuardrailOutcome inputGuardrail = evaluateGuardrails(GuardrailPhaseEnum.INPUT,
                    definition, invocation, null);
            if (inputGuardrail.denied()) {
                return failure(ToolResultStatusEnum.DENIED, "TOOL_GUARDRAIL_DENIED",
                        CategoryEnum.POLICY, inputGuardrail.reason(), false);
            }
            effectiveInvocation = applyInputChanges(tool, effectiveInvocation, inputGuardrail.changes());
            GuardrailOutcome preExecutionGuardrail = evaluateGuardrails(GuardrailPhaseEnum.PRE_EXECUTION,
                    definition, effectiveInvocation, null);
            if (preExecutionGuardrail.denied()) {
                return failure(ToolResultStatusEnum.DENIED, "TOOL_GUARDRAIL_DENIED",
                        CategoryEnum.POLICY, preExecutionGuardrail.reason(), false);
            }
            effectiveInvocation = applyInputChanges(tool, effectiveInvocation,
                    preExecutionGuardrail.changes());

            Optional<String> existingApproval = Optional.ofNullable((String) effectiveInvocation.context()
                    .attributes().get(APPROVAL_REQUEST_ATTRIBUTE));
            boolean approvalRequired = invocation.effectivePolicy().requiresApproval()
                    || inputGuardrail.approvalRequired() || preExecutionGuardrail.approvalRequired();
            if (approvalRequired) {
                ToolResult<O> approvalResult = handleApproval(tool, effectiveInvocation, existingApproval);
                if (approvalResult != null) {
                    return approvalResult;
                }
            }

            boolean deferredWorker = Boolean.TRUE.equals(effectiveInvocation.context().attributes()
                    .get(DEFERRED_WORKER_ATTRIBUTE));
            if (effectiveInvocation.effectivePolicy().executionMode() == ToolExecutionModeEnum.DEFERRED
                    && !deferredWorker) {
                ToolTaskHandle handle = await(taskManagerProvider.getObject().submit(tool, effectiveInvocation));
                events.record(ToolExecutionEvent.Type.TASK_QUEUED, invocation.callId(), invocation.tool(),
                        invocation.context(), Map.of("taskId", handle.taskId()));
                return (ToolResult<O>) new ToolResult.Accepted<>(handle, Map.of("taskId", handle.taskId()));
            }

            if (!rateLimiter.tryAcquire(effectiveInvocation)) {
                return failure(ToolResultStatusEnum.FAILED, "TOOL_RATE_LIMITED",
                        CategoryEnum.RATE_LIMIT, "tool rate limit exceeded", true);
            }

            events.record(ToolExecutionEvent.Type.STARTED, invocation.callId(), invocation.tool(),
                    invocation.context(), Map.of());
            ToolResult<O> result = invokeWithRetry(tool, effectiveInvocation);
            if (result instanceof ToolResult.Succeeded<O> succeeded) {
                GuardrailOutcome postGuardrail = evaluateGuardrails(GuardrailPhaseEnum.POST_EXECUTION,
                        definition, effectiveInvocation, result);
                if (postGuardrail.denied()) {
                    return failure(ToolResultStatusEnum.DENIED, "TOOL_OUTPUT_GUARDRAIL_DENIED",
                            CategoryEnum.POLICY, postGuardrail.reason(), false);
                }
                result = applyOutputChanges(result, postGuardrail.changes());
                GuardrailOutcome outputGuardrail = evaluateGuardrails(GuardrailPhaseEnum.OUTPUT,
                        definition, effectiveInvocation, result);
                if (outputGuardrail.denied()) {
                    return failure(ToolResultStatusEnum.DENIED, "TOOL_OUTPUT_GUARDRAIL_DENIED",
                            CategoryEnum.POLICY, outputGuardrail.reason(), false);
                }
                result = applyOutputChanges(result, outputGuardrail.changes());
                schemaValidator.validate(definition.outputSchema(),
                        ((ToolResult.Succeeded<O>) result).output(), "tool output");
            }
            return result;
        } catch (TimeoutException exception) {
            return failure(ToolResultStatusEnum.TIMED_OUT, "TOOL_TIMEOUT", CategoryEnum.TIMEOUT,
                    "tool invocation timed out", true);
        } catch (CancellationException exception) {
            return failure(ToolResultStatusEnum.CANCELLED, "TOOL_CANCELLED", CategoryEnum.CANCELLED,
                    "tool invocation was cancelled", false);
        } catch (Exception exception) {
            Throwable cause = unwrap(exception);
            return failure(ToolResultStatusEnum.FAILED, "TOOL_EXECUTION_FAILED",
                    CategoryEnum.INTERNAL, safe(cause.getMessage()), true);
        }
    }

    @SuppressWarnings("unchecked")
    private <I extends ToolRequest, O extends ToolResponse> ToolResult<O> handleApproval(
            Tool<I, O> tool, ToolInvocation<I> invocation, Optional<String> existingRequestId) {
        ToolApprovalService approvals = approvalServiceProvider.getIfAvailable();
        if (approvals == null) {
            return failure(ToolResultStatusEnum.DENIED, "TOOL_APPROVAL_UNAVAILABLE",
                    CategoryEnum.APPROVAL, "approval service is unavailable", false);
        }
        if (existingRequestId.isPresent()) {
            Optional<ToolApprovalDecision> decision = approvals.findDecision(existingRequestId.get());
            if (decision.isPresent() && decision.get().approved()) {
                return null;
            }
            if (decision.isPresent()) {
                return failure(ToolResultStatusEnum.DENIED, "TOOL_APPROVAL_REJECTED",
                        CategoryEnum.APPROVAL, safe(decision.get().reason()), false);
            }
        }
        ToolApprovalRequest request = existingRequestId.isPresent()
                ? new ToolApprovalRequest(existingRequestId.get(), invocation.callId(), invocation.tool(),
                "pending", "Waiting for approval", Instant.now().plus(invocation.effectivePolicy().timeout()), Map.of())
                : await(approvals.requestApproval(invocation));
        ToolTaskHandle task = await(taskManagerProvider.getObject().suspend(tool, invocation, request.requestId()));
        events.record(ToolExecutionEvent.Type.APPROVAL_REQUESTED, invocation.callId(), invocation.tool(),
                invocation.context(), Map.of("requestId", request.requestId(), "taskId", task.taskId()));
        return (ToolResult<O>) new ToolResult.Suspended<>(ToolResultStatusEnum.REQUIRES_APPROVAL,
                request.requestId(), task.resumeToken(), Map.of("taskId", task.taskId()));
    }

    private <I extends ToolRequest, O extends ToolResponse> ToolResult<O> invokeWithRetry(
            Tool<I, O> tool, ToolInvocation<I> invocation) throws Exception {
        int retries = tool.getDefinition().riskProfile().idempotent()
                ? invocation.effectivePolicy().maxRetries() : 0;
        for (int attempt = 0; ; attempt++) {
            try {
                Duration timeout = effectiveTimeout(invocation);
                return invokeInterceptors(tool, invocation, 0).toCompletableFuture()
                        .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException exception) {
                if (attempt >= retries) {
                    throw exception;
                }
                events.record(ToolExecutionEvent.Type.RETRIED, invocation.callId(), invocation.tool(),
                        invocation.context(), Map.of("attempt", attempt + 1));
                Thread.sleep(invocation.effectivePolicy().retryBackoff().toMillis());
            }
        }
    }

    private <I extends ToolRequest, O extends ToolResponse> CompletionStage<ToolResult<O>> invokeInterceptors(
            Tool<I, O> tool, ToolInvocation<I> invocation, int index) {
        if (index >= interceptors.size()) {
            return tool.execute(invocation);
        }
        ToolExecutionInterceptor interceptor = interceptors.get(index);
        return interceptor.intercept(tool, invocation,
                (nextTool, nextInvocation) -> invokeInterceptors(nextTool, nextInvocation, index + 1));
    }

    private Duration effectiveTimeout(ToolInvocation<?> invocation) {
        Duration policy = invocation.effectivePolicy().timeout();
        Duration deadline = Duration.between(Instant.now(), invocation.context().deadline());
        if (deadline.isNegative() || deadline.isZero()) {
            return Duration.ofMillis(1);
        }
        return deadline.compareTo(policy) < 0 ? deadline : policy;
    }

    private GuardrailOutcome evaluateGuardrails(GuardrailPhaseEnum phase, ToolDefinition definition,
                                                ToolInvocation<?> invocation, ToolResult<?> result) {
        boolean approval = false;
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        for (ToolGuardrail guardrail : guardrails) {
            if (!guardrail.getSupportedPhases().contains(phase)) {
                continue;
            }
            GuardrailDecision decision = await(guardrail.evaluate(new GuardrailContext(
                    phase, definition, invocation, result, Map.of())));
            events.record(ToolExecutionEvent.Type.GUARDRAIL_EVALUATED, invocation.callId(),
                    invocation.tool(), invocation.context(),
                    Map.of("guardrail", guardrail.getName(), "action", decision.action().getValue()));
            if (decision.action() == GuardrailActionEnum.DENY) {
                return new GuardrailOutcome(true, false, safe(decision.reason()), Map.of());
            }
            if (decision.action() == GuardrailActionEnum.REQUIRE_APPROVAL) {
                approval = true;
            }
            if (decision instanceof GuardrailDecision.Changed changed) {
                changes.putAll(changed.changes());
            }
        }
        return new GuardrailOutcome(false, approval, "", Map.copyOf(changes));
    }

    @SuppressWarnings("unchecked")
    private <I extends ToolRequest, O extends ToolResponse> ToolInvocation<I> applyInputChanges(
            Tool<I, O> tool, ToolInvocation<I> invocation, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return invocation;
        }
        Map<String, Object> arguments = invocation.request() instanceof DynamicToolRequest dynamic
                ? new java.util.LinkedHashMap<>(dynamic.arguments())
                : objectMapper.convertValue(invocation.request(), Map.class);
        arguments.putAll(changes);
        schemaValidator.validate(tool.getDefinition().inputSchema(), arguments,
                "guardrail transformed arguments");
        I request = tool.getRequestType() == DynamicToolRequest.class
                ? (I) new DynamicToolRequest(arguments)
                : objectMapper.convertValue(arguments, tool.getRequestType());
        return new ToolInvocation<>(invocation.callId(), invocation.tool(), request,
                invocation.context(), invocation.effectivePolicy());
    }

    @SuppressWarnings("unchecked")
    private <O extends ToolResponse> ToolResult<O> applyOutputChanges(
            ToolResult<O> result, Map<String, Object> changes) {
        if (changes.isEmpty() || !(result instanceof ToolResult.Succeeded<O> succeeded)) {
            return result;
        }
        if (!(succeeded.output() instanceof DynamicToolResponse dynamic)) {
            throw new IllegalStateException("typed output transformation requires a tool-specific interceptor");
        }
        Object value = changes.containsKey("value") ? changes.get("value") : dynamic.value();
        String raw = changes.containsKey("rawContent")
                ? String.valueOf(changes.get("rawContent")) : dynamic.rawContent();
        return new ToolResult.Succeeded<>((O) new DynamicToolResponse(value, raw),
                succeeded.content(), succeeded.artifacts(), succeeded.usage(), succeeded.metadata());
    }

    private <O extends ToolResponse> ToolResult<O> failure(ToolResultStatusEnum status, String code,
                                                           CategoryEnum category, String message,
                                                           boolean retryable) {
        return new ToolResult.Unsuccessful<>(status,
                new ToolError(code, category, safe(message), retryable, Map.of()), null, Map.of());
    }

    private <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted");
        } catch (ExecutionException exception) {
            throw new CompletionException(exception.getCause());
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "tool operation failed" : value;
    }

    private record GuardrailOutcome(boolean denied, boolean approvalRequired, String reason,
                                    Map<String, Object> changes) {
    }
}
