package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspaceLifecycleService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.*;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 归档及保留型关闭协调。普通业务继续使用既有授权门控；关闭完成仅停止访问，不物理删除资源或账本。
 * 领域检查只读且在排他空间父门控中执行。未知、失败、缺少领域及检查异常均阻塞软删除。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
public final class DefaultWorkspaceLifecycleService implements WorkspaceLifecycleService {
    public static final String CLOSE_SCHEMA = "workspace:closure:v1";
    private static final Set<String> COMMANDS = Set.of("workspace:archive", "workspace:restore", "workspace:close");
    private static final Set<String> REQUIRED = Set.of("resources", "ai");
    private final CurrentPrincipalResolver principals;
    private final WorkspaceGovernanceLocks locks;
    private final WorkspaceAccessSupport access;
    private final WorkspaceStore spaces;
    private final WorkspaceMembershipStore members;
    private final PreferenceStore preferences;
    private final ClosureWorkStore store;
    private final TenantInvitationStore invitations;
    private final PublicTransactionRunner tx;
    private final DefaultCommandExecutor commands;
    private final CommandReceiptStore receipts;
    private final WorkspaceMutationEffects effects;
    private final Map<String, WorkspaceClosureInspector> inspectors;
    private final Clock clock;
    private final String worker = UUID.randomUUID().toString();

    public DefaultWorkspaceLifecycleService(IdentityProvider identity, PublicTransactionRunner tx, TenantStore tenants,
                                            WorkspaceStore spaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                            PolicyStore policies, PreferenceStore preferences, WorkspaceAccessFactsStore facts, CommandReceiptStore receipts,
                                            PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, Clock clock, ClosureWorkStore store,
                                            List<WorkspaceClosureInspector> inspectors, TenantInvitationStore invitations) {
        this.principals = new CurrentPrincipalResolver(identity);
        this.tx = tx;
        this.spaces = spaces;
        this.members = members;
        this.preferences = preferences;
        this.receipts = receipts;
        this.clock = clock;
        this.store = store;
        this.invitations = invitations;
        this.locks = new WorkspaceGovernanceLocks(tx, tenants, spaces, tenantMembers, members, preferences);
        this.access = new WorkspaceAccessSupport(principals, tenants, spaces, tenantMembers, members, policies, preferences, clock, tx, facts);
        this.commands = new DefaultCommandExecutor(tx, principals, receipts, ids, clock);
        this.effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        Map<String, WorkspaceClosureInspector> index = new TreeMap<>();
        for (var inspector : inspectors) {
            if (index.putIfAbsent(inspector.domain(), inspector) != null)
                throw new IllegalArgumentException("Duplicate closure domain");
        }
        this.inspectors = Map.copyOf(index);
    }

    /**
     * 当前可用动作，不因具备治理权授予正文读取资格。
     */
    public record Capabilities(boolean archive, boolean restore, boolean close, boolean closureInstalled,
                               boolean defaultWorkspace) {
    }

    /**
     * 关闭记录仅返回空间名称、状态与最小原因码。
     */
    public record Record(String workspaceName, ClosureView closure) {
    }

    private record Context(Workspace space, WorkspaceViews.WorkspaceView view, boolean governor, boolean defaultSpace) {
    }

    private static CommandScope range(Scope scope) {
        return new CommandScope(scope.tenantId(), scope.workspaceId());
    }

    private void gate(TrustedActor actor, Scope scope) {
        locks.scopes(actor.principal().subjectId(), List.of(scope));
        principals.requireUserCurrent(actor);
    }

    private Context context(TrustedActor actor, Scope scope) {
        var tenant = access.tenantCurrent(actor, scope.tenantId());
        var space = spaces.lock(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
        var member = members.lock(scope, actor.principal().subjectId()).orElse(null);
        boolean active = member != null && member.state() == MembershipStateEnum.ACTIVE;
        boolean governor = tenant.member().role() == TenantRoleEnum.OWNER || active && member.role() == WorkspaceRoleEnum.OWNER;
        if (!governor && (!active || space.state() == WorkspaceStateEnum.CLOSING || space.state() == WorkspaceStateEnum.DELETED)) {
            throw error(PlatformErrorCodeEnum.NOT_FOUND);
        }
        boolean defaultSpace = tenant.tenant().personalSubjectId() != null && preferences.findCurrent(tenant.tenant().personalSubjectId())
                .map(p -> p.defaultScope().equals(scope)).orElseThrow(() -> error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE));
        var identity = new WorkspaceViews.WorkspaceIdentity(tenant.tenant().name(), tenant.tenant().type(),
                actor.principal().subjectId().equals(tenant.tenant().personalSubjectId()), actor.principal().subjectId().equals(space.createdBy()));
        return new Context(space, new WorkspaceViews.WorkspaceView(scope, space.name(), space.description(), space.state(),
                active ? member.role() : WorkspaceRoleEnum.OWNER, space.version(), identity), governor, defaultSpace);
    }

