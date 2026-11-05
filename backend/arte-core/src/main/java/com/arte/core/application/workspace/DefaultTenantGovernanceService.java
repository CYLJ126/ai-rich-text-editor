package com.arte.core.application.workspace;

import com.arte.core.api.workspace.TenantGovernanceService;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.application.platform.PlatformTimes;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.platform.PlatformIdGenerator;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PolicyStore;
import com.arte.core.spi.workspace.TenantMembershipStore;
import com.arte.core.spi.workspace.TenantProvisioningPolicy;
import com.arte.core.spi.workspace.TenantStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static com.arte.core.constant.platform.PlatformActions.TENANT_READ;
import static com.arte.core.constant.platform.PlatformActions.TENANT_UPDATE;

/**
 * 租户名称 CAS 及组织初始租户、负责人、政策的原子提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 17:11 ✾
 */
final class DefaultTenantGovernanceService implements TenantGovernanceService {

    static final String CREATE = "tenant:organization:create";
    static final String UPDATE = "tenant:update";
    static final String VIEW_SCHEMA = "tenant:view:v1";

    private final CurrentPrincipalResolver principals;
    private final WorkspaceAccessSupport access;
    private final DefaultAuthorizationService authorization;
    private final WorkspaceGovernanceLocks locks;
    private final DefaultCommandExecutor commands;
    private final WorkspaceMutationEffects effects;
    private final PublicTransactionRunner transactions;
    private final TenantStore tenants;
    private final TenantMembershipStore members;
    private final PolicyStore policies;
    private final TenantProvisioningPolicy provisioning;
    private final Supplier<OrganizationTenantPolicyTemplate> templates;
    private final PlatformIdGenerator ids;
    private final Clock clock;

    DefaultTenantGovernanceService(CurrentPrincipalResolver principals, WorkspaceAccessSupport access,
                                   DefaultAuthorizationService authorization, WorkspaceGovernanceLocks locks,
                                   DefaultCommandExecutor commands, WorkspaceMutationEffects effects,
                                   PublicTransactionRunner transactions, TenantStore tenants, TenantMembershipStore members,
                                   PolicyStore policies, TenantProvisioningPolicy provisioning,
                                   Supplier<OrganizationTenantPolicyTemplate> templates, PlatformIdGenerator ids, Clock clock) {
        this.principals = principals;
        this.access = access;
        this.authorization = authorization;
        this.locks = locks;
        this.commands = commands;
        this.effects = effects;
        this.transactions = transactions;
        this.tenants = tenants;
        this.members = members;
        this.policies = policies;
        this.provisioning = provisioning;
        this.templates = templates;
        this.ids = ids;
        this.clock = clock;
    }

    TenantDirectoryViews.CreationOptions creationOptions(TrustedActor actor) {
        String subject = principals.requireUser(actor).subjectId();
        return transactions.required(() -> {
            locks.subject(subject);
            principals.requireUserCurrent(actor);
            if (templates.get() == null) {
                return new TenantDirectoryViews.CreationOptions(false, false, "CAPABILITY_NOT_ENABLED");
            }
            boolean allowed = provisioning.mayCreateOrganization(actor);
            return new TenantDirectoryViews.CreationOptions(true, allowed, allowed ? "ALLOWED" : "ACCESS_DENIED");
        });
    }

