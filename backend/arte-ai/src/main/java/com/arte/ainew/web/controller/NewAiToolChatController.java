package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.chat.ToolChatQueryService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.tool.ToolChatRunEvent;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ToolChatRequests;
import com.arte.ainew.web.response.ToolChatBudgetResponse;
import com.arte.ainew.web.response.ToolChatRunStatusResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Run 查询和耐久游标补读，不提供提交、自动执行、取消或 SSE。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
@RestController
@RequestMapping("/ai-new/tool-chat")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class NewAiToolChatController {

    private final ToolChatQueryService queries;
    private final NewAiHttpContext contexts;
    private final Duration timeout;

    public NewAiToolChatController(ToolChatQueryService queries, NewAiHttpContext contexts, NewAiProperties properties) {
        this.queries = queries;
        this.contexts = contexts;
        timeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/getRunStatus")
    public Mono<ResultContext<ToolChatRunStatusResponse>> status(@Valid @RequestBody ToolChatRequests.Query request, Locale locale) {
        return contexts.read(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), timeout, false,
                c -> queries.status(request.runId(), c)).map(r -> ResultContext.success(ToolChatRunStatusResponse.from(r), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getRunBudget")
    public Mono<ResultContext<List<ToolChatBudgetResponse>>> budgets(@Valid @RequestBody ToolChatRequests.Query request, Locale locale) {
        return contexts.read(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), timeout, false,
                        c -> queries.budgets(request.runId(), c)).map(values -> values.stream().map(ToolChatBudgetResponse::from).toList())
                .map(r -> ResultContext.success(r, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getRunResult")
    public Mono<ResultContext<ToolChatQueryService.Answer>> result(@Valid @RequestBody ToolChatRequests.Query request, Locale locale) {
        return contexts.read(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), timeout, false,
                c -> queries.result(request.runId(), c)).map(r -> ResultContext.success(r, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/replayRunEvents")
    public Mono<ResultContext<List<ToolChatRunEvent>>> events(@Valid @RequestBody ToolChatRequests.Events request, Locale locale) {
        return contexts.read(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION), timeout, false,
                c -> queries.events(request.runId(), request.afterSequence(), request.limit(), c)).map(r -> ResultContext.success(r, ResultCodeEnum.SUCCESS, locale));
    }
}
