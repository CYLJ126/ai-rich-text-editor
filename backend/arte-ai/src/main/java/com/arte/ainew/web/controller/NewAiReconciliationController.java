package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.budget.ManualReconciliationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ReconciliationRequests;
import com.arte.ainew.web.response.ReconciliationResponses;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.ResultContext;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;

/**
 * 对账控制器
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 10:20 ✾
 */
@RestController
@RequestMapping("/ai-new/budget")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public class NewAiReconciliationController {

    private final ManualReconciliationService manualReconciliationService;
    private final NewAiHttpContext newAiHttpContext;
    private final Duration timeout;

    public NewAiReconciliationController(ManualReconciliationService manualReconciliationService, NewAiHttpContext newAiHttpContext, NewAiProperties properties) {
        this.manualReconciliationService = manualReconciliationService;
        this.newAiHttpContext = newAiHttpContext;
        this.timeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/pendingReconciliations")
    public Mono<ResultContext<ReconciliationResponses.PendingPage>> pending(@Valid @RequestBody ReconciliationRequests.Query request, Locale locale) {
        return newAiHttpContext.create(request.scope(), Set.of(AdmissionAuthorization.READ), timeout, null, null)
                .flatMap(context -> manualReconciliationService.pending(request.budgetRef(), request.current(), request.size(), context))
                .map(value -> ResultContext.success(ReconciliationResponses.PendingPage.from(value), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/confirmReconciliation")
    public Mono<ResultContext<ReconciliationResponses.Receipt>> confirm(@Valid @RequestBody ReconciliationRequests.Confirm request,
                                                                        @RequestHeader("Idempotency-Key") String key, Locale locale) {
        return newAiHttpContext.create(request.scope(), Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.BUDGET_ADMIN), timeout, null, key)
                .flatMap(context -> manualReconciliationService.confirm(request.budgetRef(), request.invocationId(), request.invocationVersion(),
                        request.reservationId(), request.reservationVersion(), new Money(new BigDecimal(request.actualCharge()), Currency.getInstance(request.currency())),
                        request.evidenceRef(), request.note(), request.executionEnded(), context))
                .map(value -> ResultContext.success(ReconciliationResponses.Receipt.from(value), ResultCodeEnum.SUCCESS, locale));
    }

    @PostMapping("/reconciliationReceipt")
    public Mono<ResultContext<ReconciliationResponses.Receipt>> receipt(@Valid @RequestBody ReconciliationRequests.ReceiptQuery request, Locale locale) {
        return newAiHttpContext.create(request.scope(), Set.of(AdmissionAuthorization.READ), timeout, null, null)
                .flatMap(context -> manualReconciliationService.receipt(request.budgetRef(), request.invocationId(), request.key(), context))
                .map(value -> ResultContext.success(ReconciliationResponses.Receipt.from(value), ResultCodeEnum.SUCCESS, locale));
    }
}
