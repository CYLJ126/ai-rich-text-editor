package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.BudgetAccountQueryService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.BudgetRequests;
import com.arte.ainew.web.response.BudgetAccountResponse;
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
 * 查询预算余额、预留和费用情况
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 21:38 ✾
 **/
@RestController
@RequestMapping("/ai-new/budget")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public class NewAiBudgetController {

    private final BudgetAccountQueryService budgetQueryService;
    private final NewAiHttpContext httpContext;
    private final Duration queryTimeout;

    public NewAiBudgetController(BudgetAccountQueryService budgetQueryService, NewAiHttpContext httpContext, NewAiProperties properties) {
        this.budgetQueryService = budgetQueryService;
        this.httpContext = httpContext;
        this.queryTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/getBudget")
    public Mono<ResultContext<BudgetAccountResponse>> getBudget(
            @Valid @RequestBody BudgetRequests.Query request, Locale locale) {
        // 在请求线程捕获真实身份；读取不需要预算管理权限或幂等键。
        return httpContext.create(request.scope(), Set.of(AdmissionAuthorization.READ), queryTimeout, null, null)
                .flatMap(context -> budgetQueryService.account(request.budgetRef(), context))
                .map(account -> ResultContext.success(BudgetAccountResponse.from(account), ResultCodeEnum.SUCCESS, locale));
    }
}
