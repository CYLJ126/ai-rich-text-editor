package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.model.DefaultModelResourceService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * 内部管理闭环装配；领域提供者只依赖原始 Store，避免与公共授权图形成依赖环。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformModelResourceConfiguration {

    @Bean
    ModelResourceAccessAdapter modelResourceAccessAdapter(ModelResourceStore store,
                                                          org.springframework.beans.factory.ObjectProvider<com.arte.ainew.spi.model.ModelSharingStore> sharing) {
        ModelResourceAccessAdapter adapter = new ModelResourceAccessAdapter(store);
        adapter.sharing(sharing.getIfAvailable());
        return adapter;
    }

    @Bean
    ModelPublicationSource modelPublicationSource(NewAiProperties properties, PlatformAiControlMapper mapper, PlatformJson json,
                                                  @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, RbacIdentityProvider identities, @Qualifier("appDataSource") DataSource source) {
        return new PlatformModelPublicationSource(properties, mapper, json, tx, identities, new RbacToolPermissionReader(source));
    }

    @Bean
    ModelResourceService modelResourceService(ModelResourceStore store, ModelPublicationSource publications,
                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces,
                                              TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PreferenceStore preferences,
                                              @Qualifier("platformAuthorizationService") AuthorizationService authorization, ResourceRegistrationService registration,
                                              CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock,
                                              org.springframework.beans.factory.ObjectProvider<com.arte.ainew.spi.model.ModelSharingStore> sharing,
                                              RbacIdentityProvider identities, @Qualifier("appDataSource") DataSource source) {
        DefaultModelResourceService service = new DefaultModelResourceService(store, publications, tx, identity, tenants, workspaces, tenantMembers, members, preferences, authorization, registration, receipts, outbox, audit, ids, clock);
        service.sharing(sharing.getIfAvailable());
        RbacToolPermissionReader permissions = new RbacToolPermissionReader(source);
        service.tenantCreation(actor -> {
            String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() ->
                    new com.arte.core.exception.platform.PlatformException(com.arte.core.enums.platform.PlatformErrorCodeEnum.UNAUTHENTICATED, "tenant-model"));
            java.util.Set<String> current = permissions.current(name);
            if (!current.contains("tenantModel:create") && !current.contains("aiConfiguration:manage") && !current.contains("admin")) {
                throw new com.arte.core.exception.platform.PlatformException(com.arte.core.enums.platform.PlatformErrorCodeEnum.ACCESS_DENIED, "tenant-model");
            }
        });
        return service;
    }
}
