package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.platform.CommandReceiptQueryService;
import com.arte.core.api.workspace.*;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 公共应用服务装配；身份和数据库为显式前置条件，不注册 HTTP、不执行 DDL 或启动回填。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:22 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.platform.workspace", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PlatformWorkspaceProperties.class)
public class PlatformWorkspaceConfiguration {

    public PlatformWorkspaceConfiguration(Environment environment) {
        if (!environment.getProperty("arte.platform.identity.enabled", Boolean.class, false)
                || !environment.getProperty("arte.platform.persistence.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Workspace services require arte.platform.identity.enabled=true and arte.platform.persistence.enabled=true");
        }
    }

    @Bean(defaultCandidate = false)
    Clock platformWorkspaceClock() {
        return Clock.systemUTC();
    }

    @Bean
    DefaultWorkspacePolicies platformWorkspacePolicies(PersonalTenantPolicyTemplateStore templates) {
        return new DefaultWorkspacePolicies(templates);
    }

    @Bean
    DefaultWorkspaceServices platformWorkspaceServices(IdentityProvider identity,
                                                       @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                                       TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                                       WorkspaceMembershipStore workspaceMembers, PolicyStore policies, PreferenceStore preferences,
                                                       CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                                       @Qualifier("platformWorkspaceClock") Clock clock,
                                                       @Qualifier("platformWorkspacePolicies") DefaultWorkspacePolicies defaults,
                                                       WorkspaceAccessFactsStore facts, java.util.List<WorkspaceProvisioningParticipant> provisioning) {
        return new DefaultWorkspaceServices(identity, transactions, tenants, workspaces, tenantMembers,
                workspaceMembers, policies, preferences, receipts, outbox, audit, ids, clock, defaults, facts, provisioning);
    }

    @Bean
    PersonalWorkspaceService personalWorkspaceService(DefaultWorkspaceServices services) {
        return services.personal();
    }

    @Bean
    WorkspaceService workspaceService(DefaultWorkspaceServices services) {
        return services.workspaces();
    }

    @Bean
    WorkspacePreferenceService workspacePreferenceService(DefaultWorkspaceServices services) {
        return services.preferences();
    }

    @Bean
    WorkspacePolicyService workspacePolicyService(DefaultWorkspaceServices services) {
        return services.policies();
    }

    @Bean
    ScopeResolver workspaceScopeResolver(DefaultWorkspaceServices services) {
        return services.scopes();
    }

    @Bean
    AuthorizationService platformAuthorizationService(DefaultWorkspaceServices services) {
        return services.authorization();
    }

    @Bean
    WorkspaceWriteGuard workspaceWriteGuard(DefaultWorkspaceServices services) {
        return services.writeGuard();
    }

    @Bean
    CommandReceiptQueryService workspaceCommandReceiptQueryService(DefaultWorkspaceServices services) {
        return services.receiptQueries();
    }

    @Bean
    ReactiveWorkspaceOperations reactiveWorkspaceOperations(RbacIdentityProvider identities,
                                                            PlatformPersistenceExecutor executor, PersonalWorkspaceService personal, WorkspaceService workspaces,
                                                            WorkspacePreferenceService preferences, ScopeResolver scopes,
                                                            @Qualifier("platformAuthorizationService") AuthorizationService authorization, WorkspaceWriteGuard guard,
                                                            CommandReceiptQueryService receipts, WorkspacePolicyService policies) {
        return new ReactiveWorkspaceOperations(identities, executor, personal, workspaces, preferences, scopes, authorization, guard, receipts, policies);
    }
}
