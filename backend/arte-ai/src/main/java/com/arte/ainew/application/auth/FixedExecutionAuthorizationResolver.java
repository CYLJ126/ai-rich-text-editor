package com.arte.ainew.application.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiProperties.Grant;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;

/**
 * 从受信配置解析已认证名称和稳定主体，不复制客户端／Authentication 自报的角色或主体 ID。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class FixedExecutionAuthorizationResolver implements com.arte.ainew.spi.auth.ExecutionAuthorizationResolver {

    private final List<Grant> grants;

    public FixedExecutionAuthorizationResolver(NewAiProperties properties) {
        grants = properties.grants();
        ContractChecks.unique(grants.stream().map(g -> List.of(g.subjectName(), g.tenantId(), g.workspaceId())).toList(), "grant identities");
        ContractChecks.unique(grants.stream().map(g -> List.of(g.subjectId(), g.tenantId(), g.workspaceId())).toList(), "stable grant identities");
    }

    @Override
    public Mono<ExecutionAuthorization> resolve(String authenticatedName, String tenantId, String workspaceId, Set<String> requestedScopes) {
        return Mono.defer(() -> {
            var grant = grants.stream().filter(g -> g.enabled() && g.subjectName().equals(authenticatedName)
                            && g.tenantId().equals(tenantId) && g.workspaceId().equals(workspaceId)
                            && g.scopes().containsAll(requestedScopes)).findFirst()
                    .orElseThrow(() -> new AccessDeniedException("Execution authorization denied"));
            return Mono.just(new ExecutionAuthorization(new ExecutionPrincipal(grant.subjectId(), grant.subjectName(), grant.principalKind()),
                    grant.tenantId(), grant.workspaceId(), requestedScopes, grant.grantRef()));
        });
    }
}
