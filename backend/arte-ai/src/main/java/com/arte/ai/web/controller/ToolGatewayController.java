package com.arte.ai.web.controller;

import com.arte.ai.api.tool.ToolGateway;
import com.arte.ai.api.tool.ToolResponse;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.NeverToolCancellation;
import com.arte.ai.pojo.tool.*;
import com.arte.ai.service.tool.DefaultToolGateway;
import com.arte.ai.service.tool.ToolTaskQueryService;
import com.arte.core.pojo.ResultContext;
import com.arte.core.pojo.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * REST 调用来源到统一 ToolGateway 的薄适配层。
 */
@RestController
@RequestMapping("/ai/tools")
@RequiredArgsConstructor
public class ToolGatewayController {

    private final ToolGateway gateway;
    private final ToolTaskQueryService taskQueryService;

    @PostMapping("/invoke")
    public CompletionStage<ResultContext<ToolResult<? extends ToolResponse>>> invoke(
            @RequestBody RestToolInvokeRequest request) {
        String ownerId = UserContext.getUserName();
        ToolPolicyOverride policy = policy(request);
        Duration timeout = policy == null || policy.timeout() == null
                ? Duration.ofMinutes(5) : policy.timeout();
        Map<String, Object> attributes = new LinkedHashMap<>();
        put(attributes, DefaultToolGateway.ATTR_BINDING_ID, request.bindingId());
        put(attributes, DefaultToolGateway.ATTR_WORKSPACE_ID, request.workspaceId());
        attributes.put(DefaultToolGateway.ATTR_SOURCE_TYPE, "REST");
        ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID().toString(),
                null, null, UUID.randomUUID().toString(), null,
                new ToolPrincipal(ownerId, ownerId, Set.of(), Set.of()), Instant.now().plus(timeout),
                NeverToolCancellation.INSTANCE, request.idempotencyKey(), null, attributes);
        ToolCallRequest call = new ToolCallRequest(UUID.randomUUID().toString(), request.tool(),
                request.arguments(), context, policy);
        return gateway.invoke(call).thenApply(ResultContext::success);
    }

    @GetMapping("/tasks/{taskId}")
    public CompletionStage<ResultContext<?>> task(@PathVariable String taskId) {
        return gateway.findTask(taskId, UserContext.getUserName()).thenApply(ResultContext::success);
    }

    @GetMapping("/tasks")
    public ResultContext<ToolTaskPage> tasks(
            @RequestParam(required = false) ToolTaskHandle.Status status,
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size) {
        return ResultContext.success(taskQueryService.listOwned(UserContext.getUserName(), status, current, size));
    }

    @GetMapping("/tasks/{taskId}/result")
    public CompletionStage<ResultContext<?>> result(@PathVariable String taskId) {
        return gateway.findTaskResult(taskId, UserContext.getUserName()).thenApply(ResultContext::success);
    }

    @PostMapping("/calls/{callId}/cancel")
    public CompletionStage<ResultContext<Boolean>> cancel(@PathVariable String callId) {
        return gateway.cancel(callId, UserContext.getUserName()).thenApply(ResultContext::success);
    }

    @PostMapping("/resume")
    public CompletionStage<ResultContext<ToolResult<? extends ToolResponse>>> resume(
            @RequestBody ToolResumeRequest request) {
        return gateway.resume(request.resumeToken(), UserContext.getUserName())
                .thenApply(ResultContext::success);
    }

    private ToolPolicyOverride policy(RestToolInvokeRequest request) {
        if (request.executionMode() == null && request.timeout() == null && request.maxRetries() == null) {
            return null;
        }
        return new ToolPolicyOverride(request.executionMode(), request.timeout(), request.maxRetries(),
                null, null, null, null);
    }

    private void put(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value.trim());
    }
}
