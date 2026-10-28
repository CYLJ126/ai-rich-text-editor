package com.arte.app.platform.identity;

import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Set;

/**
 * FIXED 兼容核验：保留原 grant／scope／预算引用，只检查当前账户与公共稳定主体一致。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
public final class RbacFixedExecutionAuthorizationResolver implements ExecutionAuthorizationResolver {

    private final RbacIdentityProvider rbacIdentityProvider;
    private final FixedExecutionAuthorizationResolver fixedExecutionAuthorizationResolver;
    private final Scheduler scheduler;

    public RbacFixedExecutionAuthorizationResolver(RbacIdentityProvider rbacIdentityProvider, NewAiProperties properties, Scheduler scheduler) {
        this.rbacIdentityProvider = rbacIdentityProvider;
        this.fixedExecutionAuthorizationResolver = new FixedExecutionAuthorizationResolver(properties);
        this.scheduler = scheduler;
    }

    @Override
    public Mono<ExecutionAuthorization> resolve(String name, String tenantId, String workspaceId, Set<String> scopes) {
        return Mono.fromCallable(() -> rbacIdentityProvider.resolveAuthenticatedName(name)
                        .filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                        .orElseThrow(() -> new AccessDeniedException("Account unavailable")))
                .subscribeOn(scheduler)
                .onErrorMap(PlatformException.class, failure -> new AccessDeniedException("Stable identity unavailable"))
                .flatMap(principal -> fixedExecutionAuthorizationResolver.resolve(name, tenantId, workspaceId, scopes).map(authorization -> {
                    if (authorization.principal().kind() != ExecutionPrincipal.Kind.USER
                            || !authorization.principal().sameIdentityAs(principal)) {
                        throw new AccessDeniedException("Stable identity mismatch");
                    }
                    return authorization;
                }));
    }
}
