package com.arte.ainew.web;

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

    public Mono<ExecutionContext> create(ConversationRequests.Scope scope, Set<String> scopes, Duration timeout,
                                         String budgetRef, String idempotencyKey) {
        ContractChecks.id(scope.tenantId(), "tenantId");
        ContractChecks.id(scope.workspaceId(), "workspaceId");
        ContractChecks.optionalId(budgetRef, "budgetRef");
        ContractChecks.optionalId(idempotencyKey, "idempotencyKey");
        ContractChecks.require(timeout != null && timeout.compareTo(Duration.ofSeconds(1)) >= 0
                && timeout.compareTo(properties.limits().maximumTimeout()) <= 0, "Timeout exceeds execution limits");
        return executionContextFactory.createCurrent(new ExecutionContextRequest(scope.tenantId(), scope.workspaceId(), scopes,
                timeout, null, budgetRef, properties.releaseRef(), idempotencyKey));
    }
}
