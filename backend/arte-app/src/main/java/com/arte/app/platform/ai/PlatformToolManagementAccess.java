package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_AI_USE;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;

/**
 * TOOL 消费当前公共身份／空间和原 RBAC；不依赖模型目录、模型绑定或预算账户。
 * 当前 AI 开关从既有空间配置／FIXED grants 读取，最终检查在工具持久化原事务执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class PlatformToolManagementAccess implements ToolManagementAccess {
    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;
    private final PublicTransactionRunner transactions;
    private final WorkspaceWriteGuard guard;
    private final AuthorizationService authorization;
    private final PlatformAiControlMapper configuration;
    private final PlatformJson json;
    private final NewAiProperties properties;
    private final boolean databaseMode;
    private final Scheduler scheduler;
    private final Clock clock;

    public PlatformToolManagementAccess(RbacIdentityProvider identities, RbacToolPermissionReader permissions,
                                        PublicTransactionRunner transactions, WorkspaceWriteGuard guard, AuthorizationService authorization,
                                        PlatformAiControlMapper configuration, PlatformJson json, NewAiProperties properties,
                                        boolean databaseMode, Scheduler scheduler, Clock clock) {
        this.identities = identities;
        this.permissions = permissions;
        this.transactions = transactions;
        this.guard = guard;
        this.authorization = authorization;
        this.configuration = configuration;
        this.json = json;
        this.properties = properties;
        this.databaseMode = databaseMode;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public Session capture(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof JwtUserDto(
                UserOnlineInfo online
        )))
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException("Authenticated account required");
        // 只复制会话身份，不复制 token 中可能已过时的权限、角色或可变对象。
        var copy = new UserOnlineInfo().setId(online.getId()).setUserName(online.getUserName()).setAccountCreatedAt(online.getAccountCreatedAt());
        var captured = UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(copy), null, List.of());
        return (scope, operation) -> Mono.defer(() -> {
            var deadline = clock.instant().plus(Duration.ofSeconds(30));
            return Mono.fromCallable(() -> transactions.required(() -> {
                var actor = identities.actor(captured);
                var scopes = check(captured, actor, scope, operation, Set.of(), deadline);
                return (Permit) new CurrentPermit(captured, actor, scope, operation, scopes, deadline);
            })).subscribeOn(scheduler).timeout(Duration.ofSeconds(30));
        });
    }

    private final class CurrentPermit implements Permit {
        private final Authentication login;
        private final TrustedActor actor;
        private final Scope scope;
        private final Operation operation;
        private final Set<String> scopes;
        private final Instant deadline;

        private CurrentPermit(Authentication login, TrustedActor actor, Scope scope, Operation operation, Set<String> scopes, Instant deadline) {
            this.login = login;
            this.actor = actor;
            this.scope = scope;
            this.operation = operation;
            this.scopes = scopes;
            this.deadline = deadline;
        }

        public TrustedActor actor() {
            return actor;
        }

        public ExecutionOwner owner() {
            return new ExecutionOwner(scope.tenantId(), scope.workspaceId(), actor.principal().subjectId());
        }

        public Operation operation() {
            return operation;
        }

        public Set<String> executionScopes() {
            return scopes;
        }

        public void requireInCurrentTransaction(Set<String> requiredScopes) {
            check(login, actor, scope, operation, Set.copyOf(requiredScopes), deadline);
        }
    }

    private Set<String> check(Authentication login, TrustedActor expected, Scope scope, Operation operation, Set<String> required, Instant deadline) {
        transactions.requireWritable();
        if (!clock.instant().isBefore(deadline))
            throw new com.arte.ainew.application.support.AdmissionException(com.arte.core.enums.ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        var actor = identities.actor(login);
        if (!actor.principal().equals(expected.principal())) throw denied();
        var action = operation == Operation.BINDING_WRITE ? WORKSPACE_AI_USE : WORKSPACE_READ;
        return guard.inCurrentTransaction(actor, List.of(DefaultAuthorizationService.workspaceRequest(scope, action, "ai-tool-management")), () -> {
            if (!identities.actorCurrent(login).principal().equals(actor.principal())) throw denied();
            var operations = permissions.current(login.getName());
            var requiredOperation = switch (operation) {
                case LIST -> "aiTool:list";
                case BINDING_WRITE, BINDING_DISABLE -> "aiTool:invoke";
                case MANAGE -> "aiTool:manage";
            };
            if (!operations.contains("admin") && (!operations.contains(requiredOperation)
                    || operation == Operation.MANAGE && !operations.contains("aiConfiguration:manage"))) throw denied();
            Set<String> scopes;
            if (databaseMode) {
                boolean aiEnabled = enabled(scope);
                boolean mayInvoke = false;
                if (aiEnabled) {
                    try {
                        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "ai-tool-eligibility"));
                        mayInvoke = true;
                    } catch (PlatformException error) {
                        if (error.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) throw error;
                    }
                }
                if (operation == Operation.BINDING_WRITE && !mayInvoke) throw denied();
                scopes = mayInvoke ? Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.INVOKE) : Set.of(AdmissionAuthorization.READ);
            } else {
                var grant = properties.grants().stream().filter(g -> g.enabled() && g.subjectName().equals(login.getName())
                        && g.subjectId().equals(actor.principal().subjectId()) && g.tenantId().equals(scope.tenantId())
                        && g.workspaceId().equals(scope.workspaceId()) && g.principalKind() == com.arte.ainew.common.execution.ExecutionPrincipal.Kind.USER).findFirst().orElseThrow(PlatformToolManagementAccess::denied);
                scopes = grant.scopes();
                if (!scopes.contains(operation == Operation.BINDING_WRITE ? AdmissionAuthorization.INVOKE : AdmissionAuthorization.READ))
                    throw denied();
            }
            if (!scopes.containsAll(required)) throw denied();
            return scopes;
        });
    }

    private boolean enabled(Scope scope) {
        var row = configuration.workspace(scope.tenantId(), scope.workspaceId());
        if (row == null) return false;
        var value = json.read(row.getPayload(), AiControlData.WorkspaceConfiguration.class);
        if (!value.scope().equals(scope) || value.version() != row.getVersion())
            throw new IllegalStateException("Tool AI configuration identity mismatch");
        return value.preset().enabled();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Tool management access denied");
    }
}