    @Override
    public CommandReceipt<WorkspaceViews.TenantView> createOrganization(TrustedActor actor, TenantCommands.CreateOrganization command) {
        String subject = principals.requireUser(actor).subjectId();
        return commands.execute(actor, command.meta(), new CommandScope(null, null), CREATE, VIEW_SCHEMA,
                WorkspaceViews.TenantView.class, List.of(command.name()), () -> locks.subject(subject), saved -> {
                    if (saved.type() != TenantTypeEnum.ORGANIZATION || saved.version() != 0 || saved.currentRole() != TenantRoleEnum.OWNER) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    requireCreationReceipt(actor, subject, saved.id());
                }, operationId -> {
                    OrganizationTenantPolicyTemplate template = templates.get();
                    if (template == null) {
                        throw error(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED);
                    }
                    requireProvisioning(actor);
                    Instant now = PlatformTimes.businessNow(clock);
                    String tenantId = ids.nextId();
                    Tenant tenant = new Tenant(tenantId, command.name(), TenantTypeEnum.ORGANIZATION, null,
                            TenantStateEnum.ACTIVE, subject, 0, 0, now, now);
                    check(tenants.insert(tenant));
                    check(members.insert(new TenantMembership(ids.nextId(), tenantId, subject, TenantRoleEnum.OWNER,
                            MembershipStateEnum.ACTIVE, 0, 0)));
                    check(policies.insertTenantPolicy(template.initialPolicy(tenantId)));
                    effects.append(actor, new CommandScope(tenantId, null), "tenant:organization:created", operationId, 0,
                            Map.of("organizationTemplateVersion", Long.toString(template.version())));
                    return new DefaultCommandExecutor.Result<>(new WorkspaceViews.TenantView(tenantId, tenant.name(), tenant.type(),
                            tenant.state(), TenantRoleEnum.OWNER, tenant.version()), 0);
                });
    }

    @Override
    public CommandReceipt<WorkspaceViews.TenantView> updateTenant(TrustedActor actor, TenantCommands.UpdateTenant command) {
        String subject = principals.requireUser(actor).subjectId();
        String id = command.tenantId();
        CommandScope scope = new CommandScope(id, null);
        return commands.execute(actor, command.meta(), scope, UPDATE, VIEW_SCHEMA, WorkspaceViews.TenantView.class,
                List.of(Long.toString(command.expectedTenantVersion()), command.name()), () -> locks.tenant(subject, id), saved -> {
                    if (!saved.id().equals(id)) {
                        throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
                    }
                    requireUpdate(actor, id);
                }, operationId -> {
                    requireUpdate(actor, id);
                    WorkspaceAccessSupport.TenantSnapshot snapshot = access.tenantCurrent(actor, id);
                    Tenant old = snapshot.tenant();
                    check(old.version() == command.expectedTenantVersion());
                    Tenant updated = new Tenant(id, command.name(), old.type(), old.personalSubjectId(), old.state(),
                            old.createdBy(), Math.incrementExact(old.version()), old.authEpoch(), old.createdAt(),
                            PlatformTimes.notBefore(clock, old.updatedAt()));
                    check(tenants.replace(updated, old.version()));
                    effects.append(actor, scope, "tenant:updated", operationId, updated.version(),
                            Map.of("previousTenantVersion", Long.toString(old.version())));
                    return new DefaultCommandExecutor.Result<>(new WorkspaceViews.TenantView(id, updated.name(), updated.type(),
                            updated.state(), snapshot.member().role(), updated.version()), updated.version());
                });
    }

    void requireCreationReceipt(TrustedActor actor, String subject, String tenantId) {
        locks.tenant(subject, tenantId);
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(tenantId, TENANT_READ, CREATE));
        Tenant tenant = access.tenantCurrent(actor, tenantId).tenant();
        if (tenant.type() != TenantTypeEnum.ORGANIZATION || !tenant.createdBy().equals(subject)) {
            throw error(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
        }
        requireProvisioning(actor);
    }

    void requireUpdate(TrustedActor actor, String tenantId) {
        authorization.requireInCurrentTransaction(actor, DefaultAuthorizationService.tenantRequest(tenantId, TENANT_UPDATE, UPDATE));
    }

    private void requireProvisioning(TrustedActor actor) {
        if (!provisioning.mayCreateOrganization(actor)) {
            throw error(PlatformErrorCodeEnum.ACCESS_DENIED);
        }
    }

    private static void check(boolean success) {
        if (!success) {
            throw error(PlatformErrorCodeEnum.VERSION_CONFLICT);
        }
    }

    private static PlatformException error(PlatformErrorCodeEnum code) {
        return new PlatformException(code, "tenant-governance");
    }
}
