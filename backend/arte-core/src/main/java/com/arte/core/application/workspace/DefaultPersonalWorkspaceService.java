package com.arte.core.application.workspace;

import com.arte.core.api.workspace.PersonalWorkspaceService;
import com.arte.core.api.workspace.WorkspacePreferenceService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.*;


/**
 * 个人租户、系统默认空间、负责人、政策及偏好的幂等原子初始化。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultPersonalWorkspaceService implements PersonalWorkspaceService {

    private final CurrentPrincipalResolver principals;
    private final DefaultCommandExecutor commands;
    private final WorkspaceAccessSupport access;
    private final WorkspaceGovernanceLocks locks;
    private final TenantStore tenants;
    private final WorkspaceStore workspaces;
    private final TenantMembershipStore tenantMembers;
    private final WorkspaceMembershipStore workspaceMembers;
    private final PreferenceStore preferences;
    private final PolicyStore policies;
    private final DefaultWorkspacePolicies defaults;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final WorkspaceMutationEffects effects;
    private final WorkspacePreferenceService preferenceViews;

    public DefaultPersonalWorkspaceService(CurrentPrincipalResolver principals, DefaultCommandExecutor commands,
                                           WorkspaceAccessSupport access, WorkspaceGovernanceLocks locks, TenantStore tenants, WorkspaceStore workspaces,
                                           TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PreferenceStore preferences,
                                           PolicyStore policies, DefaultWorkspacePolicies defaults, PlatformIdGenerator ids, Clock clock,
                                           WorkspaceMutationEffects effects, WorkspacePreferenceService preferenceViews) {
        this.principals = Objects.requireNonNull(principals);
        this.commands = Objects.requireNonNull(commands);
        this.access = Objects.requireNonNull(access);
        this.locks = Objects.requireNonNull(locks);
        this.tenants = Objects.requireNonNull(tenants);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
        this.preferences = Objects.requireNonNull(preferences);
        this.policies = Objects.requireNonNull(policies);
        this.defaults = Objects.requireNonNull(defaults);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.effects = Objects.requireNonNull(effects);
        this.preferenceViews = Objects.requireNonNull(preferenceViews);
    }

    @Override
    public CommandReceipt<WorkspaceViews.PersonalWorkspaceView> initialize(TrustedActor actor, CommandMeta meta) {
        String subjectId = principals.requireUser(actor).subjectId();
        return commands.execute(actor, meta, new CommandScope(null, null), "personal:initialize", "personal:view:v1",
                WorkspaceViews.PersonalWorkspaceView.class, List.of(), () -> {
                    preferences.lockSubject(subjectId);
                    tenants.findPersonal(subjectId).ifPresent(tenant -> {
                        var preference = preferences.find(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
                        if (!preference.defaultScope().tenantId().equals(tenant.id())) throw error();
                        locks.scopes(subjectId, List.of(preference.defaultScope()));
                    });
                }, value -> {
                    WorkspaceViews.PersonalWorkspaceView current = existing(actor, subjectId);
                    if (!value.tenant().id().equals(current.tenant().id())
                            || !value.workspace().scope().equals(current.workspace().scope())) throw error();
                    var preference = value.preference();
                    if (preference.startupScope() != null) access.snapshot(actor, preference.startupScope());
                    if (preference.lastScope() != null) access.snapshot(actor, preference.lastScope());
                    for (Scope favorite : preference.favorites()) access.snapshot(actor, favorite);
                }, operationId -> {
                    principals.requireUser(actor);
                    Optional<Tenant> existing = tenants.findPersonal(subjectId);
                    if (existing.isPresent()) return new DefaultCommandExecutor.Result<>(existing(actor, subjectId),
                            preferences.find(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error).version());
                    if (preferences.find(subjectId).isPresent()) throw error();
                    var now = clock.instant();
                    String tenantId = ids.nextId();
                    Scope scope = new Scope(tenantId, ids.nextId());
                    Tenant tenant = new Tenant(tenantId, "个人", TenantTypeEnum.PERSONAL, subjectId,
                            TenantStateEnum.ACTIVE, subjectId, 0, 0, now, now);
                    Workspace workspace = new Workspace(scope, "个人空间", "", WorkspaceStateEnum.ACTIVE, subjectId, 0, 0, now, now);
                    TenantPolicy tenantPolicy = defaults.personalTenant(tenantId);
                    requireInsert(tenants.insert(tenant), meta);
                    requireInsert(workspaces.insert(workspace), meta);
                    requireInsert(tenantMembers.insert(new TenantMembership(ids.nextId(), tenantId, subjectId,
                            TenantRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0)), meta);
                    requireInsert(workspaceMembers.insert(new WorkspaceMembership(ids.nextId(), scope, subjectId,
                            WorkspaceRoleEnum.OWNER, MembershipStateEnum.ACTIVE, 0, 0)), meta);
                    requireInsert(policies.insertTenantPolicy(tenantPolicy), meta);
                    requireInsert(policies.insertWorkspacePolicy(defaults.workspace(scope, tenantPolicy)), meta);
                    requireInsert(preferences.insert(new WorkspacePreference(subjectId, scope, null, null, Set.of(), 0)), meta);
                    effects.append(actor, new CommandScope(tenantId, scope.workspaceId()), "personal:initialized", operationId, 0,
                            Map.of("tenantEpoch", "0", "workspaceEpoch", "0"));
                    return new DefaultCommandExecutor.Result<>(existing(actor, subjectId), 0);
                });
    }

    private WorkspaceViews.PersonalWorkspaceView existing(TrustedActor actor, String subjectId) {
        Tenant tenant = tenants.findPersonal(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
        WorkspacePreference preference = preferences.find(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
        if (tenant.type() != TenantTypeEnum.PERSONAL || !subjectId.equals(tenant.personalSubjectId())
                || !preference.subjectId().equals(subjectId) || !preference.defaultScope().tenantId().equals(tenant.id()))
            throw error();
        var snapshot = access.snapshot(actor, preference.defaultScope());
        if (snapshot.tenant().member().role() != TenantRoleEnum.OWNER || snapshot.member().role() != WorkspaceRoleEnum.OWNER)
            throw error();
        return new WorkspaceViews.PersonalWorkspaceView(WorkspaceViewMapper.tenant(snapshot.tenant()),
                WorkspaceViewMapper.workspace(snapshot), preferenceViews.get(actor));
    }

    private static void requireInsert(boolean inserted, CommandMeta meta) {
        if (!inserted) throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, meta.traceId());
    }

    private static PlatformException error() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "personal-initialize");
    }
}
