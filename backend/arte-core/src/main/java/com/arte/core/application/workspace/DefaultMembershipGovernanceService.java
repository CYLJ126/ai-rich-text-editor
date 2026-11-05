package com.arte.core.application.workspace;

import com.arte.core.api.workspace.MembershipService;
import com.arte.core.api.workspace.TenantService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.*;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.pojo.workspace.WorkspaceViews.*;

/**
 * 两级成员治理复用主体、父范围门控及原事务，邀请接受是新增成员资格的唯一入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 21:08 ✾
 */
public final class DefaultMembershipGovernanceService implements TenantService, MembershipService {

    public static final String TENANT_INVITATION = "tenant:invitation:v1";
    public static final String SPACE_INVITATION = "workspace:invitation:v1";
    public static final String TENANT_JOIN = "tenant:join:v1";
    public static final String SPACE_JOIN = "workspace:join:v1";
    public static final String TENANT_MEMBER = "tenant:member:v1";
    public static final String SPACE_MEMBER = "workspace:member:v1";
    public static final String VOID = "membership:void:v1";

    private final CurrentPrincipalResolver principals;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService auth;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final PublicTransactionRunner tx;
    private final IdentityProvider identity;
    private final TenantStore tenants;
    private final WorkspaceStore spaces;
    private final TenantMembershipStore tenantMembers;
    private final WorkspaceMembershipStore spaceMembers;
    private final TenantInvitationStore tenantInvitations;
    private final InvitationStore spaceInvitations;
    private final DefaultTenantGovernanceService tenantGovernance;
    private final PlatformIdGenerator ids;
    private final Clock clock;

