package com.arte.core.application.workspace;

import com.arte.core.api.workspace.PersonalWorkspaceService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.CommandReceiptStore;
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
    private final java.util.List<com.arte.core.spi.workspace.WorkspaceProvisioningParticipant> provisioning;
    private final PlatformIdGenerator ids;
    private final Clock clock;
    private final WorkspaceMutationEffects effects;
    private final DefaultWorkspacePreferenceService preferenceViews;
    private final CommandReceiptStore receipts;

    public DefaultPersonalWorkspaceService(CurrentPrincipalResolver principals, DefaultCommandExecutor commands,
                                           WorkspaceAccessSupport access, WorkspaceGovernanceLocks locks, TenantStore tenants, WorkspaceStore workspaces,
                                           TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PreferenceStore preferences,
                                           PolicyStore policies, DefaultWorkspacePolicies defaults, PlatformIdGenerator ids, Clock clock,
                                           WorkspaceMutationEffects effects, DefaultWorkspacePreferenceService preferenceViews, CommandReceiptStore receipts, java.util.List<com.arte.core.spi.workspace.WorkspaceProvisioningParticipant> provisioning) {
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
        this.provisioning = java.util.List.copyOf(provisioning);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.effects = Objects.requireNonNull(effects);
        this.preferenceViews = Objects.requireNonNull(preferenceViews);
        this.receipts = Objects.requireNonNull(receipts);
    }

    @Override
    public CommandReceipt<WorkspaceViews.PersonalWorkspaceView> initialize(TrustedActor actor, CommandMeta meta) {
        String subjectId = principals.requireUser(actor).subjectId();
        return commands.execute(actor, meta, new CommandScope(null, null), "personal:initialize", "personal:view:v1",
                WorkspaceViews.PersonalWorkspaceView.class, List.of(), () -> {
                    locks.subject(subjectId);
                    tenants.findCurrentPersonal(subjectId).ifPresent(tenant -> {
                        var preference = preferences.findCurrent(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
                        if (!preference.defaultScope().tenantId().equals(tenant.id())) throw error();
                        // 结果视图还会检查启动／最近／收藏，先一次锁定全部候选父租户。
                        Set<Scope> references = references(WorkspaceViewMapper.preference(preference));
                        // 主体门控已串行化此主体的回执写入；重放原视图的引用也必须纳入完整父范围。
                        // 这里只规划锁，不判断回执有效性或授予权限，正式校验仍由执行器及重放回调完成。
                        receipts.findCurrent(new CommandIdentity(subjectId, new CommandScope(null, null), "personal:initialize", meta.operationKey()))
                                .filter(saved -> saved.resultSchema().equals("personal:view:v1"))
                                .map(saved -> saved.receipt().value())
                                .filter(WorkspaceViews.PersonalWorkspaceView.class::isInstance)
                                .map(WorkspaceViews.PersonalWorkspaceView.class::cast)
                                .ifPresent(saved -> references.addAll(references(saved.preference())));
                        locks.parents(subjectId, references.stream().map(Scope::tenantId).toList());
                        locks.scopes(subjectId, List.of(preference.defaultScope()));
                    });
                }, value -> {
                    WorkspaceViews.PersonalWorkspaceView current = existing(actor, subjectId);
                    if (!value.tenant().id().equals(current.tenant().id())
                            || !value.workspace().scope().equals(current.workspace().scope())) throw error();
                    var preference = value.preference();
                    if (preference.startupScope() != null) access.snapshotCurrent(actor, preference.startupScope());
                    if (preference.lastScope() != null) access.snapshotCurrent(actor, preference.lastScope());
                    for (Scope favorite : preference.favorites()) access.snapshotCurrent(actor, favorite);
                }, operationId -> {
                    principals.requireUserCurrent(actor);
                    Optional<Tenant> existing = tenants.findCurrentPersonal(subjectId);
                    if (existing.isPresent()) return new DefaultCommandExecutor.Result<>(existing(actor, subjectId),
                            preferences.findCurrent(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error).version());
                    if (preferences.findCurrent(subjectId).isPresent()) throw error();
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
                    provisioning.forEach(participant -> participant.provision(actor, scope, operationId));
                    effects.append(actor, new CommandScope(tenantId, scope.workspaceId()), "personal:initialized", operationId, 0,
                            Map.of("tenantEpoch", "0", "workspaceEpoch", "0", "personalTemplateVersion", Long.toString(tenantPolicy.personalTemplateVersion())));
                    return new DefaultCommandExecutor.Result<>(existing(actor, subjectId), 0);
                });
    }

    private static Set<Scope> references(WorkspaceViews.PreferenceView preference) {
        Set<Scope> references = new HashSet<>(preference.favorites());
        references.add(preference.defaultScope());
        if (preference.startupScope() != null) references.add(preference.startupScope());
        if (preference.lastScope() != null) references.add(preference.lastScope());
        return references;
    }

    private WorkspaceViews.PersonalWorkspaceView existing(TrustedActor actor, String subjectId) {
        Tenant tenant = tenants.findCurrentPersonal(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
        WorkspacePreference preference = preferences.findCurrent(subjectId).orElseThrow(DefaultPersonalWorkspaceService::error);
        if (tenant.type() != TenantTypeEnum.PERSONAL || !subjectId.equals(tenant.personalSubjectId())
                || !preference.subjectId().equals(subjectId) || !preference.defaultScope().tenantId().equals(tenant.id()))
            throw error();
        var snapshot = access.snapshotCurrent(actor, preference.defaultScope());
        if (snapshot.tenant().member().role() != TenantRoleEnum.OWNER || snapshot.member().role() != WorkspaceRoleEnum.OWNER)
            throw error();
        return new WorkspaceViews.PersonalWorkspaceView(WorkspaceViewMapper.tenant(snapshot.tenant()),
                WorkspaceViewMapper.workspace(snapshot), preferenceViews.getCurrent(actor));
    }

    private static void requireInsert(boolean inserted, CommandMeta meta) {
        if (!inserted) throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, meta.traceId());
    }

    private static PlatformException error() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "personal-initialize");
    }
}
