package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.admission.ToolAdmissionService;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.request.ToolInvocationRequests;
import com.arte.ainew.web.response.ToolInvocationPreviewResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * 公开的独立免费只读 TOOL 预览与受理；使用当前空间 AI 资格，不读取模型配置或预算。
 * 受理服务依赖宿主明确的免费及资源授权；缺少接线时失败关闭，不能以只读声明默认放行。
 * 取消／历史查询使用独立入口，关闭新调用不阻止已有调用恢复。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled"}, havingValue = "true")
public class NewAiToolSubmissionController {
    private final ObjectProvider<ToolAdmissionService> services;
    private final NewAiHttpContext contexts;
    private final Duration timeout;

    public NewAiToolSubmissionController(ObjectProvider<ToolAdmissionService> services, NewAiHttpContext contexts, NewAiProperties properties) {
        this.services = services;
        this.contexts = contexts;
        this.timeout = properties.limits().maximumTimeout();
    }

    public record ToolBudgets(com.arte.ainew.pojo.tool.ToolPrice price,
                              java.util.List<com.arte.ainew.web.response.BudgetAccountResponse> accounts) {
    }

    @PostMapping("/availableToolBudgets")
    public Mono<ResultContext<ToolBudgets>> available(@Valid @RequestBody ToolInvocationRequests.Budgets request, Locale locale) {
        return contexts.read(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()),
                        Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), timeout, true,
                        current -> service().availableBudgets(request.bindingId(), current))
                .map(value -> ResultContext.success(new ToolBudgets(value.price(), value.accounts().stream().map(com.arte.ainew.web.response.BudgetAccountResponse::from).toList()), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/previewInvocation")
    public Mono<ResultContext<ToolInvocationPreviewResponse>> preview(@Valid @RequestBody ToolInvocationRequests.Preview request, Locale locale) {
        var input = request.input().value();
        return contexts.read(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()),
                        Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), requestTimeout(input.requestedTimeout()), input.budgetRef() != null, "preview",
                        context -> service().previewPlan(input, context))
                .map(value -> ResultContext.success(ToolInvocationPreviewResponse.from(value), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/invokeTool")
    public Mono<ResponseEntity<ResultContext<AcceptedExecution>>> submit(@Valid @RequestBody ToolInvocationRequests.Submit request,
                                                                         @RequestHeader("Idempotency-Key") String key, Locale locale) {
        ContractChecks.id(key, "Idempotency-Key");
        var input = request.input().value();
        return contexts.read(new ConversationRequests.Scope(request.scope().tenantId(), request.scope().workspaceId()),
                        Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.READ), requestTimeout(input.requestedTimeout()), input.budgetRef() != null, key,
                        context -> service().submit(input, request.expectedPlanDigest(), context))
                .map(value -> ResponseEntity.accepted().body(ResultContext.success(value, ResultCodeEnum.SUCCESS, locale)));
    }

    private ToolAdmissionService service() {
        var service = services.getIfAvailable();
        if (service == null)
            throw new IllegalStateException("Trusted tool admission and execution access are required");
        return service;
    }

    /**
     * 请求的总期限从身份解析前开始；等待当前资格不能额外获得一段执行时间。
     */
    private Duration requestTimeout(Duration requested) {
        return requested == null || requested.compareTo(timeout) > 0 ? timeout : requested;
    }

}
