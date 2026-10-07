package com.arte.ainew.web.controller;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionEvent;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.request.InvocationRequests;
import com.arte.ainew.web.response.InvocationEventsResponse;
import com.arte.ainew.web.response.InvocationResultResponse;
import com.arte.ainew.web.response.InvocationStatusResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 查询执行状态、结果、事件，后续增加取消
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 **/
@RestController
@RequestMapping("/ai-new/invocation")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(name = {"arte.ai-new.enabled", "arte.ai-new-execution.enabled"}, havingValue = "true")
public class NewAiInvocationController {

    private final ExecutionControl executionControl;
    private final ExecutionEventService executionEventService;
    private final NewAiHttpContext httpContext;
    private final Duration queryTimeout;

    public NewAiInvocationController(ExecutionControl executionControl, ExecutionEventService executionEventService,
                                     NewAiHttpContext httpContext, NewAiProperties properties) {
        this.executionControl = executionControl;
        this.executionEventService = executionEventService;
        this.httpContext = httpContext;
        this.queryTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/getInvocationStatus")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResultContext<InvocationStatusResponse>> getInvocationStatus(
            @Valid @RequestBody InvocationRequests.Query request, Locale locale) {
        return readContext(request.scope())
                .flatMap(context -> executionControl.status(request.invocationId(), context))
                .map(invocation -> ResultContext.success(InvocationStatusResponse.from(invocation), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getInvocationResult")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResultContext<InvocationResultResponse>> getInvocationResult(
            @Valid @RequestBody InvocationRequests.Query request, Locale locale) {
        return readContext(request.scope())
                .flatMap(context -> executionEventService.result(request.invocationId(), context))
                .map(result -> ResultContext.success(InvocationResultResponse.from(request.invocationId(), result),
                        ResultCodeEnum.SUCCESS, locale));
    }

    /**
     * 单页读取已持久化事件；不是 SSE watch，不派发或重新执行调用。
     */
    @PostMapping("/invocationEvent")
    @PreAuthorize("isAuthenticated()")
    public Mono<ResultContext<InvocationEventsResponse>> invocationEvent(
            @Valid @RequestBody InvocationRequests.Replay request, Locale locale) {
        return readContext(request.scope())
                .flatMap(context -> executionEventService.replay(
                        new ExecutionEvent.Cursor(request.invocationId(), request.afterSequence()), request.limit(), context))
                .map(outcome -> {
                    if (!outcome.successful()) {
                        throw AdmissionException.fromStoreRejection(outcome.code());
                    }
                    return ResultContext.success(InvocationEventsResponse.from(request.invocationId(), outcome.value()),
                            ResultCodeEnum.SUCCESS, locale);
                });
    }

    private Mono<ExecutionContext> readContext(ConversationRequests.Scope scope) {
        // 在 MVC 请求线程捕获身份，每次读取分配新的查询期限，不沿用原模型执行上下文。
        return httpContext.create(scope, Set.of(AdmissionAuthorization.READ), queryTimeout, null, null);
    }
}
