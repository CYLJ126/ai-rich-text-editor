package com.arte.app.platform.ai;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;

/**
 * TOOL 历史及停止沿用当前公共身份、空间成员和 RBAC；不要求模型目录、预算或仍处于启用状态的工具。
 * AI 关闭阻止新调用，当前有读取／控制资格的本人仍能查询及收尾；取消在原写事务重验权限并持锁。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
public final class PlatformToolInvocationAccess implements ToolInvocationAccess {

    private final RbacIdentityProvider identities;
    private final RbacToolPermissionReader permissions;
    private final PublicTransactionRunner transactions;
    private final WorkspaceWriteGuard guard;
    private final NewAiProperties properties;
    private final boolean databaseMode;
    private final Scheduler scheduler;
    private final Clock clock;

    public PlatformToolInvocationAccess(RbacIdentityProvider identities, RbacToolPermissionReader permissions,
                                        PublicTransactionRunner transactions, WorkspaceWriteGuard guard, NewAiProperties properties,
                                        boolean databaseMode, Scheduler scheduler, Clock clock) {
        this.identities = identities;
        this.permissions = permissions;
        this.transactions = transactions;
        this.guard = guard;
        this.properties = properties;
        this.databaseMode = databaseMode;
        this.scheduler = scheduler;
        this.clock = clock;
    }

    @Override
    public Mono<Permit> authorize(Invocation invocation, ExecutionContext context, Operation operation) {
        return authorizeOwner(ExecutionOwner.from(invocation.request().context()), context, operation);
    }

    /**
     * 本人同空间历史的宿主规则只依赖完整 owner、当前账户、空间和 RBAC。
     * 每条记录先核对归属，再以一次原有门控取得本批公共资格；返回前下一批必须重新读取。
     * 不扩大到其他主体／空间，不缓存允许决定，也不提供取消用的写许可。
     */
    @Override
    public Mono<List<ReadPermit>> authorizeReadBatch(List<Invocation> invocations, ExecutionContext context) {
        var batch = List.copyOf(invocations);
        if (batch.size() > 100) return Mono.error(new IllegalArgumentException("Tool read batch exceeds limit"));
        var owner = ExecutionOwner.from(context);
        for (var invocation : batch)
            if (invocation.request().kind() != CapabilityDescriptor.Kind.TOOL
                    || !owner.equals(ExecutionOwner.from(invocation.request().context())))
                return Mono.error(denied());
        if (batch.isEmpty()) return Mono.just(List.of());
        return authorizeOwner(owner, context, Operation.READ).map(permit -> batch.stream()
                .map(invocation -> new ReadPermit(invocation.request().context().executionId(), permit.authorization())).toList());
    }

    /**
     * Run 停止复用本人的当前读取／控制资格，不构造伪 Invocation。
     */
    @Override
    public Mono<Permit> authorizeOwner(ExecutionOwner owner, ExecutionContext context, Operation operation) {
        return Mono.defer(() -> {
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) return Mono.error(denied());
            return Mono.fromCallable(() -> transactions.required(() -> {
                check(owner, context, operation);
                return (Permit) new Permit() {
                    public ExecutionAuthorization authorization() {
                        return context.authorization();
                    }

                    public void requireInCurrentTransaction() {
                        check(owner, context, operation);
                    }
                };
            })).subscribeOn(scheduler).timeout(remaining);
        });
    }

    /**
     * 在当前可写事务中核验本人 TOOL 调用的读取／取消资格，并通过公共门控持有身份及空间权限锁。
     * authorize 首次核验与 Permit 提交前重验共用此方法，调用方负责开启事务。
     * <p>
     * 先检查本次请求期限、用户主体及调用归属，再确认当前账户仍有效且名称映射的稳定主体未变化。
     * 随后通过 WORKSPACE_READ 门控复核空间访问资格，在锁内重查账户与 RBAC：READ 要求 aiTool:list，
     * CANCEL 要求 aiTool:invoke；admin 仅免除该菜单操作检查，不绕过身份、归属和空间门控。
     * <p>
     * FIXED 模式还须匹配启用的主体／空间 grant，且覆盖本次上下文的全部 scopes；DATABASE 模式不要求 AI 仍启用，
     * 使关闭新调用后仍有资格的本人可以查询、停止已有操作。本方法不执行工具、不查询模型目录或预算。
     *
     * @param invocation 待读取或停止的原调用，用于核对租户、空间及主体归属
     * @param context    本次操作的可信身份、权限上限和请求期限，不使用原调用期限限制历史收尾
     * @param operation  READ 为读取历史，CANCEL 为请求停止，两者分别检查当前菜单操作权限
     */
    private void check(ExecutionOwner owner, ExecutionContext context, Operation operation) {
        transactions.requireWritable();
        identities.duringAuthorization(() -> {
            checkCurrent(owner, context, operation);
            return true;
        });
    }

    /**
     * 仅在本次授权批次内复用读取；返回后业务控制写入不能沿用这些事实。
     */
    private void checkCurrent(ExecutionOwner owner, ExecutionContext context, Operation operation) {
        var principal = context.authorization().principal();
        if (!clock.instant().isBefore(context.deadline()) || principal.kind() != ExecutionPrincipal.Kind.USER
                || !ExecutionOwner.from(context).equals(owner))
            throw denied();
        var resolved = identities.resolveAuthenticatedName(principal.subjectName()).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                .orElseThrow(PlatformToolInvocationAccess::denied);
        if (!resolved.subjectId().equals(principal.subjectId())) throw denied();
        var actor = new TrustedActor(resolved, clock.instant());
        var scope = new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
        guard.inCurrentTransaction(actor, List.of(DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "ai-tool-invocation")), () -> {
            identities.findCurrentBySubjectId(principal.subjectId()).filter(p -> p.status() == PrincipalStatusEnum.ACTIVE)
                    .orElseThrow(PlatformToolInvocationAccess::denied);
            var current = permissions.current(principal.subjectName());
            if (!current.contains("admin") && !current.contains(operation == Operation.READ ? "aiTool:list" : "aiTool:invoke"))
                throw denied();
            if (!databaseMode && properties.grants().stream().noneMatch(g -> g.enabled() && g.subjectId().equals(principal.subjectId())
                    && g.subjectName().equals(principal.subjectName()) && g.principalKind() == principal.kind()
                    && g.tenantId().equals(scope.tenantId()) && g.workspaceId().equals(scope.workspaceId())
                    && g.scopes().containsAll(context.authorization().scopes()))) throw denied();
            return true;
        });
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Tool invocation access denied");
    }
}
