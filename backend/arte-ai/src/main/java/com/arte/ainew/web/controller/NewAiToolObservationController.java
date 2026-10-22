package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.observation.ToolObservationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.tool.ToolObservation;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.request.ToolObservationRequests;
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
 * 本人当前空间执行列表与样本统计查询控制器
 * <p>
 * 不接受 subjectId／managed 扩权参数，不暴露内部快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-execution.enabled"}, havingValue = "true")
public class NewAiToolObservationController {

    private final ToolObservationService service;
    private final NewAiHttpContext contexts;
    private final Duration timeout;

    public NewAiToolObservationController(ToolObservationService service, NewAiHttpContext contexts, NewAiProperties properties) {
        this.service = service;
        this.contexts = contexts;
        timeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(10)) < 0 ? properties.limits().maximumTimeout() : Duration.ofSeconds(10);
    }

    @PostMapping("/listExecutions")
    public Mono<ResultContext<ToolObservation.Page>> list(@Valid @RequestBody ToolObservationRequests.Query request, Locale locale) {
        return contexts.read(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()), Set.of(AdmissionAuthorization.READ), timeout,
                        false, context -> service.list(request.value(), request.cursor(), request.limit() == null ? 10 : request.limit(), context))
                .map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getStatistics")
    public Mono<ResultContext<ToolObservation.Statistics>> statistics(@Valid @RequestBody ToolObservationRequests.Query request, Locale locale) {
        if (request.cursor() != null || request.limit() != null)
            throw new IllegalArgumentException("Statistics does not accept pagination");
        return contexts.read(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()), Set.of(AdmissionAuthorization.READ), timeout,
                false, context -> service.statistics(request.value(), context)).map(value -> ResultContext.success(value, ResultCodeEnum.SUCCESS, locale));
    }
}
