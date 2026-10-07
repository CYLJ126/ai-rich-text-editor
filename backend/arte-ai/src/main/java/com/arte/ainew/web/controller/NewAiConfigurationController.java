package com.arte.ainew.web.controller;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.request.ConfigurationRequests;
import com.arte.ainew.web.response.ChatConfigurationResponse;
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
 * 当前用户可用的文本聊天配置发现入口；查询不触发执行或预算操作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/8 05:52 ✾
 */
@RestController
@RequestMapping("/ai-new/configuration")
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public class NewAiConfigurationController {
    private final ChatConfigurationQueryService configurations;
    private final NewAiHttpContext httpContext;
    private final Duration queryTimeout;

    public NewAiConfigurationController(ChatConfigurationQueryService configurations, NewAiHttpContext httpContext, NewAiProperties properties) {
        this.configurations = configurations;
        this.httpContext = httpContext;
        this.queryTimeout = properties.limits().maximumTimeout().compareTo(Duration.ofSeconds(30)) < 0
                ? properties.limits().maximumTimeout() : Duration.ofSeconds(30);
    }

    @PostMapping("/discoverChatOptions")
    public Mono<ResultContext<ChatConfigurationResponse>> discoverChatOptions(
            @Valid @RequestBody ConfigurationRequests.DiscoverChatOptions request, Locale locale) {
        // 必须在 MVC 请求线程捕获身份；不需要预算引用或幂等键。
        return httpContext.create(request.scope(), Set.of(AdmissionAuthorization.INVOKE), queryTimeout, null, null)
                .flatMap(configurations::discover)
                .map(options -> ResultContext.success(ChatConfigurationResponse.from(options), ResultCodeEnum.SUCCESS, locale));
    }
}
