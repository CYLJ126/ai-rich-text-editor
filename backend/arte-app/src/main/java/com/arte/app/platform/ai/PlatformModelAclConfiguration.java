package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.control.AiResourceEntitlementService;
import com.arte.ainew.spi.model.ModelAclAccess;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.platform.*;
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
                                  @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("platformWorkspaceClock") Clock clock) {
        return new PlatformModelAclAccess(scopes, models, identity, tx, authorization, tenants, workspaces, tenantMembers, members, preferences, scheduler, clock);
    }

    @Bean
    ModelAclMigrationService modelAclMigrationService(ModelAclScopeStore scopes, ModelResourceStore models, NewAiProperties properties,
                                                      PlatformAiControlMapper catalog, PlatformJson json, RbacIdentityProvider identities, @Qualifier("appDataSource") DataSource source,
                                                      @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                      TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PreferenceStore preferences,
                                                      CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock,
                                                      ModelAclAccess gate, ObjectProvider<AiResourceEntitlementService> entitlements) {
        PlatformModelAclMigrationService service = new PlatformModelAclMigrationService(scopes, models, properties, catalog, json, identities, new RbacToolPermissionReader(source),
                tx, authorization, tenants, workspaces, tenantMembers, members, preferences, receipts, outbox, audit, ids, clock);
        service.runtimeReady(() -> ModelAclStartupSafety.requireReady(properties, gate, entitlements.getIfAvailable()));
        return service;
    }
}
