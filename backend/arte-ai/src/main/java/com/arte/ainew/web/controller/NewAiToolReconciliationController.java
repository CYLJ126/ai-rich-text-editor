package com.arte.ainew.web.controller;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.outcome.ToolReconciliationService;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.tool.ToolReconciliation;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.web.request.ToolReconciliationRequests;
import com.arte.ainew.web.response.ToolReconciliationResponse;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Locale;

/**
 * 人工核对只确认原操作事实，绝不触发新执行或费用结算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
@RestController
@RequestMapping("/ai-new/tool")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled", "ai-new-execution.enabled", "ai-new-tool-outcome.enabled"}, havingValue = "true")
public class NewAiToolReconciliationController {

    private final ObjectProvider<ToolReconciliationService> services;
    private final ToolManagementAccess access;

    public NewAiToolReconciliationController(ObjectProvider<ToolReconciliationService> services, ToolManagementAccess access) {
        this.services = services;
        this.access = access;
    }

    private ToolReconciliationService service() {
        var service = services.getIfAvailable();
        if (service == null)
            throw new AdmissionException(ResultCodeEnum.AI_TOOL_MANAGEMENT_UNAVAILABLE);
        return service;
    }

    @PostMapping("/getReconciliation")
    public Mono<ResultContext<ToolReconciliationResponse>> get(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Get q, Locale locale) {
        return service().get(access.capture(auth), q.scope().value(), q.invocationId(), Boolean.TRUE.equals(q.managed()))
                .map(d -> ResultContext.success(ToolReconciliationResponse.from(d), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/listReconciliations")
    public Mono<ResultContext<ToolReconciliationResponse.Page>> list(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Page q, Locale locale) {
        return service().list(access.capture(auth), q.scope().value(), q.cursor(), q.limit() == null ? 10 : q.limit())
                .map(p -> ResultContext.success(new ToolReconciliationResponse.Page(p.records().stream().map(ToolReconciliationResponse::from).toList(), p.nextCursor(), p.exhausted()), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/getOutcomeEvidence")
    public Mono<ResultContext<ToolReconciliationResponse.Evidence>> evidence(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Get q, Locale locale) {
        return service().evidence(access.capture(auth), q.scope().value(), q.invocationId())
                .map(e -> ResultContext.success(ToolReconciliationResponse.Evidence.from(e), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/claimReconciliation")
    public Mono<ResultContext<ToolReconciliation.Receipt>> claim(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Command q, @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return command(auth, q, key, ToolReconciliation.Action.CLAIM, locale);
    }

    @PostMapping("/requestOutcomeCheck")
    public Mono<ResultContext<ToolReconciliation.Receipt>> requestCheck(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Command q, @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return command(auth, q, key, ToolReconciliation.Action.REQUEST_CHECK, locale);
    }

    @PostMapping("/confirmOutcome")
    public Mono<ResultContext<ToolReconciliation.Receipt>> confirm(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Command q, @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return command(auth, q, key, ToolReconciliation.Action.CONFIRM, locale);
    }

    @PostMapping("/markOutcomeUnresolvable")
    public Mono<ResultContext<ToolReconciliation.Receipt>> unresolvable(Authentication auth, @Valid @RequestBody ToolReconciliationRequests.Command q, @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return command(auth, q, key, ToolReconciliation.Action.UNRESOLVABLE, locale);
    }

    private Mono<ResultContext<ToolReconciliation.Receipt>> command(Authentication auth, ToolReconciliationRequests.Command q, String key, ToolReconciliation.Action action, Locale locale) {
        ContractChecks.id(key, "Idempotency-Key");
        return service().command(access.capture(auth), q.scope().value(), key, q.value(action)).map(r -> ResultContext.success(r, ResultCodeEnum.SUCCESS, locale));
    }
}
