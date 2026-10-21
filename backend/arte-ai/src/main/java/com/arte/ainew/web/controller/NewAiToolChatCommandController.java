package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.chat.ToolChatService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ToolChatRequests;
import com.arte.ainew.web.response.ToolChatRunStatusResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 工具聊天提交及耐久停止
 * <p>
 * 独立开关关闭时不注册写入口，读取旧 Run 仍走查询控制器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@RestController
@RequestMapping("/ai-new/tool-chat")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled", "ai-new-tool-chat.enabled"}, havingValue = "true")
public class NewAiToolChatCommandController {

    private final ToolChatService service;
    private final ToolManagementAccess access;
    private final NewAiHttpContext contexts;
    private final Duration controlTimeout;

    public NewAiToolChatCommandController(ToolChatService service, ToolManagementAccess access, NewAiHttpContext contexts,
                                          com.arte.ainew.config.NewAiProperties properties) {
        this.service = service;
        this.access = access;
        this.contexts = contexts;
        controlTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(10)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(10);
    }

    @PostMapping("/submitToolChat")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ResultContext<AcceptedExecution>> submit(@Valid @RequestBody ToolChatRequests.Submit request,
                                                         @RequestHeader("Idempotency-Key") String key,
                                                         Authentication authentication, Locale locale) {
        var session = access.capture(authentication);
        var input = request.input();
        return contexts.prepare(request.scope(), Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ),
                        input.limits().timeout(), null, key, c -> service.submit(input, session, c))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/cancelRun")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ResultContext<ToolChatRunStatusResponse>> cancel(@Valid @RequestBody ToolChatRequests.Query request,
                                                                 @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return contexts.create(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), controlTimeout, null, key)
                .flatMap(c -> service.cancel(request.runId(), key, c))
                .map(run -> ResultContext.success(ToolChatRunStatusResponse.from(run, !run.state().terminal() || run.state() == com.arte.ainew.pojo.tool.ToolChatRun.State.CANCELLED), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/submitReadOnlyToolChat")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<ResultContext<AcceptedExecution>> submitReadOnly(@Valid @RequestBody ToolChatRequests.Submit request,
                                                                 @RequestHeader("Idempotency-Key") String key,
                                                                 Authentication authentication, Locale locale) {
        var session = access.capture(authentication);
        var input = request.input();
        return contexts.prepare(request.scope(), Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ),
                        input.limits().timeout(), null, key, c -> service.submit(input, session, c, true))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
