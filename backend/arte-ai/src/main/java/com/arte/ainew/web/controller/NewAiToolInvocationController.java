package com.arte.ainew.web.controller;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.execution.ControlReceipt;
import com.arte.ainew.pojo.execution.ExecutionControlRequest;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.request.InvocationRequests;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 工具调用控制器
 * <p>
 * TOOL 专用停止入口使用当前 READ 上下文及独立控制资格，关闭 AI 新调用后仍可停止本人已有调用。
 * 状态、结果、快照和事件复用通用 invocation 入口，不另建执行或事件权威。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-execution.enabled"}, havingValue = "true")
public class NewAiToolInvocationController {

    private final ExecutionControl control;
    private final NewAiHttpContext contexts;
    private final Duration timeout;

    public NewAiToolInvocationController(ExecutionControl control, NewAiHttpContext contexts, NewAiProperties properties) {
        this.control = control;
        this.contexts = contexts;
        this.timeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/cancelToolInvocation")
    public Mono<ResponseEntity<ResultContext<ControlReceipt>>> cancel(@Valid @RequestBody InvocationRequests.ToolCancel request,
                                                                      @RequestHeader("Idempotency-Key") String key, Locale locale) {
        ContractChecks.id(key, "Idempotency-Key");
        return contexts.create(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()),
                        Set.of(AdmissionAuthorization.READ), timeout, null, key)
                .flatMap(context -> control.request(new ExecutionControlRequest(context.executionId(), request.invocationId(), key,
                        ControlReceipt.Command.CANCEL), context)).map(receipt -> ResponseEntity.status(receipt.outcome() == ControlReceipt.Outcome.ACCEPTED ? 202 : 200)
                        .body(ResultContext.success(receipt, ResultCodeEnum.SUCCESS, locale)));
    }
}
