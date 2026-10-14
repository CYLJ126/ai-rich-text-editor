package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.platform.CommandReceiptQueryService;
import com.arte.core.api.workspace.*;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.VerifiedAccess;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 可信会话到公共 API 的响应式适配，不实现空间领域规则。
 * 调用时复制最小会话身份；订阅后在有界数据库线程核验当前身份并执行完整同步调用。
 * 不读取 UserContext，不缓存权限、不自动重试或改变 operationKey。
 * 取消订阅不能保证撤销已执行的 JDBC 命令，结果不确定时按原键查询／重试。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:22 ✾
 */
public final class ReactiveWorkspaceOperations {

    private final RbacIdentityProvider identities;
    private final PlatformPersistenceExecutor executor;
    private final PersonalWorkspaceService personal;
    private final WorkspaceService workspaces;
    private final WorkspacePreferenceService preferences;
    private final ScopeResolver scopes;
    private final AuthorizationService authorization;
    private final WorkspaceWriteGuard guard;
    private final CommandReceiptQueryService receipts;
    private final WorkspacePolicyService policies;
    private final WorkspaceDirectoryBrowser browser;

    public ReactiveWorkspaceOperations(RbacIdentityProvider identities, PlatformPersistenceExecutor executor,
                                       PersonalWorkspaceService personal, WorkspaceService workspaces, WorkspacePreferenceService preferences,
                                       ScopeResolver scopes, AuthorizationService authorization, WorkspaceWriteGuard guard,
                                       CommandReceiptQueryService receipts, WorkspacePolicyService policies,
                                       WorkspaceDirectoryBrowser browser) {
        this.identities = identities;
        this.executor = executor;
        this.personal = personal;
        this.workspaces = workspaces;
        this.preferences = preferences;
        this.scopes = scopes;
        this.authorization = authorization;
        this.guard = guard;
        this.receipts = receipts;
        this.policies = policies;
        this.browser = browser;
    }

    public Mono<CommandReceipt<WorkspaceViews.PersonalWorkspaceView>> initializePersonal(Authentication authentication, CommandMeta meta) {
        return call(authentication, actor -> personal.initialize(actor, meta));
    }

    public Mono<PageResult<WorkspaceViews.WorkspaceSummary>> listAccessible(Authentication authentication, PageQuery query) {
        return call(authentication, actor -> workspaces.listAccessible(actor, query));
    }

    public Mono<WorkspaceDirectoryBrowser.Page> browseAccessible(Authentication authentication, String token, WorkspaceBrowseQuery query) {
        return call(authentication, actor -> browser.browse(actor, token, query));
    }

    public Mono<Long> countAccessible(Authentication authentication) {
        return call(authentication, workspaces::countAccessible);
    }

    public Mono<WorkspaceViews.WorkspaceView> get(Authentication authentication, Scope scope) {
        return call(authentication, actor -> workspaces.get(actor, scope));
    }

    public Mono<CommandReceipt<WorkspaceViews.WorkspaceView>> create(Authentication authentication, WorkspaceCommands.CreateWorkspace command) {
        return call(authentication, actor -> workspaces.create(actor, command));
    }

    public Mono<CommandReceipt<WorkspaceViews.WorkspaceView>> rename(Authentication authentication, WorkspaceCommands.RenameWorkspace command) {
        return call(authentication, actor -> workspaces.rename(actor, command));
    }

    public Mono<WorkspaceViews.PreferenceView> getPreference(Authentication authentication) {
        return call(authentication, preferences::get);
    }

    public Mono<CommandReceipt<?>> getCommandReceipt(Authentication authentication, String operationId) {
        return call(authentication, actor -> receipts.get(actor, operationId));
    }

    public Mono<CommandReceipt<WorkspaceViews.PreferenceView>> updatePreference(Authentication authentication, WorkspaceCommands.UpdatePreference command) {
        return call(authentication, actor -> preferences.update(actor, command));
    }

