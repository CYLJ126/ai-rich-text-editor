package com.arte.ainew.web;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.context.ExecutionContextRequest;
import com.arte.ainew.web.request.ConversationRequests;
import com.arte.core.diagnostics.RequestObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;

/**
 * HTTP 上下文管理：创建
 * <p>
 * HTTP 入口显式选择所需权限，委托给 ExecutionContextFactory 创建出所需上下文。
 * 必须在 MVC 请求线程调用，身份与范围由授权解析器验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 16:28 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "enabled", havingValue = "true")
public final class NewAiHttpContext {

    private final ExecutionContextFactory executionContextFactory;
    private final NewAiProperties properties;

    public NewAiHttpContext(ExecutionContextFactory executionContextFactory, NewAiProperties properties) {
        this.executionContextFactory = executionContextFactory;
        this.properties = properties;
    }

    public <T> Mono<T> read(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                            boolean includeConfiguration, java.util.function.Function<ExecutionContext, Mono<T>> work) {
        validate(scope, timeout, null, null);
        return RequestObservation.observe(executionContextFactory.readCurrent(new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, RequestObservation.currentTraceId(), null, properties.releaseRef(), null), includeConfiguration, work));
    }

    public Mono<ExecutionContext> create(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                                         String budgetRef, String idempotencyKey) {
        validate(scope, timeout, budgetRef, idempotencyKey);
        return RequestObservation.observe(executionContextFactory.createCurrent(new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, RequestObservation.currentTraceId(), budgetRef, properties.releaseRef(), idempotencyKey)));
    }

    public <T> Mono<T> prepare(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                               String budgetRef, String idempotencyKey,
                               java.util.function.Function<ExecutionContext, Mono<T>> work) {
        validate(scope, timeout, budgetRef, idempotencyKey);
        return RequestObservation.observe(executionContextFactory.prepareCurrent(new ExecutionContextRequest(
                scope.tenantId(), scope.workspaceId(), scopes, timeout, RequestObservation.currentTraceId(),
                budgetRef, properties.releaseRef(), idempotencyKey), work));
    }

    private void validate(ConversationRequests.Scope scope, Duration timeout, String budgetRef, String idempotencyKey) {
        ContractChecks.id(scope.tenantId(), "tenantId");
        ContractChecks.id(scope.workspaceId(), "workspaceId");
        ContractChecks.optionalId(budgetRef, "budgetRef");
        ContractChecks.optionalId(idempotencyKey, "idempotencyKey");
        ContractChecks.require(timeout != null && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                && timeout.compareTo(properties.limits().maximumTimeout()) <= 0, "Timeout exceeds execution limits");
    }

}
