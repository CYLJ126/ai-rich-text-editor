package com.arte.app.security.bridge;

import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 新入口的组合边界。只接受空间／操作选择，不接受请求体自报主体或完整 ExecutionContext。
 * applicationId／bindingId 由所属入口从受控配置选定；持久化策略逐项限制所请求动作。
 */
public class ExecutionContextFactory {
    private final ExistingIdentityAdapter identity;
    private final JdbcSecurityRepository repository;
    private final Clock clock;
    private final Duration taskLifetime;

    public ExecutionContextFactory(ExistingIdentityAdapter identity, JdbcSecurityRepository repository,
                                   Clock clock, Duration taskLifetime) {
        if (taskLifetime == null || taskLifetime.isNegative() || taskLifetime.isZero()) {
            throw new IllegalArgumentException("taskLifetime must be positive");
        }
        this.identity = identity;
        this.repository = repository;
        this.clock = clock;
        this.taskLifetime = taskLifetime;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionContext create(HttpServletRequest request, String tenantId, String workspaceId,
                                   String applicationId, String bindingId, Set<String> requestedActions) {
        return create(request, tenantId, workspaceId, applicationId, bindingId, requestedActions, Map.of());
    }

    /**
     * 资料范围按完整引用及动作固定；空范围只适用于不读取业务资源的请求。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExecutionContext create(HttpServletRequest request, String tenantId, String workspaceId,
                                   String applicationId, String bindingId, Set<String> requestedActions,
                                   Map<ResourceRef, Set<String>> requestedResourceActions) {
        ContractChecks.identifier(applicationId, "applicationId");
        ContractChecks.identifier(bindingId, "bindingId");
        Set<String> actions = ContractChecks.identifiers(requestedActions, "requestedActions");
        ContractChecks.required(requestedResourceActions, "requestedResourceActions");
        Map<ResourceRef, Set<String>> resourceActions = new HashMap<>();
        requestedResourceActions.forEach((resource, codes) -> {
            ContractChecks.required(resource, "task resource");
            var checked = ContractChecks.identifiers(codes, "task resource actions");
            if (!actions.containsAll(checked))
                throw new IllegalArgumentException("resource actions exceed task actions");
            resourceActions.put(resource, checked);
        });
        var account = identity.requireAccount(request);
        var scope = new ExecutionScope(tenantId, workspaceId, account.principal());
        var member = repository.membership(tenantId, workspaceId, account.principal());
        if (member.isEmpty() || !member.get().enabled()) throw denied();
        for (String action : actions) {
            var policy = repository.applicationPolicy(tenantId, workspaceId, applicationId, bindingId, action);
            if (policy.isEmpty() || !policy.get().applicationEnabled() || !policy.get().bindingEnabled())
                throw denied();
        }
        var deadline = clock.instant().plus(taskLifetime);
        var context = new ExecutionContext(scope, UUID.randomUUID().toString(), null, deadline,
                null, actions, null, null, null);
        repository.saveTask(context, applicationId, bindingId, deadline, resourceActions);
        return context;
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("arte.security.scope_or_application_rejected");
    }
}
