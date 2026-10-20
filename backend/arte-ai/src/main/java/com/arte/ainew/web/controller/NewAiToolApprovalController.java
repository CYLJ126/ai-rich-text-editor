package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.approval.ToolApprovalService;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.ainew.web.request.ToolApprovalRequests;
import com.arte.ainew.web.response.ToolApprovalResponse;
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
 * 当前参与者的待办、固定内容及幂等决定；Scope 与编号不授予权限，无 resumeToken。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled", "ai-new-tool-approval.enabled"}, havingValue = "true")
public class NewAiToolApprovalController {

    private final ObjectProvider<ToolApprovalService> services;
    private final NewAiHttpContext contexts;
    private final Duration timeout;

    public NewAiToolApprovalController(ObjectProvider<ToolApprovalService> services, NewAiHttpContext contexts, NewAiProperties properties) {
        this.services = services;
        this.contexts = contexts;
        timeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0 ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    private ToolApprovalService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new IllegalStateException("Current host approval access required");
        return service;
    }

    private Mono<com.arte.ainew.common.execution.ExecutionContext> context(com.arte.ainew.web.request.ToolManagementRequests.ToolScope scope, String key) {
        return contexts.create(new ConversationRequests.Scope(scope.tenantId(), scope.workspaceId()), Set.of(AdmissionAuthorization.READ), timeout, null, key);
    }

    @PostMapping("/getApproval")
    public Mono<ResultContext<ToolApprovalResponse>> get(@Valid @RequestBody ToolApprovalRequests.Get request, Locale locale) {
        return context(request.scope(), null).flatMap(context -> service().get(request.approvalId(), request.invocationId(), context)
                .map(value -> ResultContext.success(ToolApprovalResponse.from(value, context), ResultCodeEnum.SUCCESS, locale)));
    }

    @PostMapping("/listPendingApprovals")
    public Mono<ResultContext<ToolApprovalResponse.Page>> list(@Valid @RequestBody ToolApprovalRequests.ListPending request, Locale locale) {
        return context(request.scope(), null).flatMap(context -> service().list(request.cursor(), request.limit() == null ? 10 : request.limit(), context))
                .map(page -> ResultContext.success(new ToolApprovalResponse.Page(page.records().stream().map(ToolApprovalResponse.Summary::from).toList(), page.nextCursor(), page.exhausted()), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/decideApproval")
    public Mono<ResponseEntity<ResultContext<ToolApprovalResponse.Receipt>>> decide(@Valid @RequestBody ToolApprovalRequests.Decide request,
                                                                                    @RequestHeader("Idempotency-Key") String key, Locale locale) {
        ContractChecks.id(key, "Idempotency-Key");
        return context(request.scope(), key).flatMap(context -> service().decide(request.approvalId(), Long.parseLong(request.expectedVersion()),
                        request.subjectDigest(), request.decision(), key, context))
                .map(value -> ResponseEntity.accepted().body(ResultContext.success(ToolApprovalResponse.Receipt.from(value), ResultCodeEnum.SUCCESS, locale)));
    }
}