    private Context govern(TrustedActor actor, Scope scope) {
        Context context = context(actor, scope);
        if (!context.governor()) throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        return context;
    }

    public Capabilities capabilities(TrustedActor actor, Scope scope) {
        return tx.required(() -> {
            gate(actor, scope);
            var context = context(actor, scope);
            boolean permitted = context.governor() && !context.defaultSpace();
            boolean installed = store.ready();
            return new Capabilities(permitted && context.space().state() == WorkspaceStateEnum.ACTIVE,
                    permitted && context.space().state() == WorkspaceStateEnum.ARCHIVED,
                    permitted && installed && Set.of(WorkspaceStateEnum.ACTIVE, WorkspaceStateEnum.ARCHIVED).contains(context.space().state()),
                    installed, context.defaultSpace());
        });
    }

    @Override
    public CommandReceipt<WorkspaceViews.WorkspaceView> archive(TrustedActor actor, WorkspaceCommands.ChangeWorkspaceState command) {
        return change(actor, command, WorkspaceStateEnum.ACTIVE, WorkspaceStateEnum.ARCHIVED, "workspace:archive");
    }

    @Override
    public CommandReceipt<WorkspaceViews.WorkspaceView> unarchive(TrustedActor actor, WorkspaceCommands.ChangeWorkspaceState command) {
        return change(actor, command, WorkspaceStateEnum.ARCHIVED, WorkspaceStateEnum.ACTIVE, "workspace:restore");
    }

    private CommandReceipt<WorkspaceViews.WorkspaceView> change(TrustedActor actor, WorkspaceCommands.ChangeWorkspaceState command,
                                                                WorkspaceStateEnum from, WorkspaceStateEnum to, String type) {
        return commands.execute(actor, command.meta(), range(command.scope()), type, "workspace:view:v1", WorkspaceViews.WorkspaceView.class,
                List.of(Long.toString(command.expectedWorkspaceVersion()), command.reason()), () -> gate(actor, command.scope()),
                saved -> govern(actor, command.scope()), operation -> {
                    Context context = govern(actor, command.scope());
                    check(!context.defaultSpace(), PlatformErrorCodeEnum.POLICY_DENIED);
                    check(context.space().version() == command.expectedWorkspaceVersion() && context.space().state() == from, PlatformErrorCodeEnum.VERSION_CONFLICT);
                    Workspace changed = transition(context.space(), to);
                    check(spaces.replace(changed, context.space().version()), PlatformErrorCodeEnum.VERSION_CONFLICT);
                    effects.append(actor, range(command.scope()), type, operation, changed.authEpoch(), Map.of("state", to.name()));
                    var view = new WorkspaceViews.WorkspaceView(changed.scope(), changed.name(), changed.description(), to,
                            context.view().currentRole(), changed.version(), context.view().identity());
                    return new DefaultCommandExecutor.Result<>(view, changed.version());
                });
    }

    @Override
    public ClosurePreview preview(TrustedActor actor, Scope scope) {
        return tx.required(() -> {
            gate(actor, scope);
            Context context = govern(actor, scope);
            check(!context.defaultSpace(), PlatformErrorCodeEnum.POLICY_DENIED);
            check(Set.of(WorkspaceStateEnum.ACTIVE, WorkspaceStateEnum.ARCHIVED).contains(context.space().state()), PlatformErrorCodeEnum.SPACE_CLOSING);
            Map<String, Long> counts = new TreeMap<>();
            List<ClosureBlocker> blockers = new ArrayList<>();
            for (String domain : domains()) {
                var result = inspect(domain, scope);
                blockers.addAll(result.blockers());
                result.retainedResources().forEach((key, value) -> counts.put(domain + ":" + key, value));
            }
            return new ClosurePreview(scope, blockers, counts, context.space().version());
        });
    }