    public Mono<WorkspacePolicyViews.TenantPolicyView> getTenantPolicy(Authentication authentication, String tenantId) {
        return call(authentication, actor -> policies.getTenantPolicyView(actor, tenantId));
    }

    public Mono<WorkspacePolicyViews.WorkspacePolicyView> getWorkspacePolicy(Authentication authentication, Scope scope) {
        return call(authentication, actor -> policies.getWorkspacePolicyView(actor, scope));
    }

    public Mono<CommandReceipt<TenantPolicy>> updateTenantPolicy(Authentication authentication, TenantCommands.UpdateTenantPolicy command) {
        return call(authentication, actor -> policies.updateTenantPolicy(actor, command));
    }

    public Mono<CommandReceipt<WorkspacePolicy>> updateWorkspacePolicy(Authentication authentication, WorkspaceCommands.UpdateWorkspacePolicy command) {
        return call(authentication, actor -> policies.updateWorkspacePolicy(actor, command));
    }

    public Mono<VerifiedScope> verifyScope(Authentication authentication, Scope scope) {
        return call(authentication, actor -> scopes.verify(actor, scope));
    }

    public Mono<AccessDecision> check(Authentication authentication, AccessRequest request) {
        return call(authentication, actor -> authorization.check(actor, request));
    }

    public Mono<VerifiedAccess> require(Authentication authentication, AccessRequest request) {
        return call(authentication, actor -> authorization.require(actor, request));
    }

    public Mono<List<AccessDecision>> batchCheck(Authentication authentication, List<AccessRequest> requests) {
        var snapshot = List.copyOf(requests);
        return call(authentication, actor -> authorization.batchCheck(actor, snapshot));
    }

    /**
     * 组合业务数据库写入：整个 work 加入物理 app 库事务，同线程门控、当前读和提交。
     * work 仅执行同步本地操作，不能返回 Publisher、切线程、block 或等待网络。
     * Mono<Void>／空返回值仍正常完成事务；回调失败整体回滚。
     */
    public <T> Mono<T> inWorkspaceTransaction(Authentication authentication, List<AccessRequest> requests, Supplier<T> work) {
        var targets = List.copyOf(requests);
        return schedule(authentication, actor -> guard.inCurrentTransaction(actor, targets, () -> {
            T result = work.get();
            if (result instanceof org.reactivestreams.Publisher<?> || result instanceof java.util.concurrent.CompletionStage<?>) {
                throw new IllegalArgumentException("Workspace transaction callback must finish synchronously");
            }
            return result;
        }), true);
    }

    private <T> Mono<T> call(Authentication authentication, Function<TrustedActor, T> work) {
        // 普通命令由 core 自己开启事务；不在读查询外增加长事务。
        return schedule(authentication, work, false);
    }

    private <T> Mono<T> schedule(Authentication authentication, Function<TrustedActor, T> work, boolean transaction) {
        final Authentication snapshot;
        try {
            snapshot = capture(authentication);
        } catch (PlatformException rejected) {
            return Mono.error(rejected);
        }
        Supplier<T> invocation = () -> work.apply(identities.actor(snapshot));
        return (transaction ? executor.inTransaction(invocation) : executor.read(invocation))
                .onErrorMap(java.util.concurrent.RejectedExecutionException.class,
                        rejected -> new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, UUID.randomUUID().toString()));
    }

    private static Authentication capture(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtUserDto(UserOnlineInfo online))
                || online.getId() == null || online.getAccountCreatedAt() == null
                || !java.util.Objects.equals(authentication.getName(), online.getUserName())) {
            throw new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, UUID.randomUUID().toString());
        }
        var copy = new UserOnlineInfo().setId(online.getId()).setUserName(online.getUserName())
                .setAccountCreatedAt(online.getAccountCreatedAt());
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(copy), null, List.of());
    }
}
