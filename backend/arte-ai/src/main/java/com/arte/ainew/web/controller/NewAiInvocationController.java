package com.arte.ainew.web.controller;

import com.arte.ainew.api.execution.ExecutionControl;
import com.arte.ainew.api.execution.ExecutionEventService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.execution.InvocationBudgetStatusResolver;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 查询执行状态、结果、耐久事件及 SSE 通知，后续增加取消
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
    private final InvocationBudgetStatusResolver budgetStatus;
    private final Duration queryTimeout;
    private final Duration watchTimeout;

    public NewAiInvocationController(ExecutionControl executionControl, ExecutionEventService executionEventService,
                                     NewAiHttpContext httpContext, NewAiProperties properties, InvocationBudgetStatusResolver budgetStatus) {
        this.budgetStatus = budgetStatus;
        this.executionControl = executionControl;
        this.executionEventService = executionEventService;
        this.httpContext = httpContext;
        this.watchTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(60)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(60);
        this.queryTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/getInvocationStatus")
    public Mono<ResultContext<InvocationStatusResponse>> getInvocationStatus(
            @Valid @RequestBody InvocationRequests.Query request, Locale locale) {
        return readContext(request.scope())
                .flatMap(context -> executionControl.status(request.invocationId(), context))
                .flatMap(invocation -> budgetStatus.resolve(invocation).map(state ->
                        ResultContext.success(InvocationStatusResponse.from(invocation, state), ResultCodeEnum.SUCCESS, locale)));
    }

    @PostMapping("/getInvocationResult")
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

    /**
     * 通过 POST/fetch 和现有 Bearer Token 建立 SSE 订阅，从排他游标补读历史并接收新事件。
     * 建连前验证读取权限、调用归属及游标，失败返回 HTTP 错误；建连后异常转换为 error 帧。
     * <p>
     * 通知只含调用 ID、序号及类别，结果、历史和预算由前端通过 HTTP 查询。
     * 先发送 connected 注释，再合并事件与每 15 秒一次的心跳；事件流结束时停止心跳。
     * 连接期限取 60 秒与配置最大期限的较小值，不延长模型调用；心跳不查数据库。
     *
     * @param request 租户、工作空间、调用 ID 及最后已处理的事件序号
     * @return SSE 响应；事件重放、重新授权及结束判断由执行事件服务负责
     */
    @PostMapping(value = "/watchInvocation", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<Object>>>> watchInvocation(@Valid @RequestBody InvocationRequests.Watch request) {
        // 排他游标：仅接收大于 afterSequence 的事件，支持断线续读。
        var cursor = new ExecutionEvent.Cursor(request.invocationId(), request.afterSequence());
        // 创建本次观看的 READ 上下文，期限独立于模型执行。
        return httpContext.create(request.scope(), Set.of(AdmissionAuthorization.READ), watchTimeout, null, null)
                // 预读一条检查归属及游标，不推进游标；此处失败仍可返回 HTTP 错误。
                .flatMap(executionContext -> executionEventService.replay(cursor, 1, executionContext).map(preflight -> {
                    if (!preflight.successful()) {
                        throw AdmissionException.fromStoreRejection(preflight.code());
                    }
                    // 仅用于在事件流结束时停止心跳。
                    var done = Sinks.<Void>one();
                    Flux<ServerSentEvent<Object>> events = executionEventService.watch(cursor, executionContext)
                            // 编码通知；SSE id 使用事件序号，不直接发送业务 payload。
                            .map(event -> ServerSentEvent.<Object>builder(Map.of("executionId", event.executionId(),
                                            "sequence", event.sequence(), "kind", event.kind().name())).event("invocation")
                                    .id(Long.toString(event.sequence())).build())
                            // 响应开始后通过 error 帧报告错误，不再修改 HTTP 状态或暴露异常详情。
                            .onErrorResume(error -> Flux.just(streamError(error)))
                            // 正常完成或发送完错误帧后，通知心跳流结束。
                            .concatWith(Flux.defer(() -> {
                                done.tryEmitEmpty();
                                return Flux.empty();
                            }));
                    // 注释心跳只保活，不读取数据库，也不推进事件游标。
                    var heartbeat = Flux.interval(Duration.ofSeconds(15))
                            .map(ignored -> ServerSentEvent.builder().comment("heartbeat").build())
                            .takeUntilOther(done.asMono());
                    // 先发送连接标记，再合并事件和心跳；每个来源预取 1 条。
                    var stream = Flux.concat(Flux.just(ServerSentEvent.builder().comment("connected").build()),
                            Flux.merge(1, events, heartbeat));
                    // 返回流式响应，禁止缓存，并请求代理关闭响应缓冲。
                    return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                            .header("Cache-Control", "no-cache, no-transform").header("X-Accel-Buffering", "no")
                            .body(stream);
                }));
    }

    private ServerSentEvent<Object> streamError(Throwable error) {
        int status = error instanceof AuthenticationException ? 401 : error instanceof AccessDeniedException ? 403
                : error instanceof AdmissionException rejected && rejected.getResultCode() == ResultCodeEnum.AI_CURSOR_EXPIRED ? 410 : 503;
        String code = error instanceof AdmissionException rejected ? rejected.getResultCode().getCode() : "SSE_UNAVAILABLE";
        return ServerSentEvent.<Object>builder(Map.of("httpStatus", status, "code", code)).event("error").build();
    }

    private Mono<ExecutionContext> readContext(ConversationRequests.Scope scope) {
        // 在 MVC 请求线程捕获身份，每次读取分配新的查询期限，不沿用原模型执行上下文。
        return httpContext.create(scope, Set.of(AdmissionAuthorization.READ), queryTimeout, null, null);
    }
}
