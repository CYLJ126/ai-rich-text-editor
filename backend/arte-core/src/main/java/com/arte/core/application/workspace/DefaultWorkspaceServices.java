package com.arte.core.application.workspace;

import com.arte.core.application.access.DefaultActionRegistry;
import com.arte.core.application.access.DefaultAuthorizationService;
import com.arte.core.application.access.DefaultWorkspaceWriteGuard;
import com.arte.core.application.platform.CurrentPrincipalResolver;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;

import java.time.Clock;

/**
 * 无框架的首批默认服务装配
 * <p>
 * app 提供同库 SPI 后组合，core 不自行注册 HTTP 或数据库 Bean。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultWorkspaceServices {

    private final DefaultPersonalWorkspaceService personal;
    private final DefaultWorkspaceService workspaces;
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
                preferenceStore, receipts, outbox, audit, ids, clock, defaults, batchFacts, java.util.List.of());
    }

    public DefaultWorkspaceServices(IdentityProvider identity, PublicTransactionRunner transactions,
                                    TenantStore tenantStore, WorkspaceStore workspaceStore, TenantMembershipStore tenantMembers,
                                    WorkspaceMembershipStore workspaceMembers, PolicyStore policyStore, PreferenceStore preferenceStore,
                                    CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                    Clock clock, DefaultWorkspacePolicies defaults, WorkspaceAccessFactsStore batchFacts, java.util.List<WorkspaceProvisioningParticipant> provisioning) {
        var principals = new CurrentPrincipalResolver(identity);
        var access = new WorkspaceAccessSupport(principals, tenantStore, workspaceStore, tenantMembers,
                workspaceMembers, policyStore, preferenceStore, clock, transactions, batchFacts);
        var locks = new WorkspaceGovernanceLocks(transactions, tenantStore, workspaceStore, tenantMembers, workspaceMembers, preferenceStore);
        var commands = new DefaultCommandExecutor(transactions, principals, receipts, ids, clock);
        var effects = new WorkspaceMutationEffects(outbox, audit, ids, clock);
        scopes = new DefaultScopeResolver(access);
        authorization = new DefaultAuthorizationService(access, DefaultActionRegistry.standard(), clock);
        writeGuard = new DefaultWorkspaceWriteGuard(transactions, principals, locks, authorization);
        preferences = new DefaultWorkspacePreferenceService(principals, preferenceStore, authorization, commands, locks, effects);
        personal = new DefaultPersonalWorkspaceService(principals, commands, access, locks, tenantStore, workspaceStore,
                tenantMembers, workspaceMembers, preferenceStore, policyStore, defaults, ids, clock, effects, preferences, receipts, provisioning);
        workspaces = new DefaultWorkspaceService(principals, access, authorization, locks, commands, tenantStore,
                workspaceStore, workspaceMembers, policyStore, preferenceStore, defaults, ids, clock, effects, provisioning);
        policies = new DefaultWorkspacePolicyService(principals, access, authorization, commands, locks, effects,
                tenantStore, workspaceStore, policyStore, transactions, clock);
        receiptQueries = new DefaultCommandReceiptQueryService(principals, receipts, preferenceStore, tenantStore,
                access, authorization, locks, transactions);
    }

    public DefaultPersonalWorkspaceService personal() {
        return personal;
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