    @Override
    public CommandReceipt<ClosureView> close(TrustedActor actor, WorkspaceCommands.CloseWorkspace command) {
        return commands.execute(actor, command.meta(), range(command.scope()), "workspace:close", CLOSE_SCHEMA, ClosureView.class,
                List.of(Long.toString(command.expectedWorkspaceVersion()), command.reason()), () -> gate(actor, command.scope()),
                saved -> {
                    govern(actor, command.scope());
                    store.lockOperation(command.scope(), saved.operationId()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
                },
                operationId -> {
                    Context context = govern(actor, command.scope());
                    check(!context.defaultSpace(), PlatformErrorCodeEnum.POLICY_DENIED);
                    check(store.ready(), PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
                    check(context.space().version() == command.expectedWorkspaceVersion()
                            && Set.of(WorkspaceStateEnum.ACTIVE, WorkspaceStateEnum.ARCHIVED).contains(context.space().state()), PlatformErrorCodeEnum.VERSION_CONFLICT);
                    for (String domain : domains()) {
                        check(inspect(domain, command.scope()).blockers().stream().allMatch(ClosureBlocker::retryable), PlatformErrorCodeEnum.POLICY_DENIED);
                    }
                    Workspace changed = transition(context.space(), WorkspaceStateEnum.CLOSING);
                    check(spaces.replace(changed, context.space().version()), PlatformErrorCodeEnum.VERSION_CONFLICT);
                    invitations.invalidateWorkspace(command.scope());
                    Instant now = PlatformTimes.businessNow(clock);
                    ClosureOperation operation = new ClosureOperation(operationId, command.scope(), actor.principal().subjectId(), command.reason(),
                            ClosureWorkStateEnum.PENDING, 0, now, now);
                    check(store.insertOperation(operation), PlatformErrorCodeEnum.VERSION_CONFLICT);
                    for (String domain : domains()) ensureWork(operation, domain);
                    effects.append(actor, range(command.scope()), "workspace:close", operationId, changed.authEpoch(), Map.of("state", "CLOSING"));
                    return new DefaultCommandExecutor.Result<>(view(operation), 0);
                });
    }

    @Override
    public ClosureView progress(TrustedActor actor, Scope scope, String operationId) {
        return tx.required(() -> {
            gate(actor, scope);
            govern(actor, scope);
            return view(store.lockOperation(scope, operationId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)));
        });
    }

    public PageResult<Record> list(TrustedActor actor, PageQuery query) {
        String subject = principals.requireUser(actor).subjectId();
        if (!store.ready()) return new PageResult<>(query.current(), query.size(), 0, List.of());
        return tx.readOnly(() -> store.listForGovernor(subject, query).map(operation ->
                new Record(spaces.find(operation.scope()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND)).name(), view(operation))));
    }

    public static boolean supports(String type) {
        return COMMANDS.contains(type);
    }

