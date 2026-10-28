package com.arte.app.platform.ai;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
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
import com.arte.core.pojo.platform.ExecutionOwner;
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
 * 工具管理及调用的宿主授权适配器，统一核验当前账户、RBAC 操作、空间资格及 AI 开关。
 * 支持登录会话和可信执行上下文；初检返回的 Permit 在实际写入时仍须于原事务内重新授权。
 * 身份事实仅在一次同步授权检查内复用，AI 资格来自空间配置或 FIXED grants，不依赖模型或预算账户。
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

    /**
     * 组合现有身份、权限、空间门控及配置读取能力；数据库检查由指定调度器执行。
     */
    public PlatformToolManagementAccess(RbacIdentityProvider identities, RbacToolPermissionReader permissions, PublicTransactionRunner transactions, WorkspaceWriteGuard guard, AuthorizationService authorization, PlatformAiControlMapper configuration, PlatformJson json, NewAiProperties properties, boolean databaseMode, Scheduler scheduler, Clock clock) {
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

    /**
     * 捕获登录身份的必要字段，返回可按目标空间和操作重新检查当前资格的会话。
     */
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
            // 每次订阅独立授权；仅在本次事务的同步检查内复用身份事实，返回 Permit 前清空。
            return Mono.fromCallable(() -> transactions.required(() -> identities.duringAuthorization(() -> {
                var actor = identities.actor(captured);
                var scopes = check(captured, actor, scope, operation, Set.of(), deadline);
                return (Permit) new CurrentPermit(captured, actor, scope, operation, scopes, deadline);
            }))).subscribeOn(scheduler).timeout(Duration.ofSeconds(30));
        });
    }

    /**
     * 根据可信执行上下文核验独立调用／Worker 的当前使用资格，受原调用期限约束。
     * 返回的 Permit 支持提交时再次检查；工具免费及领域资源资格由执行适配器另行批准。
     */
    public Mono<Permit> authorizeUse(ExecutionContext context) {
        return Mono.defer(() -> {
            // 授权等待也消耗原调用期限，不因重新检查而续期。
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isZero() || remaining.isNegative()) return Mono.error(denied());
            return Mono.fromCallable(() -> transactions.required(() -> identities.duringAuthorization(() -> {
                var actor = useActor(context);
                var scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
                var scopes = checkUse(context, actor, scope, Set.of(AdmissionAuthorization.INVOKE));
                return (Permit) new Permit() {
                    /**
                     * 返回本轮已核验的主体。
                     */
                    public TrustedActor actor() {
                        return actor;
                    }

                    /** 返回可信上下文限定的调用归属。 */
                    public ExecutionOwner owner() {
                        return ExecutionOwners.from(context);
                    }

                    /** 工具使用沿用绑定写入的资格规则。 */
                    public Operation operation() {
                        return Operation.BINDING_WRITE;
                    }

                    /** 返回初检时允许的执行权限范围。 */
                    public Set<String> executionScopes() {
                        return scopes;
                    }

                    /**
                     * 在调用方的实际写事务中重新检查当前资格及工具要求的权限。
                     */
                    public void requireInCurrentTransaction(Set<String> required) {
                        checkUse(context, actor, scope, required);
                    }
                };
            }))).subscribeOn(scheduler).timeout(remaining).onErrorMap(PlatformException.class, error -> switch (error.getCode()) {
                // 当前资格明确丧失应在派发前收尾；权威不可用等基础设施故障仍保留原异常，不能伪装成撤权。
                case UNAUTHENTICATED, ACCESS_DENIED, POLICY_DENIED, NOT_FOUND, SPACE_READ_ONLY, SPACE_CLOSING,
                     CAPABILITY_NOT_ENABLED, IDENTITY_CONFLICT, TENANT_INACTIVE -> denied();
                default -> error;
            });
        });
    }

    /**
     * 以可信上下文中的稳定主体 ID 查找当前启用账户，不将历史上下文直接当作当前资格。
     */
    private TrustedActor useActor(ExecutionContext context) {
        var expected = context.authorization().principal();
        if (expected.kind() != ExecutionPrincipal.Kind.USER || !context.authorization().scopes().contains(AdmissionAuthorization.INVOKE))
            throw denied();
        var principal = identities.findBySubjectId(expected.subjectId())
                .filter(value -> value.status() == PrincipalStatusEnum.ACTIVE).filter(expected::sameIdentityAs)
                .orElseThrow(PlatformToolManagementAccess::denied);
        return new TrustedActor(principal, clock.instant());
    }

    /**
     * 审批组合事务复用当前 AI／资源使用资格；调用前由审批适配器按序预锁完整主体集合。
     */
    public void requireUseInCurrentTransaction(ExecutionContext context, Set<String> scopes) {
        transactions.requireWritable();
        checkUse(context, useActor(context), new Scope(context.authorization().tenantId(), context.authorization().workspaceId()), scopes);
    }

    /**
     * 审批操作不由 admin 标记替代，当前公共成员门控、AI grant 与 RBAC 都必须通过。
     */
    public void requireApprovalReadInCurrentTransaction(ExecutionContext context, boolean independent) {
        requireApprovalOperation(context, independent ? Operation.APPROVE : Operation.LIST, Set.of(AdmissionAuthorization.READ));
    }

    /**
     * 本人决定的 HTTP 读取上下文不必预授予 INVOKE；仍在当前平台权威中独立要求完整调用资格。
     */
    public void requireConfirmationInCurrentTransaction(ExecutionContext context, Set<String> required) {
        var scopes = new java.util.HashSet<>(required);
        scopes.add(AdmissionAuthorization.INVOKE);
        requireApprovalOperation(context, Operation.BINDING_WRITE, Set.copyOf(scopes));
    }

    private void requireApprovalOperation(ExecutionContext context, Operation operation, Set<String> required) {
        transactions.requireWritable();
        var expected = context.authorization().principal();
        if (expected.kind() != ExecutionPrincipal.Kind.USER) throw denied();
        var principal = identities.findCurrentExecutionPrincipal(expected).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE).orElseThrow(PlatformToolManagementAccess::denied);
        var actor = new TrustedActor(principal, clock.instant());
        checkCurrent(actor, expected.subjectName(), new Scope(context.authorization().tenantId(), context.authorization().workspaceId()),
                operation, required, context.deadline(), () -> {
                    if (!identities.findCurrentExecutionPrincipal(expected).orElseThrow(PlatformToolManagementAccess::denied).equals(principal))
                        throw denied();
                });
    }

    /**
     * 复用公共资格规则，并在门控持锁期间核对执行主体的当前名称、状态及账户生命周期。
     */
    private Set<String> checkUse(ExecutionContext context, TrustedActor actor, Scope scope, Set<String> required) {
        return identities.duringAuthorization(() -> checkCurrent(actor, context.authorization().principal().subjectName(), scope, Operation.BINDING_WRITE, required, context.deadline(), () -> {
            var current = identities.findCurrentExecutionPrincipal(context.authorization().principal()).orElseThrow(PlatformToolManagementAccess::denied);
            if (!current.equals(actor.principal()) || current.status() != PrincipalStatusEnum.ACTIVE) throw denied();
        }));
    }

    /**
     * 管理会话的初检结果及提交重验入口；保存原会话证据，不作为永久有效的授权凭证。
     */
    private final class CurrentPermit implements Permit {
        private final Authentication login;
        private final TrustedActor actor;
        private final Scope scope;
        private final Operation operation;
        private final Set<String> scopes;
        private final Instant deadline;

        /**
         * 保存本次管理操作的身份、范围、初检权限和重验期限。
         */
        private CurrentPermit(Authentication login, TrustedActor actor, Scope scope, Operation operation, Set<String> scopes, Instant deadline) {
            this.login = login;
            this.actor = actor;
            this.scope = scope;
            this.operation = operation;
            this.scopes = scopes;
            this.deadline = deadline;
        }

        /**
         * 返回初检核验的主体，供持久化门控使用。
         */
        public TrustedActor actor() {
            return actor;
        }

        /**
         * 组合租户、空间和主体，限定工具配置归属。
         */
        public ExecutionOwner owner() {
            return new ExecutionOwner(scope.tenantId(), scope.workspaceId(), actor.principal().subjectId());
        }

        /**
         * 返回本次已请求授权的管理操作。
         */
        public Operation operation() {
            return operation;
        }

        /**
         * 返回初检时允许的执行权限，不代替提交时复核。
         */
        public Set<String> executionScopes() {
            return scopes;
        }

        /**
         * 在调用方原写事务内重验会话、当前资格及本次要求的权限。
         */
        public void requireInCurrentTransaction(Set<String> requiredScopes) {
            check(login, actor, scope, operation, Set.copyOf(requiredScopes), deadline);
        }
    }

    /**
     * 检查管理会话的期限和稳定主体，并在公共门控内以锁定当前读重新核验会话证据。
     */
    private Set<String> check(Authentication login, TrustedActor expected, Scope scope, Operation operation, Set<String> required, Instant deadline) {
        transactions.requireWritable();
        if (!clock.instant().isBefore(deadline))
            throw new com.arte.ainew.application.support.AdmissionException(com.arte.core.enums.ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        return identities.duringAuthorization(() -> {
            var actor = identities.actor(login);
            if (!actor.principal().equals(expected.principal())) throw denied();
            return checkCurrent(actor, login.getName(), scope, operation, required, deadline, () -> {
                // 初检身份一致仍不够；持锁后重查，拒绝已停用、改名或主键被复用的旧会话。
                if (!identities.actorCurrent(login).principal().equals(actor.principal())) throw denied();
            });
        });
    }

    /**
     * 共用资格门控：检查空间访问、当前 RBAC、AI 资格及要求的执行权限，返回允许的权限范围。
     * identityProof 由调用方提供，在公共门控持锁期间分别复核登录会话或可信执行主体。
     */
    private Set<String> checkCurrent(TrustedActor actor, String subjectName, Scope scope, Operation operation, Set<String> required, Instant deadline, Runnable identityProof) {
        transactions.requireWritable();
        if (!clock.instant().isBefore(deadline)) throw denied();
        var action = operation == Operation.BINDING_WRITE ? WORKSPACE_AI_USE : WORKSPACE_READ;
        return guard.inCurrentTransaction(actor, List.of(DefaultAuthorizationService.workspaceRequest(scope, action, "ai-tool-management")), () -> {
            identityProof.run();
            // 按操作检查现有 RBAC；admin 仅免除菜单操作检查，不能绕过身份和空间门控。
            var operations = permissions.current(subjectName);
            var requiredOperation = switch (operation) {
                case LIST -> "aiTool:list";
                case BINDING_WRITE, BINDING_DISABLE -> "aiTool:invoke";
                case MANAGE -> "aiTool:manage";
                case APPROVE -> "aiTool:approve";
                case RECONCILE -> "aiTool:reconcile";
            };
            if (operation == Operation.APPROVE || operation == Operation.RECONCILE ? !operations.contains(requiredOperation)
                    : !operations.contains("admin") && (!operations.contains(requiredOperation) || operation == Operation.MANAGE && !operations.contains("aiConfiguration:manage")))
                throw denied();
            if (operation == Operation.RECONCILE)
                authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope,
                        com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE, "ai-tool-reconciliation"));
            Set<String> scopes;
            if (databaseMode) {
                boolean aiEnabled = enabled(scope);
                if (operation == Operation.APPROVE && !aiEnabled) throw denied();
                // 写入门控刚以同一事务的当前事实验证过 AI_USE，不再退出授权批次后重复整轮锁读。
                boolean mayInvoke = aiEnabled && operation == Operation.BINDING_WRITE;
                if (aiEnabled && operation != Operation.BINDING_WRITE) {
                    try {
                        // 查看／停用等操作只要求可读；另行判断是否还能返回工具调用权限。
                        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_AI_USE, "ai-tool-eligibility"));
                        mayInvoke = true;
                    } catch (PlatformException error) {
                        if (error.getCode() == PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE) throw error;
                    }
                }
                if (operation == Operation.BINDING_WRITE && !mayInvoke) throw denied();
                scopes = mayInvoke ? Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.INVOKE) : Set.of(AdmissionAuthorization.READ);
            } else {
                // FIXED 模式须匹配同一主体和空间的启用 grant，不能仅凭菜单权限取得执行资格。
                var grant = properties.grants().stream().filter(g -> g.enabled() && g.subjectName().equals(subjectName) && g.subjectId().equals(actor.principal().subjectId()) && g.tenantId().equals(scope.tenantId()) && g.workspaceId().equals(scope.workspaceId()) && g.principalKind() == com.arte.ainew.common.execution.ExecutionPrincipal.Kind.USER).findFirst().orElseThrow(PlatformToolManagementAccess::denied);
                scopes = grant.scopes();
                if (!scopes.contains(operation == Operation.BINDING_WRITE ? AdmissionAuthorization.INVOKE : AdmissionAuthorization.READ))
                    throw denied();
            }
            // 当前允许范围必须覆盖工具或本次操作明确要求的全部权限。
            if (!scopes.containsAll(required)) throw denied();
            return scopes;
        });
    }

    /**
     * 读取指定空间的 AI 开关，并核对配置内容与存储行的空间、版本是否一致。
     */
    private boolean enabled(Scope scope) {
        var row = configuration.workspace(scope.tenantId(), scope.workspaceId());
        if (row == null) return false;
        var value = json.read(row.getPayload(), AiControlData.WorkspaceConfiguration.class);
        if (!value.scope().equals(scope) || value.version() != row.getVersion())
            throw new IllegalStateException("Tool AI configuration identity mismatch");
        return value.preset().enabled();
    }

    /**
     * 生成统一的工具管理授权拒绝异常。
     */
    private static AccessDeniedException denied() {
        return new AccessDeniedException("Tool management access denied");
    }
}
