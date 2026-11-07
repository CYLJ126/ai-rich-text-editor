package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.platform.IdentityProvider;
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
 * 模型执行 ACL 装配
 * <p>
 * 原始资源描述提供者仍独立于执行门控，避免授权图循环。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformModelAclConfiguration {

    @Bean
    ModelAclAccess modelAclAccess(ModelAclScopeStore scopes, ModelResourceStore models, IdentityProvider identity,
                                  @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                  TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PreferenceStore preferences,
                                  @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("platformWorkspaceClock") Clock clock,
                                  ObjectProvider<com.arte.ainew.spi.model.ModelPublicationRuntime> personal) {
        return new PlatformModelAclAccess(scopes, models, identity, tx, authorization, tenants, workspaces, tenantMembers, members, preferences, scheduler, clock)
                .resourceRuntime(personal::getIfAvailable);
    }

    @Bean
    ModelAclMigrationService modelAclMigrationService(ModelAclScopeStore scopes, RbacIdentityProvider identities,
                                                      @Qualifier("appDataSource") DataSource source,
                                                      @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                      @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                      TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                                      WorkspaceMembershipStore members, PreferenceStore preferences) {
        return new PlatformModelAclMigrationService(scopes, identities, new RbacToolPermissionReader(source),
                tx, authorization, tenants, workspaces, tenantMembers, members, preferences);
    }
}