    DefaultMembershipGovernanceService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                                       DefaultAuthorizationService auth, WorkspaceGovernanceLocks locks, DefaultCommandExecutor commands,
                                       WorkspaceMutationEffects effects, PublicTransactionRunner tx, IdentityProvider identity,
                                       TenantStore tenants, WorkspaceStore spaces, TenantMembershipStore tenantMembers,
                                       WorkspaceMembershipStore spaceMembers, TenantInvitationStore tenantInvitations, InvitationStore spaceInvitations,
                                       DefaultTenantGovernanceService tenantGovernance, PlatformIdGenerator ids, Clock clock) {
        this.principals = principals;
        this.access = access;
        this.auth = auth;
        this.locks = locks;
        this.commands = commands;
        this.effects = effects;
        this.tx = tx;
        this.identity = identity;
        this.tenants = tenants;
        this.spaces = spaces;
        this.tenantMembers = tenantMembers;
        this.spaceMembers = spaceMembers;
        this.tenantInvitations = tenantInvitations;
        this.spaceInvitations = spaceInvitations;
        this.tenantGovernance = tenantGovernance;
        this.ids = ids;
        this.clock = clock;
    }

    private static String subject(TrustedActor actor) {
        return actor.principal().subjectId();
    }

    private static CommandScope range(Scope scope) {
        return new CommandScope(scope.tenantId(), scope.workspaceId());
    }

    private static CommandScope tenantRange(String tenantId) {
        return new CommandScope(tenantId, null);
    }

    private void gate(TrustedActor actor, CommandScope scope, String... subjects) {
        TreeSet<String> ordered = new TreeSet<>(List.of(subjects));
        ordered.add(subject(actor));
        ordered.forEach(locks::subject);
        if (scope.workspaceId() == null) {
            locks.tenant(subject(actor), scope.tenantId());
        } else {
            locks.scopes(subject(actor), List.of(new Scope(scope.tenantId(), scope.workspaceId())));
        }
        principals.requireUserCurrent(actor);
    }

    private <T> T read(TrustedActor actor, CommandScope scope, Supplier<T> work) {
        principals.requireUser(actor);
        return tx.required(() -> {
            gate(actor, scope);
            return work.get();
        });
    }

    private WorkspaceAccessSupport.TenantSnapshot tenant(TrustedActor actor, String id, Action action) {
        auth.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(id, action, "membership"));
        return access.tenantCurrent(actor, id);
    }

    private WorkspaceAccessSupport.TenantSnapshot organization(TrustedActor actor, String id, Action action) {
        WorkspaceAccessSupport.TenantSnapshot snapshot = tenant(actor, id, action);
        allowed(snapshot.tenant().type() == TenantTypeEnum.ORGANIZATION);
        return snapshot;
    }

    private WorkspaceAccessSupport.WorkspaceSnapshot workspace(TrustedActor actor, Scope scope, Action action) {
        auth.requireInCurrentTransaction(actor, DefaultAuthorizationService.workspaceRequest(scope, action, "membership"));
        return access.snapshotCurrent(actor, scope);
    }

    public MembershipCapabilities capabilities(TrustedActor actor, String tenantId, Scope scope) {
        if (scope == null) {
            return read(actor, tenantRange(tenantId), () -> {
                WorkspaceAccessSupport.TenantSnapshot snapshot = tenant(actor, tenantId, TENANT_READ);
                boolean organization = snapshot.tenant().type() == TenantTypeEnum.ORGANIZATION;
                TenantRoleEnum role = snapshot.member().role();
                boolean owner = organization && role == TenantRoleEnum.OWNER;
                boolean manage = owner || organization && role == TenantRoleEnum.ADMIN;
                return new MembershipCapabilities(true, manage, manage, owner, owner, false,
                        manage ? snapshot.member().id() : null, manage ? snapshot.member().version() : null);
            });
        }
        return read(actor, range(scope), () -> {
            WorkspaceAccessSupport.WorkspaceSnapshot snapshot = workspace(actor, scope, WORKSPACE_READ);
            WorkspaceRoleEnum role = snapshot.member().role();
            boolean writable = snapshot.workspace().state() == WorkspaceStateEnum.ACTIVE;
            boolean owner = role == WorkspaceRoleEnum.OWNER;
            boolean manage = writable && (owner || role == WorkspaceRoleEnum.ADMIN);
            boolean transfer = manage && owner && !access.isDefaultSpaceCurrent(snapshot);
            boolean leave = writable && !owner;
            return new MembershipCapabilities(true, manage, manage, manage && owner, transfer, leave,
                    manage || leave ? snapshot.member().id() : null, manage || leave ? snapshot.member().version() : null);
        });
    }

    public void requireInvitationAccess(TrustedActor actor, String tenantId, Scope scope) {
        read(actor, scope == null ? tenantRange(tenantId) : range(scope), () -> {
            if (scope == null) {
                organization(actor, tenantId, TENANT_MEMBER_MANAGE);
            } else {
                workspace(actor, scope, WORKSPACE_INVITE);
            }
            return true;
        });
    }

    @Override
    public TenantView getTenant(TrustedActor actor, String tenantId) {
        return read(actor, tenantRange(tenantId), () -> WorkspaceViewMapper.tenant(tenant(actor, tenantId, TENANT_READ)));
    }

    @Override
    public CommandReceipt<TenantView> createOrganization(TrustedActor actor, TenantCommands.CreateOrganization command) {
        return tenantGovernance.createOrganization(actor, command);
    }

    @Override
    public CommandReceipt<TenantView> updateTenant(TrustedActor actor, TenantCommands.UpdateTenant command) {
        return tenantGovernance.updateTenant(actor, command);
    }

    @Override
    public PageResult<TenantMemberView> listMembers(TrustedActor actor, String tenantId, PageQuery query) {
        return read(actor, tenantRange(tenantId), () -> {
            organization(actor, tenantId, TENANT_MEMBER_MANAGE);
            return tenantMembers.list(tenantId, query).map(DefaultMembershipGovernanceService::view);
        });
    }

    @Override
    public PageResult<MemberView> listMembers(TrustedActor actor, Scope scope, PageQuery query) {
        return read(actor, range(scope), () -> {
            workspace(actor, scope, WORKSPACE_MEMBER_MANAGE);
            return spaceMembers.list(scope, query).map(DefaultMembershipGovernanceService::view);
        });
    }

    @Override
    public PageResult<TenantInvitationView> listTenantInvitations(TrustedActor actor, String tenantId, PageQuery query) {
        return read(actor, tenantRange(tenantId), () -> {
            organization(actor, tenantId, TENANT_MEMBER_MANAGE);
            return tenantInvitations.listTenantInvitations(tenantId, null, query).map(this::view);
        });
    }

    @Override
    public PageResult<TenantInvitationView> listMyTenantInvitations(TrustedActor actor, PageQuery query) {
        String subject = principals.requireUser(actor).subjectId();
        return tx.readOnly(() -> tenantInvitations.listTenantInvitations(null, subject, query).map(this::view));
    }

    @Override
    public PageResult<InvitationView> listWorkspaceInvitations(TrustedActor actor, Scope scope, PageQuery query) {
        return read(actor, range(scope), () -> {
            workspace(actor, scope, WORKSPACE_INVITE);
            return spaceInvitations.listForWorkspace(scope, query).map(this::view);
        });
    }

    @Override
    public PageResult<InvitationView> listInvitations(TrustedActor actor, PageQuery query) {
        String subject = principals.requireUser(actor).subjectId();
        return tx.readOnly(() -> spaceInvitations.listForSubject(subject, query).map(this::view));
    }

    @Override
    public CommandReceipt<TenantInvitationView> inviteTenantMember(TrustedActor actor, TenantCommands.InviteTenantMember command) {
        String id = command.tenantId();
        String target = command.targetSubjectId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:invitation:issue";
        return commands.execute(actor, command.meta(), scope, type, TENANT_INVITATION, TenantInvitationView.class,
                fields(command.expectedTenantVersion(), target, command.expiresAt()), () -> gate(actor, scope, target),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    Tenant tenant = organization(actor, id, TENANT_MEMBER_MANAGE).tenant();
                    check(tenant.version() == command.expectedTenantVersion());
                    activeUser(target);
                    allowed(!subject(actor).equals(target));
                    TenantInvitation invitation = new TenantInvitation(ids.nextId(), id, target, InvitationStateEnum.PENDING,
                            expiry(command.expiresAt()), subject(actor), null, 0);
                    check(tenantInvitations.insertTenantInvitation(invitation));
                    event(actor, scope, type, operation, 0, invitation.id());
                    return result(view(invitation), 0);
                });
    }

    @Override
    public CommandReceipt<InvitationView> invite(TrustedActor actor, WorkspaceCommands.InviteMember command) {
        Scope space = command.scope();
        String target = command.targetSubjectId();
        CommandScope scope = range(space);
        String type = "workspace:invitation:issue";
        return commands.execute(actor, command.meta(), scope, type, SPACE_INVITATION, InvitationView.class,
                fields(command.expectedWorkspaceVersion(), target, command.role(), command.expiresAt()), () -> gate(actor, scope, target),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    Workspace workspace = workspace(actor, space, WORKSPACE_INVITE).workspace();
                    check(workspace.version() == command.expectedWorkspaceVersion());
                    activeUser(target);
                    allowed(!subject(actor).equals(target));
                    WorkspaceInvitation invitation = new WorkspaceInvitation(ids.nextId(), space, target, command.role(),
                            InvitationStateEnum.PENDING, expiry(command.expiresAt()), subject(actor), null, 0);
                    check(spaceInvitations.insert(invitation));
                    event(actor, scope, type, operation, 0, invitation.id());
                    return result(view(invitation), 0);
                });
    }

    @Override
    public CommandReceipt<TenantInvitationView> revokeTenantInvitation(TrustedActor actor, TenantCommands.RevokeTenantInvitation command) {
        String id = command.tenantId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:invitation:revoke";
        return commands.execute(actor, command.meta(), scope, type, TENANT_INVITATION, TenantInvitationView.class,
                fields(command.invitationId(), command.expectedInvitationVersion(), command.expectedTenantVersion()), () -> gate(actor, scope),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    check(organization(actor, id, TENANT_MEMBER_MANAGE).tenant().version() == command.expectedTenantVersion());
                    tenantInvitations.findTenantInvitation(command.invitationId())
                            .filter(value -> value.tenantId().equals(id)).orElseThrow(DefaultMembershipGovernanceService::missing);
                    TenantInvitation invitation = tenantInvitations.lockTenantInvitation(command.invitationId())
                            .filter(value -> value.tenantId().equals(id)).orElseThrow(DefaultMembershipGovernanceService::missing);
                    pending(invitation.state(), invitation.expiresAt(), invitation.version(), command.expectedInvitationVersion());
                    TenantInvitation updated = transition(invitation, InvitationStateEnum.REVOKED, null);
                    check(tenantInvitations.replaceTenantInvitation(updated, invitation.version()));
                    event(actor, scope, type, operation, updated.version(), updated.id());
                    return result(view(updated), updated.version());
                });
    }

    @Override
    public CommandReceipt<InvitationView> revokeInvitation(TrustedActor actor, WorkspaceCommands.RevokeInvitation command) {
        Scope space = command.scope();
        CommandScope scope = range(space);
        String type = "workspace:invitation:revoke";
        return commands.execute(actor, command.meta(), scope, type, SPACE_INVITATION, InvitationView.class,
                fields(command.invitationId(), command.expectedInvitationVersion(), command.expectedWorkspaceVersion()), () -> gate(actor, scope),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    check(workspace(actor, space, WORKSPACE_INVITE).workspace().version() == command.expectedWorkspaceVersion());
                    spaceInvitations.find(command.invitationId())
                            .filter(value -> value.scope().equals(space)).orElseThrow(DefaultMembershipGovernanceService::missing);
                    WorkspaceInvitation invitation = spaceInvitations.lock(command.invitationId())
                            .filter(value -> value.scope().equals(space)).orElseThrow(DefaultMembershipGovernanceService::missing);
                    pending(invitation.state(), invitation.expiresAt(), invitation.version(), command.expectedInvitationVersion());
                    WorkspaceInvitation updated = transition(invitation, InvitationStateEnum.REVOKED, null);
                    check(spaceInvitations.replace(updated, invitation.version()));
                    event(actor, scope, type, operation, updated.version(), updated.id());
                    return result(view(updated), updated.version());
                });
    }

    @Override
    public CommandReceipt<TenantJoinResult> acceptTenantInvitation(TrustedActor actor, TenantCommands.AcceptTenantInvitation command) {
        String subject = principals.requireUser(actor).subjectId();
        TenantInvitation located = tenantInvitations.findTenantInvitation(command.invitationId())
                .filter(value -> value.targetSubjectId().equals(subject)).orElseThrow(DefaultMembershipGovernanceService::missing);
        String id = located.tenantId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:invitation:accept";
        return commands.execute(actor, command.meta(), scope, type, TENANT_JOIN, TenantJoinResult.class,
                fields(located.id(), command.expectedInvitationVersion()), () -> gate(actor, scope, located.issuedBy()),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.member().version()), operation -> {
                    TenantInvitation invitation = tenantInvitations.lockTenantInvitation(located.id())
                            .filter(value -> value.tenantId().equals(id) && value.targetSubjectId().equals(subject)
                                    && value.issuedBy().equals(located.issuedBy())).orElseThrow(DefaultMembershipGovernanceService::missing);
                    pending(invitation.state(), invitation.expiresAt(), invitation.version(), command.expectedInvitationVersion());
                    Tenant tenant = organization(issuer(invitation.issuedBy()), id, TENANT_MEMBER_MANAGE).tenant();
                    TenantMembership old = tenantMembers.lock(id, subject).orElse(null);
                    boolean already = active(old) && old.role() != TenantRoleEnum.GUEST;
                    TenantMembership member = already ? old : joinTenant(tenant, subject, TenantRoleEnum.MEMBER, old);
                    check(tenantInvitations.replaceTenantInvitation(transition(invitation, InvitationStateEnum.ACCEPTED, member.id()), invitation.version()));
                    if (!already) {
                        bump(tenant);
                    }
                    event(actor, scope, type, operation, member.version(), invitation.id());
                    return result(new TenantJoinResult(outcome(already), id, view(member)), member.version());
                });
    }

    @Override
    public CommandReceipt<JoinResult> accept(TrustedActor actor, WorkspaceCommands.AcceptInvitation command) {
        String subject = principals.requireUser(actor).subjectId();
        WorkspaceInvitation located = spaceInvitations.find(command.invitationId())
                .filter(value -> value.targetSubjectId().equals(subject)).orElseThrow(DefaultMembershipGovernanceService::missing);
        Scope space = located.scope();
        CommandScope scope = range(space);
        String type = "workspace:invitation:accept";
        return commands.execute(actor, command.meta(), scope, type, SPACE_JOIN, JoinResult.class,
                fields(located.id(), command.expectedInvitationVersion()), () -> gate(actor, scope, located.issuedBy()),
                saved -> authorizeReceipt(actor, scope, type, saved, saved.member().version()), operation -> {
                    WorkspaceInvitation invitation = spaceInvitations.lock(located.id())
                            .filter(value -> value.scope().equals(space) && value.targetSubjectId().equals(subject)
                                    && value.issuedBy().equals(located.issuedBy())).orElseThrow(DefaultMembershipGovernanceService::missing);
                    pending(invitation.state(), invitation.expiresAt(), invitation.version(), command.expectedInvitationVersion());
                    WorkspaceAccessSupport.WorkspaceSnapshot issuer = workspace(issuer(invitation.issuedBy()), space, WORKSPACE_INVITE);
                    Tenant tenant = issuer.tenant().tenant();
                    TenantMembership oldTenant = tenantMembers.lock(tenant.id(), subject).orElse(null);
                    if (!active(oldTenant)) {
                        joinTenant(tenant, subject, TenantRoleEnum.GUEST, oldTenant);
                        bump(tenant);
                    }
                    WorkspaceMembership old = spaceMembers.lock(space, subject).orElse(null);
                    boolean already = active(old);
                    WorkspaceMembership member = already ? old : joinSpace(space, subject, invitation.role(), old);
                    check(spaceInvitations.replace(transition(invitation, InvitationStateEnum.ACCEPTED, member.id()), invitation.version()));
                    if (!already) {
                        bump(issuer.workspace());
                    }
                    event(actor, scope, type, operation, member.version(), invitation.id());
                    return result(new JoinResult(outcome(already), space, view(member)), member.version());
                });
    }

    @Override
    public CommandReceipt<TenantMemberView> changeMemberRole(TrustedActor actor, TenantCommands.ChangeTenantMemberRole command) {
        String id = command.tenantId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:member:role";
        String target = tenantSubject(actor, id, command.memberId());
        return commands.execute(actor, command.meta(), scope, type, TENANT_MEMBER, TenantMemberView.class,
                fields(command.memberId(), command.expectedMemberVersion(), command.expectedTenantVersion(), command.role()),
                () -> gate(actor, scope, target), saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    WorkspaceAccessSupport.TenantSnapshot snapshot = organization(actor, id, TENANT_MEMBER_MANAGE);
                    TenantMembership member = currentTenantMember(id, target, command.memberId(), command.expectedMemberVersion());
                    check(snapshot.tenant().version() == command.expectedTenantVersion());
                    protectTenantTarget(snapshot.member(), member);
                    allowed(member.role() != TenantRoleEnum.GUEST && (command.role() == TenantRoleEnum.MEMBER
                            || command.role() == TenantRoleEnum.ADMIN && snapshot.member().role() == TenantRoleEnum.OWNER));
                    activeUser(target);
                    TenantMembership updated = update(member, command.role(), MembershipStateEnum.ACTIVE);
                    check(tenantMembers.replace(updated, member.version()));
                    bump(snapshot.tenant());
                    event(actor, scope, type, operation, updated.version(), member.id());
                    return result(view(updated), updated.version());
                });
    }

    @Override
    public CommandReceipt<Void> removeMember(TrustedActor actor, TenantCommands.RemoveTenantMember command) {
        String id = command.tenantId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:member:remove";
        String target = tenantSubject(actor, id, command.memberId());
        return commands.execute(actor, command.meta(), scope, type, VOID, Void.class,
                fields(command.memberId(), command.expectedMemberVersion(), command.expectedTenantVersion()),
                () -> gate(actor, scope, target), saved -> organization(actor, id, TENANT_MEMBER_MANAGE), operation -> {
                    WorkspaceAccessSupport.TenantSnapshot snapshot = organization(actor, id, TENANT_MEMBER_MANAGE);
                    TenantMembership member = currentTenantMember(id, target, command.memberId(), command.expectedMemberVersion());
                    check(snapshot.tenant().version() == command.expectedTenantVersion());
                    protectTenantTarget(snapshot.member(), member);
                    allowed(!spaceMembers.ownsWorkspace(id, target));
                    spaceMembers.revokeTenantAccess(id, target);
                    tenantInvitations.invalidatePending(id, null, target);
                    TenantMembership updated = update(member, member.role(), MembershipStateEnum.REVOKED);
                    check(tenantMembers.replace(updated, member.version()));
                    Tenant changed = bump(snapshot.tenant());
                    event(actor, scope, type, operation, changed.version(), member.id());
                    return result(null, changed.version());
                });
    }

    @Override
    public CommandReceipt<TenantView> transferOwner(TrustedActor actor, TenantCommands.TransferTenantOwner command) {
        String id = command.tenantId();
        CommandScope scope = tenantRange(id);
        String type = "tenant:member:transfer";
        String target = tenantSubject(actor, id, command.targetMemberId());
        return commands.execute(actor, command.meta(), scope, type, "tenant:view:v1", TenantView.class,
                fields(command.targetMemberId(), command.expectedOwnerMemberVersion(), command.expectedTargetMemberVersion(), command.expectedTenantVersion()),
                () -> gate(actor, scope, target), saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    WorkspaceAccessSupport.TenantSnapshot snapshot = organization(actor, id, TENANT_OWNER_TRANSFER);
                    TenantMembership owner = snapshot.member();
                    TenantMembership member = currentTenantMember(id, target, command.targetMemberId(), command.expectedTargetMemberVersion());
                    check(owner.version() == command.expectedOwnerMemberVersion() && snapshot.tenant().version() == command.expectedTenantVersion());
                    allowed(member.role() == TenantRoleEnum.MEMBER || member.role() == TenantRoleEnum.ADMIN);
                    activeUser(target);
                    check(tenantMembers.replace(update(owner, TenantRoleEnum.ADMIN, MembershipStateEnum.ACTIVE), owner.version()));
                    check(tenantMembers.replace(update(member, TenantRoleEnum.OWNER, MembershipStateEnum.ACTIVE), member.version()));
                    Tenant changed = bump(snapshot.tenant());
                    event(actor, scope, type, operation, changed.version(), member.id());
                    return result(new TenantView(id, changed.name(), changed.type(), changed.state(), TenantRoleEnum.ADMIN, changed.version()), changed.version());
                });
    }

    @Override
    public CommandReceipt<MemberView> changeRole(TrustedActor actor, WorkspaceCommands.ChangeMemberRole command) {
        Scope space = command.scope();
        CommandScope scope = range(space);
        String type = "workspace:member:role";
        String target = spaceSubject(actor, space, command.memberId());
        return commands.execute(actor, command.meta(), scope, type, SPACE_MEMBER, MemberView.class,
                fields(command.memberId(), command.expectedMemberVersion(), command.expectedWorkspaceVersion(), command.role()),
                () -> gate(actor, scope, target), saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    WorkspaceAccessSupport.WorkspaceSnapshot snapshot = workspace(actor, space, WORKSPACE_MEMBER_MANAGE);
                    WorkspaceMembership member = currentSpaceMember(space, target, command.memberId(), command.expectedMemberVersion());
                    check(snapshot.workspace().version() == command.expectedWorkspaceVersion());
                    protectSpaceTarget(snapshot.member(), member);
                    allowed(command.role() != WorkspaceRoleEnum.ADMIN || snapshot.member().role() == WorkspaceRoleEnum.OWNER);
                    activeUser(target);
                    allowed(active(tenantMembers.lock(space.tenantId(), target).orElse(null)));
                    WorkspaceMembership updated = update(member, command.role(), MembershipStateEnum.ACTIVE);
                    check(spaceMembers.replace(updated, member.version()));
                    bump(snapshot.workspace());
                    event(actor, scope, type, operation, updated.version(), member.id());
                    return result(view(updated), updated.version());
                });
    }

    @Override
    public CommandReceipt<Void> removeMember(TrustedActor actor, WorkspaceCommands.RemoveMember command) {
        Scope space = command.scope();
        CommandScope scope = range(space);
        String type = "workspace:member:remove";
        String target = spaceSubject(actor, space, command.memberId());
        return commands.execute(actor, command.meta(), scope, type, VOID, Void.class,
                fields(command.memberId(), command.expectedMemberVersion(), command.expectedWorkspaceVersion()),
                () -> gate(actor, scope, target), saved -> workspace(actor, space, WORKSPACE_MEMBER_MANAGE), operation -> {
                    WorkspaceAccessSupport.WorkspaceSnapshot snapshot = workspace(actor, space, WORKSPACE_MEMBER_MANAGE);
                    WorkspaceMembership member = currentSpaceMember(space, target, command.memberId(), command.expectedMemberVersion());
                    check(snapshot.workspace().version() == command.expectedWorkspaceVersion());
                    protectSpaceTarget(snapshot.member(), member);
                    return removeSpaceMember(actor, snapshot.workspace(), member, scope, type, operation);
                });
    }

    @Override
    public CommandReceipt<Void> leave(TrustedActor actor, WorkspaceCommands.LeaveWorkspace command) {
        Scope space = command.scope();
        CommandScope scope = range(space);
        String type = "workspace:member:leave";
        return commands.execute(actor, command.meta(), scope, type, VOID, Void.class,
                fields(command.expectedMemberVersion(), command.expectedWorkspaceVersion()), () -> gate(actor, scope),
                saved -> requireLeft(actor, space, Math.incrementExact(command.expectedMemberVersion())), operation -> {
                    WorkspaceAccessSupport.WorkspaceSnapshot snapshot = workspace(actor, space, WORKSPACE_READ);
                    WorkspaceMembership member = snapshot.member();
                    check(snapshot.workspace().version() == command.expectedWorkspaceVersion() && member.version() == command.expectedMemberVersion());
                    allowed(snapshot.workspace().state() == WorkspaceStateEnum.ACTIVE && member.role() != WorkspaceRoleEnum.OWNER);
                    return removeSpaceMember(actor, snapshot.workspace(), member, scope, type, operation);
                });
    }

    private DefaultCommandExecutor.Result<Void> removeSpaceMember(TrustedActor actor, Workspace workspace, WorkspaceMembership member,
                                                                  CommandScope scope, String type, String operation) {
        WorkspaceMembership updated = update(member, member.role(), MembershipStateEnum.REVOKED);
        check(spaceMembers.replace(updated, member.version()));
        tenantInvitations.invalidatePending(scope.tenantId(), scope.workspaceId(), member.subjectId());
        bump(workspace);
        event(actor, scope, type, operation, updated.version(), member.id());
        return result(null, updated.version());
    }

    @Override
    public CommandReceipt<WorkspaceView> transferOwner(TrustedActor actor, WorkspaceCommands.TransferOwner command) {
        Scope space = command.scope();
        CommandScope scope = range(space);
        String type = "workspace:member:transfer";
        String target = spaceSubject(actor, space, command.targetMemberId());
        return commands.execute(actor, command.meta(), scope, type, "workspace:view:v1", WorkspaceView.class,
                fields(command.targetMemberId(), command.expectedOwnerMemberVersion(), command.expectedTargetMemberVersion(), command.expectedWorkspaceVersion()),
                () -> gate(actor, scope, target), saved -> authorizeReceipt(actor, scope, type, saved, saved.version()), operation -> {
                    WorkspaceAccessSupport.WorkspaceSnapshot snapshot = workspace(actor, space, WORKSPACE_OWNER_TRANSFER);
                    WorkspaceMembership owner = snapshot.member();
                    WorkspaceMembership member = currentSpaceMember(space, target, command.targetMemberId(), command.expectedTargetMemberVersion());
                    check(owner.version() == command.expectedOwnerMemberVersion() && snapshot.workspace().version() == command.expectedWorkspaceVersion());
                    allowed(member.role() == WorkspaceRoleEnum.MEMBER || member.role() == WorkspaceRoleEnum.ADMIN);
                    activeUser(target);
                    TenantMembership membership = tenantMembers.lock(space.tenantId(), target).orElse(null);
                    allowed(active(membership));
                    check(spaceMembers.replace(update(owner, WorkspaceRoleEnum.ADMIN, MembershipStateEnum.ACTIVE), owner.version()));
                    check(spaceMembers.replace(update(member, WorkspaceRoleEnum.OWNER, MembershipStateEnum.ACTIVE), member.version()));
                    Workspace changed = bump(snapshot.workspace());
                    event(actor, scope, type, operation, changed.version(), member.id());
                    return result(new WorkspaceView(space, changed.name(), changed.description(), changed.state(), WorkspaceRoleEnum.ADMIN, changed.version()), changed.version());
                });
    }

    private static void protectTenantTarget(TenantMembership actor, TenantMembership target) {
        allowed(!actor.id().equals(target.id()) && target.role() != TenantRoleEnum.OWNER
                && (actor.role() == TenantRoleEnum.OWNER || target.role() != TenantRoleEnum.ADMIN));
    }

    private static void protectSpaceTarget(WorkspaceMembership actor, WorkspaceMembership target) {
        allowed(!actor.id().equals(target.id()) && target.role() != WorkspaceRoleEnum.OWNER
                && (actor.role() == WorkspaceRoleEnum.OWNER || target.role() != WorkspaceRoleEnum.ADMIN));
    }

    private String tenantSubject(TrustedActor actor, String tenant, String member) {
        auth.require(actor, DefaultAuthorizationService.tenantRequest(tenant, TENANT_MEMBER_MANAGE, "membership-target"));
        return tenantMembers.findById(tenant, member).orElseThrow(DefaultMembershipGovernanceService::missing).subjectId();
    }

    private String spaceSubject(TrustedActor actor, Scope scope, String member) {
        auth.require(actor, DefaultAuthorizationService.workspaceRequest(scope, WORKSPACE_MEMBER_MANAGE, "membership-target"));
        return spaceMembers.findById(scope, member).orElseThrow(DefaultMembershipGovernanceService::missing).subjectId();
    }

    private TenantMembership currentTenantMember(String tenant, String subject, String id, long version) {
        TenantMembership member = tenantMembers.lock(tenant, subject).filter(value -> value.id().equals(id) && active(value))
                .orElseThrow(DefaultMembershipGovernanceService::missing);
        check(member.version() == version);
        return member;
    }

    private WorkspaceMembership currentSpaceMember(Scope scope, String subject, String id, long version) {
        WorkspaceMembership member = spaceMembers.lock(scope, subject).filter(value -> value.id().equals(id) && active(value))
                .orElseThrow(DefaultMembershipGovernanceService::missing);
        check(member.version() == version);
        return member;
    }

    private TenantMembership joinTenant(Tenant tenant, String subject, TenantRoleEnum role, TenantMembership old) {
        if (old != null && !active(old)) {
            allowed(!spaceMembers.ownsWorkspace(tenant.id(), subject));
            spaceMembers.revokeTenantAccess(tenant.id(), subject);
        }
        TenantMembership next = new TenantMembership(old == null ? ids.nextId() : old.id(), tenant.id(), subject, role,
                MembershipStateEnum.ACTIVE, old == null ? 0 : active(old) ? old.generation() : Math.incrementExact(old.generation()),
                old == null ? 0 : Math.incrementExact(old.version()));
        check(old == null ? tenantMembers.insert(next) : tenantMembers.replace(next, old.version()));
        return next;
    }

    private WorkspaceMembership joinSpace(Scope space, String subject, WorkspaceRoleEnum role, WorkspaceMembership old) {
        WorkspaceMembership next = new WorkspaceMembership(old == null ? ids.nextId() : old.id(), space, subject, role,
                MembershipStateEnum.ACTIVE, old == null ? 0 : Math.incrementExact(old.generation()),
                old == null ? 0 : Math.incrementExact(old.version()));
        check(old == null ? spaceMembers.insert(next) : spaceMembers.replace(next, old.version()));
        return next;
    }

    private Tenant bump(Tenant tenant) {
        Tenant next = new Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(), tenant.state(),
                tenant.createdBy(), Math.incrementExact(tenant.version()), Math.incrementExact(tenant.authEpoch()),
                tenant.createdAt(), PlatformTimes.notBefore(clock, tenant.updatedAt()));
        check(tenants.replace(next, tenant.version()));
        return next;
    }

    private Workspace bump(Workspace space) {
        Workspace next = new Workspace(space.scope(), space.name(), space.description(), space.state(),
                space.createdBy(), Math.incrementExact(space.version()), Math.incrementExact(space.authEpoch()),
                space.createdAt(), PlatformTimes.notBefore(clock, space.updatedAt()));
        check(spaces.replace(next, space.version()));
        return next;
    }

    private Principal activeUser(String subject) {
        return identity.findCurrentBySubjectId(subject).filter(p -> p.subjectId().equals(subject)
                        && p.kind() == PrincipalKindEnum.USER && p.status() == PrincipalStatusEnum.ACTIVE)
                .orElseThrow(DefaultMembershipGovernanceService::missing);
    }

    private TrustedActor issuer(String subject) {
        return new TrustedActor(activeUser(subject), clock.instant());
    }

    private Instant expiry(Instant supplied) {
        Instant now = PlatformTimes.businessNow(clock);
        Instant end = supplied == null ? now.plus(7, ChronoUnit.DAYS) : supplied.truncatedTo(ChronoUnit.MICROS);
        if (!end.isAfter(now) || end.isAfter(now.plus(30, ChronoUnit.DAYS))) {
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, "invitation-expiry");
        }
        return end;
    }

    private void pending(InvitationStateEnum state, Instant expiry, long version, long expected) {
        check(version == expected && state == InvitationStateEnum.PENDING && expiry.isAfter(clock.instant()));
    }

    private InvitationStateEnum displayed(InvitationStateEnum state, Instant expiry) {
        return state == InvitationStateEnum.PENDING && !expiry.isAfter(clock.instant()) ? InvitationStateEnum.EXPIRED : state;
    }

    private TenantInvitationView view(TenantInvitation invitation) {
        return new TenantInvitationView(invitation.id(), invitation.tenantId(), invitation.targetSubjectId(),
                displayed(invitation.state(), invitation.expiresAt()), invitation.expiresAt(), invitation.version());
    }

    private InvitationView view(WorkspaceInvitation invitation) {
        return new InvitationView(invitation.id(), invitation.scope(), invitation.targetSubjectId(), invitation.role(),
                displayed(invitation.state(), invitation.expiresAt()), invitation.expiresAt(), invitation.version());
    }

    private static TenantMemberView view(TenantMembership member) {
        return new TenantMemberView(member.id(), member.subjectId(), member.role(), member.state(), member.version());
    }

    private static MemberView view(WorkspaceMembership member) {
        return new MemberView(member.id(), member.subjectId(), member.role(), member.state(), member.version());
    }

    private static TenantInvitation transition(TenantInvitation v, InvitationStateEnum state, String member) {
        return new TenantInvitation(v.id(), v.tenantId(), v.targetSubjectId(), state, v.expiresAt(), v.issuedBy(), member, Math.incrementExact(v.version()));
    }

    private static WorkspaceInvitation transition(WorkspaceInvitation v, InvitationStateEnum state, String member) {
        return new WorkspaceInvitation(v.id(), v.scope(), v.targetSubjectId(), v.role(), state, v.expiresAt(), v.issuedBy(), member, Math.incrementExact(v.version()));
    }

    private static TenantMembership update(TenantMembership m, TenantRoleEnum role, MembershipStateEnum state) {
        return new TenantMembership(m.id(), m.tenantId(), m.subjectId(), role, state, m.generation(), Math.incrementExact(m.version()));
    }

    private static WorkspaceMembership update(WorkspaceMembership m, WorkspaceRoleEnum role, MembershipStateEnum state) {
        return new WorkspaceMembership(m.id(), m.scope(), m.subjectId(), role, state, m.generation(), Math.incrementExact(m.version()));
    }

    private static boolean active(TenantMembership member) {
        return member != null && member.state() == MembershipStateEnum.ACTIVE;
    }

    private static boolean active(WorkspaceMembership member) {
        return member != null && member.state() == MembershipStateEnum.ACTIVE;
    }

    private static JoinOutcomeEnum outcome(boolean already) {
        return already ? JoinOutcomeEnum.ALREADY_MEMBER : JoinOutcomeEnum.JOINED;
    }

    private void requireLeft(TrustedActor actor, Scope space, long memberVersion) {
        tenant(actor, space.tenantId(), TENANT_READ);
        WorkspaceMembership member = spaceMembers.lock(space, subject(actor)).orElseThrow(DefaultMembershipGovernanceService::missing);
        allowed(member.state() == MembershipStateEnum.REVOKED && member.version() == memberVersion);
    }

    private static String schema(String type) {
        return switch (type) {
            case "tenant:invitation:issue", "tenant:invitation:revoke" -> TENANT_INVITATION;
            case "workspace:invitation:issue", "workspace:invitation:revoke" -> SPACE_INVITATION;
            case "tenant:invitation:accept" -> TENANT_JOIN;
            case "workspace:invitation:accept" -> SPACE_JOIN;
            case "tenant:member:role" -> TENANT_MEMBER;
            case "workspace:member:role" -> SPACE_MEMBER;
            case "tenant:member:remove", "workspace:member:remove", "workspace:member:leave" -> VOID;
            case "tenant:member:transfer" -> "tenant:view:v1";
            case "workspace:member:transfer" -> "workspace:view:v1";
            default -> null;
        };
    }

    static boolean supportsReceipt(String type) {
        return schema(type) != null;
    }

    void lockStoredReceiptScope(TrustedActor actor, StoredCommandReceipt stored) {
        String type = stored.identity().commandType();
        CommandScope scope = stored.identity().scope();
        if (!Objects.equals(schema(type), stored.resultSchema()) || scope.tenantId() == null
                || type.startsWith("tenant:") != (scope.workspaceId() == null)) {
            throw new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "membership-receipt");
        }
        Object value = stored.receipt().value();
        if (value instanceof TenantMemberView member) {
            gate(actor, scope, member.subjectId());
        } else if (value instanceof MemberView member) {
            gate(actor, scope, member.subjectId());
        } else {
            gate(actor, scope);
        }
    }

    void authorizeStoredReceipt(TrustedActor actor, StoredCommandReceipt stored) {
        authorizeReceipt(actor, stored.identity().scope(), stored.identity().commandType(),
                stored.receipt().value(), stored.receipt().resultVersion());
    }

    private void authorizeReceipt(TrustedActor actor, CommandScope scope, String type, Object saved, long version) {
        String tenant = scope.tenantId();
        Scope space = scope.workspaceId() == null ? null : new Scope(tenant, scope.workspaceId());
        if (type.equals("tenant:invitation:accept")) {
            TenantJoinResult result = (TenantJoinResult) saved;
            TenantMembership current = tenant(actor, tenant, TENANT_READ).member();
            allowed(result.tenantId().equals(tenant) && view(current).equals(result.member()) && current.version() == version);
            return;
        }
        if (type.equals("workspace:invitation:accept")) {
            JoinResult result = (JoinResult) saved;
            WorkspaceMembership current = workspace(actor, space, WORKSPACE_READ).member();
            allowed(result.scope().equals(space) && view(current).equals(result.member()) && current.version() == version);
            return;
        }
        if (type.equals("workspace:member:leave")) {
            requireLeft(actor, space, version);
            return;
        }
        if (space == null) {
            organization(actor, tenant, TENANT_MEMBER_MANAGE);
        } else {
            workspace(actor, space, type.startsWith("workspace:invitation:") ? WORKSPACE_INVITE : WORKSPACE_MEMBER_MANAGE);
        }
        if (saved instanceof TenantInvitationView view) {
            allowed(tenant.equals(view.tenantId()) && version == view.version()
                    && tenantInvitations.lockTenantInvitation(view.invitationId()).map(this::view).filter(view::equals).isPresent());
        } else if (saved instanceof InvitationView view) {
            allowed(view.scope().equals(space) && version == view.version()
                    && spaceInvitations.lock(view.invitationId()).map(this::view).filter(view::equals).isPresent());
        } else if (saved instanceof TenantMemberView view) {
            allowed(version == view.version() && tenantMembers.lock(tenant, view.subjectId())
                    .map(DefaultMembershipGovernanceService::view).filter(view::equals).isPresent());
        } else if (saved instanceof MemberView view) {
            allowed(version == view.version() && spaceMembers.lock(space, view.subjectId())
                    .map(DefaultMembershipGovernanceService::view).filter(view::equals).isPresent());
        } else if (saved instanceof TenantView view) {
            allowed(tenant.equals(view.id()) && version == view.version());
        } else if (saved instanceof WorkspaceView view) {
            allowed(view.scope().equals(space) && version == view.version());
        }
    }

    private static List<String> fields(Object... values) {
        return Arrays.stream(values).map(value -> Objects.toString(value, "")).toList();
    }

    private void event(TrustedActor actor, CommandScope scope, String type, String operation, long version, String target) {
        effects.append(actor, scope, type, operation, version, Map.of("recordId", target));
    }

    private static <T> DefaultCommandExecutor.Result<T> result(T value, long version) {
        return new DefaultCommandExecutor.Result<>(value, version);
    }

    private static void check(boolean success) {
        if (!success) {
            throw new PlatformException(PlatformErrorCodeEnum.VERSION_CONFLICT, "membership");
        }
    }

    private static void allowed(boolean success) {
        if (!success) {
            throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "membership");
        }
    }

    private static PlatformException missing() {
        return new PlatformException(PlatformErrorCodeEnum.NOT_FOUND, "membership");
    }
}