    public CommandReceipt<?> receipt(TrustedActor actor, StoredCommandReceipt located) {
        return tx.required(() -> {
            var scope = located.identity().scope();
            var target = new Scope(scope.tenantId(), scope.workspaceId());
            gate(actor, target);
            govern(actor, target);
            var stored = receipts.findCurrent(located.identity()).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            check(stored.equals(located) && stored.identity().actorSubjectId().equals(actor.principal().subjectId())
                    && stored.receipt().status() == CommandStatusEnum.APPLIED, PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            if (stored.identity().commandType().equals("workspace:close")) {
                check(CLOSE_SCHEMA.equals(stored.resultSchema()) && stored.receipt().value() instanceof ClosureView v
                        && v.scope().equals(target) && v.operationId().equals(stored.receipt().operationId()), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            } else
                check("workspace:view:v1".equals(stored.resultSchema()) && stored.receipt().value() instanceof WorkspaceViews.WorkspaceView v
                        && v.scope().equals(target) && v.version() == stored.receipt().resultVersion(), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            var receipt = stored.receipt();
            return new CommandReceipt<>(receipt.operationId(), receipt.status(), CommandDeliveryEnum.REPLAYED, receipt.value(),
                    receipt.resultVersion(), receipt.acceptedAt(), receipt.completedAt());
        });
    }

    /**
     * 内部可靠扫描；调用者不能提供新执行、金额或正文清理指令。
     */
    public void advanceUnfinished() {
        if (!store.ready()) return;
        for (var operation : store.listUnfinished(new PageQuery(1, 20)).records()) {
            try {
                advance(operation.scope(), operation.id());
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workspace closure remains pending, operation={}", operation.id(), failure);
            }
        }
    }

    /**
     * 每个操作在同一排他父门控事务中推进，领域检查异常保留为阻塞项。
     */
    public void advance(Scope scope, String operationId) {
        tx.required(() -> {
            locks.closureScope(scope, operationId);
            Workspace workspace = locks.lockWorkspace(scope).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            ClosureOperation operation = store.lockOperation(scope, operationId).orElseThrow(() -> error(PlatformErrorCodeEnum.NOT_FOUND));
            if (operation.state() == ClosureWorkStateEnum.DONE) return null;
            check(workspace.state() == WorkspaceStateEnum.CLOSING, PlatformErrorCodeEnum.VERSION_CONFLICT);
            for (String domain : domains()) ensureWork(operation, domain);
            boolean done = true;
            // 首版领域数有界；超限保留失败，不允许忽略未检查的工作项。
            var page = store.listWork(scope, operationId, new PageQuery(1, 100));
            if (page.total() > 100) throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            for (var old : page.records()) {
                var claimed = store.claim(scope, operationId, old.domain(), old.workKey(), old.version(), worker, clock.instant(), Duration.ofSeconds(30));
                if (claimed.isEmpty()) {
                    done = false;
                    continue;
                }
                var current = claimed.get();
                var result = inspect(old.domain(), scope);
                boolean ready = result.blockers().isEmpty();
                done &= ready;
                var updated = new ClosureWorkItem(operationId, scope, current.domain(), current.workKey(), current.targetRefs(),
                        ready ? ClosureWorkStateEnum.DONE : ClosureWorkStateEnum.WAITING_EVIDENCE,
                        current.version() + 1, null, null, null);
                check(store.replaceProgress(updated, current.version(), current.fencing(), result.blockers()), PlatformErrorCodeEnum.VERSION_CONFLICT);
            }
            if (page.records().isEmpty()) done = false;
            Instant now = PlatformTimes.notBefore(clock, operation.updatedAt());
            var updated = new ClosureOperation(operation.id(), scope, operation.requestedBy(), operation.reason(),
                    done ? ClosureWorkStateEnum.DONE : ClosureWorkStateEnum.WAITING_EVIDENCE, operation.version() + 1, operation.acceptedAt(), now);
            check(store.replaceOperation(updated, operation.version()), PlatformErrorCodeEnum.VERSION_CONFLICT);
            if (done) {
                Workspace deleted = transition(workspace, WorkspaceStateEnum.DELETED);
                check(spaces.replace(deleted, workspace.version()), PlatformErrorCodeEnum.VERSION_CONFLICT);
                // 审计记录实际内部协调者，绝不伪装成原请求者，也不改变任何历史执行 owner。
                var system = new TrustedActor(new Principal("system:workspace-closure", PrincipalKindEnum.SERVICE, PrincipalStatusEnum.ACTIVE), now);
                effects.append(system, range(scope), "workspace:deleted", operationId, deleted.authEpoch(), Map.of("retention", "PRESERVE"));
            }
            return null;
        });
    }

    private Set<String> domains() {
        Set<String> domains = new TreeSet<>(REQUIRED);
        domains.addAll(inspectors.keySet());
        return domains;
    }

    private WorkspaceClosureInspector.Inspection inspect(String domain, Scope scope) {
        var inspector = inspectors.get(domain);
        if (inspector == null)
            return new WorkspaceClosureInspector.Inspection(List.of(new ClosureBlocker(domain, "retention", "PARTICIPANT_UNAVAILABLE", true)), Map.of());
        try {
            return inspector.inspect(scope);
        } catch (RuntimeException unavailable) {
            return new WorkspaceClosureInspector.Inspection(List.of(new ClosureBlocker(domain, "retention", "AUTHORITY_UNAVAILABLE", true)), Map.of());
        }
    }

    private void ensureWork(ClosureOperation operation, String domain) {
        store.insertWorkIfAbsent(new ClosureWorkItem(operation.id(), operation.scope(), domain, "retention", Set.of(operation.scope().workspaceId()),
                ClosureWorkStateEnum.PENDING, 0, null, null, null));
    }

    private ClosureView view(ClosureOperation operation) {
        return new ClosureView(operation.id(), operation.scope(), operation.state(), store.blockers(operation.scope(), operation.id()), operation.version());
    }

    private Workspace transition(Workspace old, WorkspaceStateEnum state) {
        return new Workspace(old.scope(), old.name(), old.description(), state, old.createdBy(), Math.incrementExact(old.version()),
                Math.incrementExact(old.authEpoch()), old.createdAt(), PlatformTimes.notBefore(clock, old.updatedAt()));
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "workspace-lifecycle");
    }

    private static void check(boolean success, PlatformErrorCodeEnum code) {
        if (!success) throw error(code);
    }
}
