package com.arte.ainew.web;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.web.request.ConversationRequests;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

/**
 * 在 MVC 请求线程捕获真实 Authentication；后台线程只使用已解析上下文。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class ConversationHttpContext {
    private final ExecutionContextFactory factory;
    private final NewAiProperties properties;

    public ConversationHttpContext(ExecutionContextFactory factory, NewAiProperties properties) {
        this.factory = factory;
        this.properties = properties;
    }

    public Mono<ExecutionContext> create(ConversationRequests.Scope scope, String idempotencyKey) {
        ContractChecks.id(scope.tenantId(), "tenantId");
        ContractChecks.id(scope.workspaceId(), "workspaceId");
        ContractChecks.optionalId(idempotencyKey, "idempotencyKey");
        var timeout = Duration.ofSeconds(30).compareTo(properties.limits().maximumTimeout()) <= 0
                ? Duration.ofSeconds(30) : properties.limits().maximumTimeout();
        return factory.createCurrent(new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(),
                Set.of(AdmissionAuthorization.CONVERSATION), timeout, null, null, properties.releaseRef(), idempotencyKey));
    }
}
