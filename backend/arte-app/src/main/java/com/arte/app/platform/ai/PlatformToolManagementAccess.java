package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
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
import com.arte.core.enums.platform.PrincipalStatusEnum;
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

    /**
     * 独立调用／Worker 沿用同一当前资格门控；输入必须是可信入口或耐久记录产生的上下文，不接收客户端主体。
     * 与管理写入共享账户、RBAC、空间及 AI 开关检查；免费和领域资源资格由执行适配另外明确批准。
     */
    public Mono<Permit> authorizeUse(ExecutionContext context) {
        return Mono.defer(() -> {
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isZero() || remaining.isNegative()) return Mono.error(denied());
            return Mono.fromCallable(() -> transactions.required(() -> {
                var actor = useActor(context);
                var scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
                var scopes = checkUse(context, actor, scope, Set.of(AdmissionAuthorization.INVOKE));
                return (Permit) new Permit() {
                    public TrustedActor actor() {
                        return actor;
                    }

                    public ExecutionOwner owner() {
                        return ExecutionOwner.from(context);
                    }

                    public Operation operation() {
                        return Operation.BINDING_WRITE;
                    }

                    public Set<String> executionScopes() {
                        return scopes;
                    }

                    public void requireInCurrentTransaction(Set<String> required) {
                        checkUse(context, actor, scope, required);
                    }
                };
            })).subscribeOn(scheduler).timeout(remaining).onErrorMap(PlatformException.class, error -> switch (error.getCode()) {
                // 当前资格明确丧失应在派发前收尾；权威不可用等基础设施故障仍保留原异常，不能伪装成撤权。
                case UNAUTHENTICATED, ACCESS_DENIED, POLICY_DENIED, NOT_FOUND, SPACE_READ_ONLY, SPACE_CLOSING,
                     CAPABILITY_NOT_ENABLED, IDENTITY_CONFLICT, TENANT_INACTIVE -> denied();
                default -> error;
            });
        });
    }

    private TrustedActor useActor(ExecutionContext context) {
        var expected = context.authorization().principal();
        if (expected.kind() != ExecutionPrincipal.Kind.USER || !context.authorization().scopes().contains(AdmissionAuthorization.INVOKE))
            throw denied();
        var principal = identities.findBySubjectId(expected.subjectId()).filter(value -> value.status() == PrincipalStatusEnum.ACTIVE)
                .orElseThrow(PlatformToolManagementAccess::denied);
        return new TrustedActor(principal, clock.instant());
    }

    private Set<String> checkUse(ExecutionContext context, TrustedActor actor, Scope scope, Set<String> required) {
        return checkCurrent(actor, context.authorization().principal().subjectName(), scope, Operation.BINDING_WRITE, required,
                context.deadline(), () -> {
                    var current = identities.findCurrentExecutionPrincipal(context.authorization().principal()).orElseThrow(PlatformToolManagementAccess::denied);
                    if (!current.equals(actor.principal()) || current.status() != PrincipalStatusEnum.ACTIVE)
                        throw denied();
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
        return checkCurrent(actor, login.getName(), scope, operation, required, deadline, () -> {
            if (!identities.actorCurrent(login).principal().equals(actor.principal())) throw denied();
        });
    }

    /**
     * 管理会话与可信执行复用当前权限规则，主体证据在公共锁内分别复核。
     */
    private Set<String> checkCurrent(TrustedActor actor, String subjectName, Scope scope, Operation operation,
                                     Set<String> required, Instant deadline, Runnable identityProof) {
        transactions.requireWritable();
        if (!clock.instant().isBefore(deadline)) throw denied();
        var action = operation == Operation.BINDING_WRITE ? WORKSPACE_AI_USE : WORKSPACE_READ;
        return guard.inCurrentTransaction(actor, List.of(DefaultAuthorizationService.workspaceRequest(scope, action, "ai-tool-management")), () -> {
            identityProof.run();
            var operations = permissions.current(subjectName);
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
                // 写入门控刚以同一事务的当前事实验证过 AI_USE，不再退出授权批次后重复整轮锁读。
                boolean mayInvoke = aiEnabled && operation == Operation.BINDING_WRITE;
                if (aiEnabled && operation != Operation.BINDING_WRITE) {
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
                var grant = properties.grants().stream().filter(g -> g.enabled() && g.subjectName().equals(subjectName)
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
