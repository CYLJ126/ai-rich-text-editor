package com.arte.core.application.workspace;

import com.arte.core.api.workspace.TenantGovernanceService;
import com.arte.core.api.workspace.TenantQueryService;
import com.arte.core.application.access.DefaultActionRegistry;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.access.DefaultResourceAccessService;
import com.arte.core.application.access.DefaultWorkspaceWriteGuard;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.workspace.OrganizationTenantPolicyTemplate;
import com.arte.core.pojo.workspace.WorkspaceBrowseBudget;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 无框架的首批默认服务装配
 * <p>
 * app 提供同库 SPI 后组合，core 不自行注册 HTTP 或数据库 Bean。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspaceServices {

    private final DefaultResourceAccessService resources;
    private final DefaultPersonalWorkspaceService personal;
    private final DefaultWorkspaceService workspaces;
    private final TenantQueryService tenantQueries;
    private final DefaultTenantGovernanceService tenantGovernance;
    private final DefaultWorkspacePreferenceService preferences;
    private final DefaultWorkspacePolicyService policies;
    private final DefaultScopeResolver scopes;
    private final DefaultAuthorizationService authorization;
    private final DefaultWorkspaceWriteGuard writeGuard;
    private final DefaultCommandReceiptQueryService receiptQueries;

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts) {
        this(identity, transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, policyStore,
                preferenceStore, receipts, outbox, audit, ids, clock, defaults, batchFacts, List.of());
    }

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts, List<WorkspaceProvisioningParticipant> provisioning) {
        this(identity, transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, policyStore, preferenceStore,
                receipts, outbox, audit, ids, clock, defaults, batchFacts, provisioning,
                WorkspaceBrowseBudget.defaults(), System::nanoTime);
    }

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts,
                                    List<WorkspaceProvisioningParticipant> provisioning,
                                    WorkspaceBrowseBudget budget, LongSupplier nanoTime) {
        this(identity, transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, policyStore,
                preferenceStore, receipts, outbox, audit, ids, clock, defaults, batchFacts, provisioning, budget, nanoTime,
                null, List.of(), List.of());
    }

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts,
                                    List<WorkspaceProvisioningParticipant> provisioning,
                                    WorkspaceBrowseBudget budget, LongSupplier nanoTime,
                                    ResourceGrantStore grants,
                                    List<ResourceDescriptorProvider> descriptors,
                                    List<DomainAccessConditionProvider> conditions) {
        this(identity, transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, policyStore,
                preferenceStore, receipts, outbox, audit, ids, clock, defaults, batchFacts, provisioning,
                budget, nanoTime, grants, descriptors, conditions, (actor, tenantId, current) -> false, actor -> false, () -> null);
    }

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts,
                                    List<WorkspaceProvisioningParticipant> provisioning, WorkspaceBrowseBudget budget, LongSupplier nanoTime,
                                    ResourceGrantStore grants, List<ResourceDescriptorProvider> descriptors,
                                    List<DomainAccessConditionProvider> conditions, TenantUpdatePolicy tenantUpdates,
                                    TenantProvisioningPolicy tenantProvisioning, Supplier<OrganizationTenantPolicyTemplate> organizationTemplate) {
        var principals = new CurrentPrincipalResolver(identity);
        var access = new WorkspaceAccessSupport(principals, tenantStore, workspaceStore, tenantMembers,
                workspaceMembers, policyStore, preferenceStore, clock, transactions, batchFacts);
        var locks = new WorkspaceGovernanceLocks(transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, preferenceStore);
        var commands = new DefaultCommandExecutor(transactions, principals, receipts, ids, clock);
        var effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        scopes = new DefaultScopeResolver(access);
        authorization = new DefaultAuthorizationService(access, DefaultActionRegistry.standard(), clock,
                grants, descriptors, conditions, tenantUpdates);
        resources = grants == null ? null : new DefaultResourceAccessService(grants, descriptors,
                conditions, access, authorization, locks, commands, effects, transactions, tenantStore, workspaceStore, identity, ids, clock);
        writeGuard = new DefaultWorkspaceWriteGuard(transactions, principals, locks, authorization, grants != null);
        preferences = new DefaultWorkspacePreferenceService(principals, preferenceStore, authorization, commands, locks, effects);
        personal = new DefaultPersonalWorkspaceService(principals, commands, access, locks, tenantStore, workspaceStore,
                tenantMembers, workspaceMembers, preferenceStore, policyStore, defaults, ids, clock, effects, preferences, receipts, provisioning);
        tenantGovernance = new DefaultTenantGovernanceService(principals, access, authorization, locks, commands, effects,
                transactions, tenantStore, tenantMembers, policyStore, tenantProvisioning, organizationTemplate, ids, clock);
        tenantQueries = new DefaultTenantQueryService(principals, access, tenantMembers, budget, nanoTime, authorization, tenantGovernance);
        workspaces = new DefaultWorkspaceService(principals, access, authorization, locks, commands, tenantStore,
                workspaceStore, workspaceMembers, policyStore, preferenceStore, defaults, ids, clock, effects, provisioning, budget, nanoTime);
        policies = new DefaultWorkspacePolicyService(principals, access, authorization, commands, locks, effects,
                tenantStore, workspaceStore, policyStore, transactions, clock);
        receiptQueries = new DefaultCommandReceiptQueryService(principals, receipts, preferenceStore, tenantStore,
                access, authorization, locks, transactions, tenantGovernance);
    }

    public DefaultResourceAccessService resources() {
        return Objects.requireNonNull(resources, "Resource ACL storage must be configured");
    }

    public DefaultPersonalWorkspaceService personal() {
        return personal;
    }

    public TenantQueryService tenantQueries() {
        return tenantQueries;
    }

    public TenantGovernanceService tenantGovernance() {
        return tenantGovernance;
    }

    public DefaultWorkspaceService workspaces() {
        return workspaces;
    }

    public DefaultWorkspacePreferenceService preferences() {
        return preferences;
    }

    public DefaultWorkspacePolicyService policies() {
        return policies;
    }

    public DefaultScopeResolver scopes() {
        return scopes;
    }

    public DefaultAuthorizationService authorization() {
        return authorization;
    }

    public DefaultWorkspaceWriteGuard writeGuard() {
        return writeGuard;
    }

    public DefaultCommandReceiptQueryService receiptQueries() {
        return receiptQueries;
    }
}
