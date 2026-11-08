package com.arte.app.platform.ai;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.spi.access.ResourceGrantStore;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * 具体账户核对的公共边界装配
 * <p>
 * 不在启动时补授角色、读账本或修改权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/08 17:30 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformBudgetReconciliationConfiguration {

    @Bean
    PlatformBudgetAccountAccess budgetAccountAccess(@Qualifier("appDataSource") DataSource source, RbacIdentityProvider identities,
                                                    @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                                    TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                                    PreferenceStore preferences, @Qualifier("platformAuthorizationService") ObjectProvider<AuthorizationService> authorization,
                                                    ResourceGrantStore grants, AuditSink audit, @Qualifier("newAiClock") Clock clock,
                                                    @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, com.arte.ainew.config.NewAiProperties properties) {
        if (!"appDataSource".equals(properties.dataSourceBean()))
            throw new IllegalStateException("Budget reconciliation requires the same appDataSource transaction");
        return new PlatformBudgetAccountAccess(source, identities, new RbacToolPermissionReader(source), transactions,
                new WorkspaceGovernanceLocks(transactions, tenants, workspaces, tenantMembers, members, preferences),
                authorization, grants, audit, clock, scheduler);
    }
}
