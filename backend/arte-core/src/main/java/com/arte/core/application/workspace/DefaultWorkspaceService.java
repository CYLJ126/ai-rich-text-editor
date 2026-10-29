package com.arte.core.application.workspace;

import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
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
import java.util.function.LongSupplier;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;
import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_UPDATE;

/**
 * 基础空间管理；创建只检查租户资格，列表批量读取事实并逐项授权后计算可见 total。
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
    private final List<WorkspaceProvisioningParticipant> provisioning;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final WorkspaceMutationEffects effects;
    private final WorkspaceBrowseBudget browseBudget;
    private final LongSupplier nanoTime;

    public DefaultWorkspaceService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                                   DefaultAuthorizationService authorization, WorkspaceGovernanceLocks locks, DefaultCommandExecutor commands,
                                   TenantStore tenants, WorkspaceStore workspaces, WorkspaceMembershipStore members, PolicyStore policies,
                                   PreferenceStore preferences, DefaultWorkspacePolicies defaults, PlatformIdGenerator ids, Clock clock,
                                   WorkspaceMutationEffects effects, List<WorkspaceProvisioningParticipant> provisioning,
                                   WorkspaceBrowseBudget browseBudget, LongSupplier nanoTime) {
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
        this.provisioning = java.util.List.copyOf(provisioning);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.effects = Objects.requireNonNull(effects);
        this.browseBudget = Objects.requireNonNull(browseBudget);
        this.nanoTime = Objects.requireNonNull(nanoTime);
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
        WorkspaceCandidateKey lastKey = null;
        boolean exhausted;
        do {
            var candidates = workspaces.scanMembershipCandidates(subjectId, lastKey, 100);
            for (WorkspaceCandidateKey key : candidates.records()) {
                if (lastKey != null && key.compareTo(lastKey) <= 0) {
                    throw unavailable();
                }
                lastKey = key;
            }
            // 每批只装载一次事实；计数和摘要共用授权时验证过的快照，不再逐项重读。
            var checks = authorization.checkWorkspaceReads(actor, candidates.records().stream().map(WorkspaceCandidateKey::scope).toList());
            // 主体失效才需本批重检身份；租户不可用只过滤该租户，不触发身份重检。
            if (checks.stream().anyMatch(check -> check.decision().reason() == AccessReasonEnum.SUBJECT_INACTIVE)) {
                principals.requireActive(actor);
            }
            for (var check : checks) {
                var decision = check.decision();
                if (!decision.allowed()) {
                    if (decision.reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) throw unavailable();
                    continue;
                }
                var workspaceSnapshot = check.snapshot();
                if (total >= offset && records.size() < query.size()) {
                    Workspace workspace = workspaceSnapshot.workspace();
                    records.add(new WorkspaceViews.WorkspaceSummary(workspace.scope(), workspace.name(), workspace.state(),
                            workspaceSnapshot.member().role(), preference.map(p -> p.defaultScope().equals(workspace.scope())).orElse(false), workspace.version()));
                }
                total = Math.incrementExact(total);
            }
            exhausted = candidates.exhausted();
        } while (!exhausted);
        principals.requireUser(actor);
        return new PageResult<>(query.current(), query.size(), total, records);
    }

    @Override
    public long countAccessible(TrustedActor actor) {
        String subject = principals.requireUser(actor).subjectId();
        WorkspaceCandidateKey after = null;
        long total = 0;
        boolean exhausted;
        do {
            var batch = workspaces.scanMembershipCandidates(subject, after, 100);
            for (var key : batch.records()) {
                if (after != null && key.compareTo(after) <= 0) throw unavailable();
                after = key;
            }
            for (var check : authorization.checkWorkspaceReads(actor, batch.records().stream().map(WorkspaceCandidateKey::scope).toList())) {
                if (check.decision().reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) throw unavailable();
                if (check.decision().reason() == AccessReasonEnum.SUBJECT_INACTIVE) principals.requireActive(actor);
                if (check.decision().allowed()) total = Math.incrementExact(total);
            }
            exhausted = batch.exhausted();
        } while (!exhausted);
        principals.requireUser(actor);
        return total;
    }

    @Override
    public WorkspaceBrowseResult browseAccessible(TrustedActor actor, WorkspaceBrowseQuery query, WorkspaceCandidateKey after) {
        long started = nanoTime.getAsLong();
        String subject = principals.requireUser(actor).subjectId();
        var preference = preferences.find(subject);
        var records = new ArrayList<WorkspaceViews.WorkspaceSummary>();
        WorkspaceCandidateKey boundary = after;
        int processed = 0;
        int batches = 0;
        boolean exhausted = false;
        while (records.size() < query.size() && processed < browseBudget.maxCandidates() && batches < browseBudget.maxBatches()) {
            // Always process one batch to produce a continuation; SQL itself uses the adapter's statement timeout.
            if (batches > 0 && nanoTime.getAsLong() - started >= browseBudget.maxWorkTime().toNanos()) break;
            // Keep the dense first page light; widen subsequent batches to amortize authorization round trips.
            int desired = batches == 0 ? Math.min(browseBudget.batchSize(), query.size() * 2) : browseBudget.batchSize();
            int limit = Math.min(desired, browseBudget.maxCandidates() - processed);
            var batch = workspaces.scanMembershipCandidates(subject, boundary, limit);
            if (batch.records().size() > limit) throw unavailable();
            WorkspaceCandidateKey previous = boundary;
            for (var key : batch.records()) {
                if (previous != null && key.compareTo(previous) <= 0) throw unavailable();
                previous = key;
            }
            var checks = authorization.checkWorkspaceReads(actor, batch.records().stream().map(WorkspaceCandidateKey::scope).toList());
            batches++;
            int consumed = 0;
            for (int index = 0; index < batch.records().size(); index++) {
                var check = checks.get(index);
                boundary = batch.records().get(index);
                consumed++;
                processed++;
                if (check.decision().reason() == AccessReasonEnum.AUTHORITY_UNAVAILABLE) throw unavailable();
                if (check.decision().reason() == AccessReasonEnum.SUBJECT_INACTIVE) principals.requireActive(actor);
                if (check.decision().allowed()) {
                    var snapshot = check.snapshot();
                    Workspace workspace = snapshot.workspace();
                    if (!workspace.scope().equals(boundary.scope()) || !workspace.createdAt().equals(boundary.createdAt()))
                        throw unavailable();
                    if (workspace.name().toLowerCase(Locale.ROOT).contains(query.search())) {
                        records.add(new WorkspaceViews.WorkspaceSummary(workspace.scope(), workspace.name(), workspace.state(),
                                snapshot.member().role(), preference.map(p -> p.defaultScope().equals(workspace.scope())).orElse(false), workspace.version()));
                    }
                }
                if (records.size() == query.size()) break;
            }
            exhausted = batch.exhausted() && consumed == batch.records().size();
            if (exhausted) break;
        }
        principals.requireUser(actor);
        return new WorkspaceBrowseResult(records, exhausted ? null : boundary, exhausted);
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
                    var now = PlatformTimes.businessNow(clock);
                    Scope newScope = new Scope(command.tenantId(), ids.nextId());
                    Workspace workspace = new Workspace(newScope, command.name(), command.description(), WorkspaceStateEnum.ACTIVE,
                            subjectId, 0, 0, now, now);
                    check(workspaces.insert(workspace), command.meta());
                    check(members.insert(new WorkspaceMembership(ids.nextId(), newScope, subjectId, WorkspaceRoleEnum.OWNER,
                            MembershipStateEnum.ACTIVE, 0, 0)), command.meta());
                    check(policies.insertWorkspacePolicy(defaults.workspace(newScope, tenant.policy())), command.meta());
                    Tenant old = tenant.tenant();
                    Tenant updated = new Tenant(old.id(), old.name(), old.type(), old.personalSubjectId(), old.state(), old.createdBy(),
                            Math.incrementExact(old.version()), Math.incrementExact(old.authEpoch()), old.createdAt(),
                            PlatformTimes.notBefore(now, old.updatedAt()));
                    check(tenants.replace(updated, old.version()), command.meta());
                    provisioning.forEach(participant -> participant.provision(actor, newScope, operationId));
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
                            Math.incrementExact(old.version()), old.authEpoch(), old.createdAt(), PlatformTimes.notBefore(clock, old.updatedAt()));
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
