package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE;

/**
 * 基础空间管理；创建只检查租户资格，列表逐项授权后计算可见 total。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspaceService implements WorkspaceService {

    private final CurrentPrincipalResolver principals;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final WorkspaceMembershipStore members;
    private final PolicyStore policies;
    private final PreferenceStore preferences;
    private final DefaultWorkspacePolicies defaults;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final WorkspaceMutationEffects effects;

    public DefaultWorkspaceService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                                   DefaultAuthorizationService authorization, WorkspaceGovernanceLocks locks, DefaultCommandExecutor commands,
                                   TenantStore tenants, WorkspaceStore workspaces, WorkspaceMembershipStore members, PolicyStore policies,
                                   PreferenceStore preferences, DefaultWorkspacePolicies defaults, PlatformIdGenerator ids, Clock clock,
                                   WorkspaceMutationEffects effects) {
        this.principals = Objects.requireNonNull(principals);
        this.access = Objects.requireNonNull(access);
        this.authorization = Objects.requireNonNull(authorization);
        this.locks = Objects.requireNonNull(locks);
        this.commands = Objects.requireNonNull(commands);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.members = Objects.requireNonNull(members);
        this.policies = Objects.requireNonNull(policies);
        this.preferences = Objects.requireNonNull(preferences);
        this.defaults = Objects.requireNonNull(defaults);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.effects = Objects.requireNonNull(effects);
    }

    @Override
    public WorkspaceViews.WorkspaceView get(TrustedActor actor, Scope scope) {
        authorization.require(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "workspace-read"));
        return WorkspaceViewMapper.workspace(access.snapshot(actor, scope));
    }

    private WorkspaceViews.WorkspaceView getCurrent(TrustedActor actor, Scope scope) {
        authorization.requireInCurrentTransaction(actor,
                DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_READ, "workspace-read"));
        return WorkspaceViewMapper.workspace(access.snapshotCurrent(actor, scope));
    }

    @Override
    public PageResult<WorkspaceViews.WorkspaceSummary> listAccessible(TrustedActor actor, PageQuery query) {
        String subjectId = principals.requireUser(actor).subjectId();
        var preference = preferences.find(subjectId);
        long offset = Math.multiplyExact(query.current() - 1, query.size());
        long total = 0;
        List<WorkspaceViews.WorkspaceSummary> records = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<Scope> seen = new HashSet<>();
        WorkspaceCandidateKey lastKey = null;
        String cursor = null;
        do {
            var candidates = workspaces.scanMembershipCandidates(subjectId, cursor, 100);
            for (Workspace candidate : candidates.records()) {
                // 校验跨批进度，适配器不能退回可变排序或重复扫描已过边界
                WorkspaceCandidateKey key = WorkspaceCandidateKey.from(candidate);
                // 检查当前候选是否排在上一个候选之后，扫描有没有向前推进
                if (lastKey != null && key.compareTo(lastKey) <= 0) throw unavailable();
                // 上一个处理过的 candidate 的排序键，每次 for 都更新，进入下一批 while 时继续保留
                lastKey = key;
                if (!seen.add(candidate.scope())) throw unavailable();
                var decision = authorization.check(actor, DefaultAuthorizationService.workspaceRequest(candidate.scope(), WORKSPACE_READ, "workspace-list"));
                if (!decision.allowed()) {
                    if (decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) throw unavailable();
                    if (decision.reason() == AccessReasonEnum.SUBJECT_INACTIVE) principals.requireActive(actor);
                    continue;
                }
                WorkspaceAccessSupport.WorkspaceSnapshot workspaceSnapshot;
                try {
                    workspaceSnapshot = access.snapshot(actor, candidate.scope());
                } catch (PlatformException e) {
                    if (e.getCode() == PlatformErrorCodeEnum.NOT_FOUND || e.getCode() == PlatformErrorCodeEnum.SPACE_CLOSING)
                        continue;
                    throw e;
                }
                if (total >= offset && records.size() < query.size()) {
                    Workspace workspace = workspaceSnapshot.workspace();
                    records.add(new WorkspaceViews.WorkspaceSummary(workspace.scope(), workspace.name(), workspace.state(),
                            workspaceSnapshot.member().role(), preference.map(p -> p.defaultScope().equals(workspace.scope())).orElse(false), workspace.version()));
                }
                total = Math.incrementExact(total);
            }
            cursor = candidates.nextCursor();
            if (cursor != null && (candidates.records().isEmpty() || !cursors.add(cursor))) throw unavailable();
        } while (cursor != null);
        principals.requireUser(actor);
        return new PageResult<>(query.current(), query.size(), total, records);
    }

    @Override
    public CommandReceipt<WorkspaceViews.WorkspaceView> create(TrustedActor actor, WorkspaceCommands.CreateWorkspace command) {
        String subjectId = principals.requireUser(actor).subjectId();
        CommandScope scope = new CommandScope(command.tenantId(), null);
        return commands.execute(actor, command.meta(), scope, "workspace:create", "workspace:view:v1",
                WorkspaceViews.WorkspaceView.class, List.of(command.name(), command.description()),
                () -> locks.tenant(subjectId, command.tenantId()), value -> {
                    if (!value.scope().tenantId().equals(command.tenantId())) throw unavailable();
                    getCurrent(actor, value.scope());
                }, operationId -> {
                    var tenant = authorization.requireWorkspaceCreationCurrent(actor, command.tenantId());
                    var now = clock.instant();
                    Scope newScope = new Scope(command.tenantId(), ids.nextId());
                    Workspace workspace = new Workspace(newScope, command.name(), command.description(), WorkspaceStateEnum.ACTIVE,
                            subjectId, 0, 0, now, now);
                    check(workspaces.insert(workspace), command.meta());
                    check(members.insert(new WorkspaceMembership(ids.nextId(), newScope, subjectId, WorkspaceRoleEnum.OWNER,
                            MembershipStateEnum.ACTIVE, 0, 0)), command.meta());
                    check(policies.insertWorkspacePolicy(defaults.workspace(newScope, tenant.policy())), command.meta());
                    Tenant old = tenant.tenant();
                    Tenant updated = new Tenant(old.id(), old.name(), old.type(), old.personalSubjectId(), old.state(), old.createdBy(),
                            Math.incrementExact(old.version()), Math.incrementExact(old.authEpoch()), old.createdAt(), now);
                    check(tenants.replace(updated, old.version()), command.meta());
                    effects.append(actor, new CommandScope(newScope.tenantId(), newScope.workspaceId()), "workspace:created", operationId, 0,
                            Map.of("tenantEpoch", Long.toString(updated.authEpoch()), "workspaceEpoch", "0"));
                    return new DefaultCommandExecutor.Result<>(getCurrent(actor, newScope), 0);
                });
    }

    @Override
    public CommandReceipt<WorkspaceViews.WorkspaceView> rename(TrustedActor actor, WorkspaceCommands.RenameWorkspace command) {
        Scope scope = command.scope();
        return commands.execute(actor, command.meta(), new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:rename",
                "workspace:view:v1", WorkspaceViews.WorkspaceView.class,
                List.of(Long.toString(command.expectedVersion()), command.name(), command.description()),
                () -> locks.scopes(actor.principal().subjectId(), List.of(scope)), value -> {
                    if (!value.scope().equals(scope)) throw unavailable();
                    getCurrent(actor, value.scope());
                }, operationId -> {
                    authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_UPDATE, "workspace-rename"));
                    Workspace old = access.snapshotCurrent(actor, scope).workspace();
                    if (old.version() != command.expectedVersion())
                        throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, command.meta().traceId());
                    Workspace updated = new Workspace(scope, command.name(), command.description(), old.state(), old.createdBy(),
                            Math.incrementExact(old.version()), old.authEpoch(), old.createdAt(), clock.instant());
                    check(workspaces.replace(updated, old.version()), command.meta());
                    effects.append(actor, new CommandScope(scope.tenantId(), scope.workspaceId()), "workspace:renamed", operationId,
                            updated.version(), Map.of("workspaceEpoch", Long.toString(updated.authEpoch())));
                    return new DefaultCommandExecutor.Result<>(getCurrent(actor, scope), updated.version());
                });
    }

    private static void check(boolean success, CommandMeta meta) {
        if (!success) throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, meta.traceId());
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "workspace-list");
    }
}
